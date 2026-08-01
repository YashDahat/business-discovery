package com.business.discovery.repository;

import com.business.discovery.model.PlatformUser;
import com.business.discovery.model.PlatformUser.Role;
import com.business.discovery.model.PlatformUser.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByRole(Role role);

    long countByStatus(UserStatus status);

    // Guards the "last active operator" lockout check.
    long countByRoleAndStatus(Role role, UserStatus status);
}
