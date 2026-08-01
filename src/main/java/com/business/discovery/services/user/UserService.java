package com.business.discovery.services.user;

import com.business.discovery.dto.access.AccessSummaryDto;
import com.business.discovery.dto.access.CreateUserRequest;
import com.business.discovery.dto.access.UpdateUserRequest;
import com.business.discovery.dto.access.UserSummaryDto;
import com.business.discovery.model.BusinessEntity;
import com.business.discovery.model.PlatformUser;
import com.business.discovery.model.PlatformUser.Role;
import com.business.discovery.model.PlatformUser.UserStatus;
import com.business.discovery.repository.BusinessEntityRepository;
import com.business.discovery.repository.PlatformUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User administration for the Access & Roles screen: create, list, update, block/unblock
 * (via status), and delete platform users. Passwords are BCrypt-hashed here — plaintext
 * never leaves this class.
 *
 * Lockout safeguards: {@link #updateUser} and {@link #deleteUser} refuse (409) any change
 * that would remove the caller's own operator account or the last remaining ACTIVE operator.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final PlatformUserRepository userRepository;
    private final BusinessEntityRepository businessRepository;
    private final PasswordEncoder passwordEncoder;

    // ─── Create ───────────────────────────────────────────

    @Transactional
    public UserSummaryDto createUser(CreateUserRequest req) {
        String name  = requireText(req.name(), "name");
        String email = normalizeEmail(req.email());
        if (!email.contains("@")) {
            throw badRequest("A valid email is required");
        }
        if (req.role() == null) {
            throw badRequest("role is required");
        }
        if (req.password() == null || req.password().length() < MIN_PASSWORD_LENGTH) {
            throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with this email already exists");
        }

        Set<UUID> businesses = scopedBusinesses(req.role(), req.assignedBusinessIds());

        PlatformUser user = PlatformUser.builder()
                .name(name)
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(req.role())
                .status(UserStatus.ACTIVE)
                .assignedBusinessIds(businesses)
                .build();

        return toSummary(userRepository.save(user));
    }

    // ─── Read ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UserSummaryDto> listUsers() {
        List<PlatformUser> users = userRepository.findAll();

        // Resolve business titles in a single query instead of one per user.
        Set<UUID> allBusinessIds = users.stream()
                .flatMap(u -> u.getAssignedBusinessIds().stream())
                .collect(Collectors.toSet());
        Map<UUID, String> titles = businessRepository.findAllById(allBusinessIds).stream()
                .collect(Collectors.toMap(BusinessEntity::getId, this::displayTitle));

        return users.stream()
                .sorted((a, b) -> b.getCreatedAt() == null || a.getCreatedAt() == null
                        ? 0 : b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(u -> toSummary(u, titles))
                .toList();
    }

    /** The current user's own profile row — used by GET /api/auth/profile. */
    @Transactional(readOnly = true)
    public UserSummaryDto getSummary(UUID id) {
        return toSummary(load(id));
    }

    @Transactional(readOnly = true)
    public AccessSummaryDto buildAccessSummary() {
        long operators = userRepository.countByRole(Role.OPERATOR);
        long analysts  = userRepository.countByRole(Role.ANALYST);
        long clients   = userRepository.countByRole(Role.CLIENT);
        long resellers = userRepository.countByRole(Role.RESELLER);
        long pending   = userRepository.countByStatus(UserStatus.PENDING);
        long total     = userRepository.count();
        long totalBusinesses = businessRepository.count();
        return new AccessSummaryDto(
                total,
                operators + analysts,   // internal (discovery staff)
                clients + resellers,    // external
                operators, analysts, clients, resellers,
                pending, totalBusinesses);
    }

    // ─── Update / block / delete ──────────────────────────

    /**
     * Applies only the non-null fields of {@code req}. A non-blank password re-hashes and
     * resets the user's credentials. Setting status to DISABLED blocks the user; ACTIVE
     * unblocks them. {@code actingUserId} is the logged-in operator (may be null in Stage 1
     * while the console is open) — used only for the self-lockout guard.
     */
    @Transactional
    public UserSummaryDto updateUser(UUID id, UpdateUserRequest req, UUID actingUserId) {
        PlatformUser user = load(id);

        Role newRole      = req.role()   != null ? req.role()   : user.getRole();
        UserStatus status = req.status() != null ? req.status() : user.getStatus();

        boolean losesOperator = user.getRole() == Role.OPERATOR && user.getStatus() == UserStatus.ACTIVE
                && (newRole != Role.OPERATOR || status != UserStatus.ACTIVE);
        assertNoOperatorLockout(user, actingUserId, losesOperator, status != UserStatus.ACTIVE);

        if (req.name() != null && !req.name().isBlank()) {
            user.setName(req.name().trim());
        }
        user.setRole(newRole);
        user.setStatus(status);

        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < MIN_PASSWORD_LENGTH) {
                throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
            }
            user.setPasswordHash(passwordEncoder.encode(req.password()));
        }

        if (req.assignedBusinessIds() != null) {
            user.setAssignedBusinessIds(scopedBusinesses(newRole, req.assignedBusinessIds()));
        } else if (!isBusinessScoped(newRole)) {
            // Role changed to a non-scoped role — drop any stale assignments.
            user.setAssignedBusinessIds(new HashSet<>());
        }

        return toSummary(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(UUID id, UUID actingUserId) {
        PlatformUser user = load(id);
        boolean removesOperator = user.getRole() == Role.OPERATOR && user.getStatus() == UserStatus.ACTIVE;
        assertNoOperatorLockout(user, actingUserId, removesOperator, true);
        userRepository.delete(user);
    }

    /**
     * Self-service password change: verifies the caller's current password before
     * setting the new one. Used by the profile screen (POST /api/auth/change-password).
     */
    @Transactional
    public void changePassword(UUID id, String currentPassword, String newPassword) {
        PlatformUser user = load(id);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw badRequest("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw badRequest("New password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    @Transactional
    public void recordLogin(String email) {
        userRepository.findByEmail(normalizeEmail(email)).ifPresent(u -> {
            u.setLastLoginAt(LocalDateTime.now());
            userRepository.save(u);
        });
    }

    // ─── Internals ────────────────────────────────────────

    private void assertNoOperatorLockout(PlatformUser target, UUID actingUserId,
                                         boolean removesOperator, boolean isBlockOrDelete) {
        if (isBlockOrDelete && actingUserId != null && actingUserId.equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You cannot block or delete your own operator account");
        }
        if (removesOperator
                && userRepository.countByRoleAndStatus(Role.OPERATOR, UserStatus.ACTIVE) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot remove the last active operator — the platform would be locked out");
        }
    }

    private PlatformUser load(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private boolean isBusinessScoped(Role role) {
        return role == Role.CLIENT || role == Role.RESELLER;
    }

    /** Only CLIENT/RESELLER carry explicit assignments; CLIENT must have at least one. */
    private Set<UUID> scopedBusinesses(Role role, List<UUID> requested) {
        if (!isBusinessScoped(role)) {
            return new HashSet<>();
        }
        Set<UUID> ids = requested == null ? new HashSet<>() : new LinkedHashSet<>(requested);
        if (role == Role.CLIENT && ids.isEmpty()) {
            throw badRequest("A Client must be assigned at least one business");
        }
        return ids;
    }

    private UserSummaryDto toSummary(PlatformUser u) {
        Map<UUID, String> titles = businessRepository.findAllById(u.getAssignedBusinessIds()).stream()
                .collect(Collectors.toMap(BusinessEntity::getId, this::displayTitle));
        return toSummary(u, titles);
    }

    private UserSummaryDto toSummary(PlatformUser u, Map<UUID, String> titles) {
        List<UUID> ids = new ArrayList<>(u.getAssignedBusinessIds());
        List<String> names = ids.stream()
                .map(id -> titles.getOrDefault(id, "Unknown business"))
                .toList();
        return new UserSummaryDto(
                u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getStatus(),
                ids, names, u.getLastLoginAt(), u.getCreatedAt());
    }

    private String displayTitle(BusinessEntity b) {
        return b.getTitle() != null ? b.getTitle() : "Untitled business";
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
        return value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
