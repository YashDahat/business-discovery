package com.business.discovery.services.user;

import com.business.discovery.model.PlatformUser.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the authenticated principal for row-level (business) access decisions that the
 * URL-based SecurityFilterChain can't express:
 *   OPERATOR / ANALYST → every business,
 *   CLIENT             → only their assigned businesses,
 *   RESELLER           → no individual business records (aggregate KPI data only).
 *
 * Deliberately model-agnostic (no BusinessEntity import) so controllers keep their own
 * filtering; this only answers "which is the caller and what may they see".
 */
@Component
public class AccessScope {

    public Optional<PlatformUserDetails> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof PlatformUserDetails principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    public Optional<Role> role() {
        return current().map(PlatformUserDetails::getRole);
    }

    /** OPERATOR and ANALYST may read every business. */
    public boolean canViewAllBusinesses() {
        return role().map(r -> r == Role.OPERATOR || r == Role.ANALYST).orElse(false);
    }

    public boolean isClient() {
        return role().map(r -> r == Role.CLIENT).orElse(false);
    }

    public boolean isReseller() {
        return role().map(r -> r == Role.RESELLER).orElse(false);
    }

    public Set<UUID> assignedBusinessIds() {
        return current().map(PlatformUserDetails::getAssignedBusinessIds).orElseGet(Set::of);
    }

    /** Whether the caller may view a specific business record. */
    public boolean canViewBusiness(UUID businessId) {
        return current().map(p -> switch (p.getRole()) {
            case OPERATOR, ANALYST -> true;
            case CLIENT -> p.getAssignedBusinessIds().contains(businessId);
            case RESELLER -> false;
        }).orElse(false);
    }
}
