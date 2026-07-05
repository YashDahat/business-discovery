package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RolePrefixPatcherTest {

    @TempDir
    Path src;

    private Path write(String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void prefixesRoleAuthorityWhenHasRoleUsed() throws Exception {
        write("com/x/config/SecurityConfig.java",
                "class SecurityConfig { void c() { auth.requestMatchers(\"/api/v1/admin/**\").hasRole(\"ADMIN\"); } }");
        Path user = write("com/x/model/User.java",
                "class User { public Collection<GrantedAuthority> getAuthorities() {"
                + " return List.of(new SimpleGrantedAuthority(role.name())); } }");

        boolean changed = RolePrefixPatcher.fix(src);

        assertThat(changed).isTrue();
        assertThat(Files.readString(user)).contains("new SimpleGrantedAuthority(\"ROLE_\" + role.name())");
    }

    @Test
    void leavesAuthoritiesAloneWhenOnlyHasAuthorityUsed() throws Exception {
        write("com/x/config/SecurityConfig.java",
                "class SecurityConfig { void c() { auth.requestMatchers(\"/admin/**\").hasAuthority(\"ADMIN\"); } }");
        Path user = write("com/x/model/User.java",
                "class User { getAuthorities() { return List.of(new SimpleGrantedAuthority(role.name())); } }");

        boolean changed = RolePrefixPatcher.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(user)).contains("new SimpleGrantedAuthority(role.name())");
    }

    @Test
    void doesNotDoublePrefixWhenAlreadyPrefixed() throws Exception {
        write("com/x/config/SecurityConfig.java", "hasRole(\"ADMIN\")");
        Path user = write("com/x/model/User.java",
                "new SimpleGrantedAuthority(\"ROLE_\" + role.name())");

        boolean changed = RolePrefixPatcher.fix(src);

        assertThat(changed).isFalse();
        assertThat(Files.readString(user)).doesNotContain("ROLE_\" + \"ROLE_");
    }

    @Test
    void handlesNestedMethodCallArgument() {
        String out = RolePrefixPatcher.prefixRoleAuthorities(
                "x = new SimpleGrantedAuthority(user.getRole().name());");
        assertThat(out).isEqualTo("x = new SimpleGrantedAuthority(\"ROLE_\" + user.getRole().name());");
    }
}
