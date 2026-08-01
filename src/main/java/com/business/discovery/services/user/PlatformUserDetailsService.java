package com.business.discovery.services.user;

import com.business.discovery.model.PlatformUser;
import com.business.discovery.repository.PlatformUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a {@link PlatformUser} by email for Spring Security's DaoAuthenticationProvider.
 * Because this is the only UserDetailsService bean, Spring Boot wires it (plus the
 * BCrypt PasswordEncoder) into the global AuthenticationManager automatically.
 */
@Service
@RequiredArgsConstructor
public class PlatformUserDetailsService implements UserDetailsService {

    private final PlatformUserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username == null ? "" : username.trim().toLowerCase();
        PlatformUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user for email: " + email));
        return new PlatformUserDetails(user);
    }
}
