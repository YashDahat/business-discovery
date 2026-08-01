package com.business.discovery.services.user;

import com.business.discovery.model.PlatformUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Security principal backed by a {@link PlatformUser} row. Carries the user id,
 * role, and assigned business ids so Stage-2 authorization can scope requests
 * (e.g. CLIENT → only their own businesses) straight off the authenticated principal.
 *
 * A user whose status is not ACTIVE reports {@code isEnabled() == false}, so blocked
 * users are rejected at authentication time.
 */
public class PlatformUserDetails implements UserDetails {

    private final PlatformUser user;

    public PlatformUserDetails(PlatformUser user) {
        this.user = user;
    }

    public UUID getId() {
        return user.getId();
    }

    public PlatformUser.Role getRole() {
        return user.getRole();
    }

    public Set<UUID> getAssignedBusinessIds() {
        return user.getAssignedBusinessIds();
    }

    public String getName() {
        return user.getName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == PlatformUser.UserStatus.ACTIVE;
    }
}
