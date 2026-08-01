package com.business.discovery.services.user;

import com.business.discovery.model.PlatformUser;
import com.business.discovery.model.PlatformUser.Role;
import com.business.discovery.model.PlatformUser.UserStatus;
import com.business.discovery.repository.PlatformUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Seeds a default OPERATOR from env at startup so you can always log in.
 *
 * - If no user exists for OPS_ADMIN_EMAIL → create it.
 * - If it exists and OPS_ADMIN_FORCE_RESET=true → overwrite its password + reactivate it
 *   (break-glass recovery for a lost operator password). Flip the flag back to false after.
 * - Otherwise → leave the existing row untouched (never clobber a deliberately-changed password).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperatorSeeder implements CommandLineRunner {

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ops.admin.email:}")
    private String adminEmail;

    @Value("${ops.admin.password:}")
    private String adminPassword;

    @Value("${ops.admin.name:Operator}")
    private String adminName;

    @Value("${ops.admin.force-reset:false}")
    private boolean forceReset;

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.warn("ops.admin.email/password not set — no default operator seeded. "
                    + "Set OPS_ADMIN_EMAIL and OPS_ADMIN_PASSWORD in .env to enable login.");
            return;
        }

        String email = adminEmail.trim().toLowerCase();
        Optional<PlatformUser> existing = userRepository.findByEmail(email);

        if (existing.isEmpty()) {
            userRepository.save(PlatformUser.builder()
                    .name(adminName)
                    .email(email)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .role(Role.OPERATOR)
                    .status(UserStatus.ACTIVE)
                    .build());
            log.info("Seeded default operator '{}'.", email);
        } else if (forceReset) {
            PlatformUser operator = existing.get();
            operator.setPasswordHash(passwordEncoder.encode(adminPassword));
            operator.setStatus(UserStatus.ACTIVE);
            userRepository.save(operator);
            log.warn("ops.admin.force-reset=true — reset password and reactivated operator '{}'. "
                    + "Set OPS_ADMIN_FORCE_RESET=false and restart.", email);
        } else {
            log.info("Default operator '{}' already present — leaving untouched.", email);
        }
    }
}
