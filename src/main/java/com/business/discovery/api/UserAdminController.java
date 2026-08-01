package com.business.discovery.api;

import com.business.discovery.dto.access.AccessSummaryDto;
import com.business.discovery.dto.access.CreateUserRequest;
import com.business.discovery.dto.access.UpdateUserRequest;
import com.business.discovery.dto.access.UserSummaryDto;
import com.business.discovery.services.user.PlatformUserDetails;
import com.business.discovery.services.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Operator-facing user administration behind the Access & Roles screen.
 *
 * Stage 1: reachable by anyone (console is still open). Stage 2 restricts this whole
 * controller to OPERATOR via the SecurityFilterChain (/api/admin/** → hasRole OPERATOR).
 * The acting principal is threaded into the service for self-lockout protection.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;

    @GetMapping
    public List<UserSummaryDto> list() {
        return userService.listUsers();
    }

    @GetMapping("/summary")
    public AccessSummaryDto summary() {
        return userService.buildAccessSummary();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummaryDto create(@RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    @PatchMapping("/{id}")
    public UserSummaryDto update(@PathVariable UUID id,
                                 @RequestBody UpdateUserRequest request,
                                 @AuthenticationPrincipal PlatformUserDetails principal) {
        return userService.updateUser(id, request, principal != null ? principal.getId() : null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,
                       @AuthenticationPrincipal PlatformUserDetails principal) {
        userService.deleteUser(id, principal != null ? principal.getId() : null);
    }
}
