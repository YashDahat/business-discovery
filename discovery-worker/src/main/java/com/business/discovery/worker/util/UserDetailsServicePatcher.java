package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Supplies a UserDetailsService implementation when SecurityConfig/JwtAuthFilter inject
 * the interface but nothing in the generated code implements it — the fourth
 * "compiles clean, dies at boot" defect class, after the JWT cycle
 * ({@link JwtCircularDependencyPatcher}), the PasswordEncoder cycle/missing-bean
 * ({@link PasswordEncoderExtractor}), and missing infra beans ({@link MissingBeanPatcher}).
 *
 * Vikram's Fitness Studio (2026-07-19): SecurityConfig and JwtAuthFilter both
 * constructor-inject UserDetailsService for DaoAuthenticationProvider; UserService exists
 * and handles register/login itself but does not implement the interface. javac is blind
 * to this (the interface reference resolves fine — it's on the classpath from
 * spring-security); Spring then refuses to refresh the context ("No qualifying bean of
 * type UserDetailsService"), crash-looping until the smoke gate kills it.
 *
 * Unlike RestTemplate/ModelMapper ({@link MissingBeanPatcher}), there is no safe no-arg
 * default — a real implementation must query the actual UserRepository this project
 * generated. Wired to whatever findBy*(String) lookup method and whatever
 * Set&lt;Role&gt;/List&lt;Role&gt; getter actually exist on disk, not a fixed assumption —
 * so this works identically across every business vertical. The auth layer is
 * vertical-agnostic baseline infra (arch_outline.txt adds it to EVERY project, independent
 * of what the business sells), so the defect and the fix recur identically regardless of
 * business type.
 *
 * Deliberately narrow, matching this package's other patchers: if the UserRepository
 * lookup method or the User getters can't be confirmed on disk, this backs off and leaves
 * the gap for ErrorFixAgent rather than guessing a shape that doesn't compile.
 */
@Slf4j
public final class UserDetailsServicePatcher {

    private static final Pattern IMPLEMENTS_UDS = Pattern.compile(
            "class\\s+\\w+(?:<[^>]*>)?(?:\\s+extends\\s+[\\w.<>]+)?\\s+implements\\s+[^{]*\\bUserDetailsService\\b");

    private static final Pattern USER_REPO_FINDER = Pattern.compile(
            "Optional\\s*<\\s*User\\s*>\\s+findBy(\\w+)\\s*\\(\\s*String\\s+(\\w+)\\s*\\)");

    private static final Pattern ROLES_GETTER = Pattern.compile(
            "(?:Set|List|Collection)<\\s*Role\\s*>\\s+(get\\w+)\\s*\\(");

    private static final Pattern USER_IMPORT = Pattern.compile("import\\s+([\\w.]+)\\.User;");

    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    private UserDetailsServicePatcher() {}

    /** Returns true if CustomUserDetailsService.java was created. */
    public static boolean fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return false;

        List<Path> sources;
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            log.warn("[UserDetailsServicePatcher] Walk failed: {}", e.getMessage());
            return false;
        }

        if (alreadyImplemented(sources)) return false;

        Path injector = MissingBeanPatcher.firstInjectorOf(sources, "UserDetailsService");
        if (injector == null) return false; // nobody needs it

        Path userRepositoryFile = findFileNamed(sources, "UserRepository.java");
        Path userEntityFile = findFileNamed(sources, "User.java");
        if (userRepositoryFile == null || userEntityFile == null) {
            log.warn("[UserDetailsServicePatcher] UserDetailsService is injected by {} but "
                    + "UserRepository/User not found — leaving for ErrorFixAgent",
                    injector.getFileName());
            return false;
        }

        String repoContent = read(userRepositoryFile);
        String userContent = read(userEntityFile);
        if (repoContent == null || userContent == null) return false;

        Finder finder = pickFinder(repoContent);
        if (finder == null) {
            log.warn("[UserDetailsServicePatcher] No findBy*(String) lookup on UserRepository — "
                    + "leaving for ErrorFixAgent");
            return false;
        }

        String identityGetter = "get" + finder.field();
        if (!userContent.contains(identityGetter + "(")) {
            log.warn("[UserDetailsServicePatcher] User has no {}() matching UserRepository.{} — "
                    + "leaving for ErrorFixAgent", identityGetter, finder.methodName());
            return false;
        }
        if (!userContent.contains("getPassword(")) {
            log.warn("[UserDetailsServicePatcher] User has no getPassword() — leaving for ErrorFixAgent");
            return false;
        }
        Matcher rolesMatcher = ROLES_GETTER.matcher(userContent);
        if (!rolesMatcher.find()) {
            log.warn("[UserDetailsServicePatcher] No Set<Role>/List<Role> getter on User — "
                    + "leaving for ErrorFixAgent");
            return false;
        }

        return writeImplementation(userRepositoryFile, repoContent, finder, identityGetter, rolesMatcher.group(1));
    }

    private static boolean alreadyImplemented(List<Path> sources) {
        for (Path p : sources) {
            String content = read(p);
            if (content != null && IMPLEMENTS_UDS.matcher(content).find()) return true;
        }
        return false;
    }

    private record Finder(String methodName, String paramName, String field) {}

    /** Prefers findByUsername, then findByEmail, then the first findBy*(String) found. */
    private static Finder pickFinder(String repoContent) {
        Matcher m = USER_REPO_FINDER.matcher(repoContent);
        Finder first = null, username = null, email = null;
        while (m.find()) {
            Finder f = new Finder("findBy" + m.group(1), m.group(2), m.group(1));
            if (first == null) first = f;
            if (f.methodName().equalsIgnoreCase("findByUsername")) username = f;
            if (f.methodName().equalsIgnoreCase("findByEmail")) email = f;
        }
        return username != null ? username : (email != null ? email : first);
    }

    private static boolean writeImplementation(Path userRepositoryFile, String repoContent, Finder finder,
                                                String identityGetter, String rolesGetter) {
        Path serviceDir = userRepositoryFile.getParent().resolveSibling("service");
        if (!Files.isDirectory(serviceDir)) serviceDir = userRepositoryFile.getParent();

        Path target = serviceDir.resolve("CustomUserDetailsService.java");
        if (Files.exists(target)) return false; // idempotent across attempts

        String repoPkg = packageOf(repoContent);
        if (repoPkg == null) return false;
        String servicePkg = siblingPackage(repoPkg, "service");

        Matcher userImport = USER_IMPORT.matcher(repoContent);
        String modelPkg = userImport.find() ? userImport.group(1) : siblingPackage(repoPkg, "model");

        String content = """
                package %s;

                import %s.User;
                import %s.UserRepository;
                import org.springframework.security.core.authority.SimpleGrantedAuthority;
                import org.springframework.security.core.userdetails.UserDetails;
                import org.springframework.security.core.userdetails.UserDetailsService;
                import org.springframework.security.core.userdetails.UsernameNotFoundException;
                import org.springframework.stereotype.Service;

                import java.util.stream.Collectors;

                /**
                 * SecurityConfig/JwtAuthFilter constructor-inject UserDetailsService for
                 * DaoAuthenticationProvider, but nothing in the generated code implemented
                 * it — this compiles clean and crash-loops the whole application at context
                 * refresh ("No qualifying bean of type UserDetailsService").
                 */
                @Service
                public class CustomUserDetailsService implements UserDetailsService {

                    private final UserRepository userRepository;

                    public CustomUserDetailsService(UserRepository userRepository) {
                        this.userRepository = userRepository;
                    }

                    @Override
                    public UserDetails loadUserByUsername(String %s) {
                        User user = userRepository.%s(%s)
                                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + %s));
                        return new org.springframework.security.core.userdetails.User(
                                user.%s(),
                                user.getPassword(),
                                user.%s().stream()
                                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                                        .collect(Collectors.toList())
                        );
                    }
                }
                """.formatted(servicePkg, modelPkg, repoPkg,
                        finder.paramName(), finder.methodName(), finder.paramName(), finder.paramName(),
                        identityGetter, rolesGetter);

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            log.info("[UserDetailsServicePatcher] UserDetailsService is injected but never "
                    + "implemented — created CustomUserDetailsService wired to "
                    + "UserRepository.{} (boot failure prevented)", finder.methodName());
            return true;
        } catch (IOException e) {
            log.warn("[UserDetailsServicePatcher] Could not write {}: {}", target, e.getMessage());
            return false;
        }
    }

    private static String siblingPackage(String pkg, String lastSegment) {
        int lastDot = pkg.lastIndexOf('.');
        return (lastDot == -1 ? "" : pkg.substring(0, lastDot + 1)) + lastSegment;
    }

    private static Path findFileNamed(List<Path> sources, String name) {
        return sources.stream().filter(p -> p.getFileName().toString().equals(name)).findFirst().orElse(null);
    }

    private static String packageOf(String content) {
        if (content == null) return null;
        Matcher m = PACKAGE_LINE.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}
