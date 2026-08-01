package com.business.discovery.api;

import com.business.discovery.dto.access.ChangePasswordRequest;
import com.business.discovery.dto.access.CurrentUserDto;
import com.business.discovery.dto.access.LoginRequest;
import com.business.discovery.dto.access.UserSummaryDto;
import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.PlatformUserRepository;
import com.business.discovery.services.user.PlatformUserDetails;
import com.business.discovery.services.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Session-based login for the Ops Console. Credentials are verified against the
 * platform_user table (BCrypt) via the AuthenticationManager; on success the
 * SecurityContext is persisted to the HTTP session so later requests resolve the user.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final PlatformUserRepository userRepository;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<CurrentUserDto> login(@RequestBody LoginRequest req,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        String email = req.email() == null ? "" : req.email().trim().toLowerCase();

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, req.password()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        userService.recordLogin(email);

        PlatformUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return ResponseEntity.ok(CurrentUserDto.from(user));
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserDto> me() {
        PlatformUser user = userRepository.findById(requirePrincipal().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        return ResponseEntity.ok(CurrentUserDto.from(user));
    }

    /** Richer view of the current user's own account for the profile screen. */
    @GetMapping("/profile")
    public ResponseEntity<UserSummaryDto> profile() {
        return ResponseEntity.ok(userService.getSummary(requirePrincipal().getId()));
    }

    /** Self-service password change from the profile screen — verifies the current password. */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        userService.changePassword(requirePrincipal().getId(), req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    private PlatformUserDetails requirePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof PlatformUserDetails principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return principal;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
