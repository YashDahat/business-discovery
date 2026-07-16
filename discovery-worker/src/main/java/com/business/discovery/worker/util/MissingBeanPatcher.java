package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Supplies infrastructure beans the generated code injects but never declares — the
 * fourth "compiles clean, dies at boot" defect class, after the JWT cycle
 * ({@link JwtCircularDependencyPatcher}) and the PasswordEncoder cycle
 * ({@link PasswordEncoderExtractor}).
 *
 * Circuit-house attempt 2 (2026-07-12): PaymentService constructor-injected a
 * {@code RestTemplate} for the Razorpay HTTP calls; no {@code @Bean} for it existed
 * anywhere. javac is blind to this — RestTemplate is a real class on the classpath from
 * spring-web, so every compile gate passed — and Spring then refused to refresh the
 * context ("required a bean of type 'org.springframework.web.client.RestTemplate' that
 * could not be found"), crash-looping until the smoke gate timed out at 120s.
 *
 * The trap is a genuine Spring Boot subtlety, not sloppiness: Boot auto-configures a
 * {@code RestTemplateBuilder}, but deliberately NOT a raw {@code RestTemplate} (it can't
 * know your timeouts/interceptors). Models reasonably assume the symmetry that isn't there.
 *
 * Deliberately narrow: only types that Spring Boot does not auto-configure AND that carry
 * a safe, dependency-free default construction are handled. A type that is injected,
 * undeclared, and NOT in this table is a genuine code defect — left alone for the
 * ErrorFixAgent rather than papered over with a guessed bean.
 */
@Slf4j
public final class MissingBeanPatcher {

    /** fully-qualified type → the bean method Spring needs. Dependency-free by construction. */
    private static final Map<String, Supplyable> SUPPLYABLE = new LinkedHashMap<>();

    static {
        SUPPLYABLE.put("RestTemplate", new Supplyable(
                "org.springframework.web.client.RestTemplate",
                "RestTemplateConfig",
                """
                    @Bean
                    public RestTemplate restTemplate() {
                        return new RestTemplate();
                    }
                """));
        SUPPLYABLE.put("ModelMapper", new Supplyable(
                "org.modelmapper.ModelMapper",
                "ModelMapperConfig",
                """
                    @Bean
                    public ModelMapper modelMapper() {
                        return new ModelMapper();
                    }
                """));
    }

    private record Supplyable(String fqcn, String configClass, String beanMethod) {}

    private MissingBeanPatcher() {}

    /** Returns the number of config classes created. */
    public static int fix(Path backendSrcDir) {
        if (!Files.exists(backendSrcDir)) return 0;

        List<Path> sources;
        try (Stream<Path> s = Files.walk(backendSrcDir)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            log.warn("[MissingBeanPatcher] Walk failed: {}", e.getMessage());
            return 0;
        }

        int created = 0;
        for (Map.Entry<String, Supplyable> entry : SUPPLYABLE.entrySet()) {
            String simpleName = entry.getKey();
            Supplyable supply = entry.getValue();

            Path injector = firstInjectorOf(sources, simpleName);
            if (injector == null) continue;                  // nobody wants it
            if (isProvidedSomewhere(sources, simpleName)) continue;  // already declared

            if (writeConfig(sources, injector, simpleName, supply)) created++;
        }
        return created;
    }

    /** A class that takes the type as a constructor parameter or an @Autowired field. */
    static Path firstInjectorOf(List<Path> sources, String simpleName) {
        Pattern ctorParam = Pattern.compile("public\\s+\\w+\\s*\\([^)]*\\b" + simpleName + "\\s+\\w+");
        Pattern field = Pattern.compile("private\\s+final\\s+" + simpleName + "\\s+\\w+\\s*;");
        for (Path p : sources) {
            String content = read(p);
            if (content == null) continue;
            if (ctorParam.matcher(content).find() || field.matcher(content).find()) return p;
        }
        return null;
    }

    /** Any @Bean method returning the type — regardless of which class declares it. */
    static boolean isProvidedSomewhere(List<Path> sources, String simpleName) {
        Pattern beanMethod = Pattern.compile(
                "@Bean[\\s\\S]{0,200}?public\\s+" + simpleName + "\\s+\\w+\\s*\\(");
        for (Path p : sources) {
            String content = read(p);
            if (content != null && beanMethod.matcher(content).find()) return true;
        }
        return false;
    }

    /**
     * Writes the config beside the injector's package root — into the existing config
     * package when one exists, so the class lands where a human would have put it and
     * stays inside the application's component scan.
     */
    private static boolean writeConfig(List<Path> sources, Path injector,
                                       String simpleName, Supplyable supply) {
        // prefer an existing config package (where a human would put it); else sit beside
        // the injector — either way it stays inside the application's component scan
        Path neighbour = sources.stream()
                .filter(p -> p.getParent() != null
                        && p.getParent().getFileName().toString().equals("config"))
                .findFirst()
                .orElse(injector);

        Path configDir = neighbour.getParent();
        String pkg = packageOf(read(neighbour));
        if (pkg == null) return false;

        Path target = configDir.resolve(supply.configClass() + ".java");
        if (Files.exists(target)) return false;

        String content = """
                package %s;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import %s;

                /**
                 * Spring Boot does not auto-configure %s — it is injected by the generated
                 * code but never declared, which compiles clean and then fails the context
                 * refresh at boot. Supplied here with a dependency-free default.
                 */
                @Configuration
                public class %s {

                %s}
                """.formatted(pkg, supply.fqcn(), simpleName, supply.configClass(), supply.beanMethod());

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            log.info("[MissingBeanPatcher] {} is injected by {} but never declared — "
                            + "created {} (boot failure prevented)",
                    simpleName, injector.getFileName(), supply.configClass());
            return true;
        } catch (IOException e) {
            log.warn("[MissingBeanPatcher] Could not write {}: {}", target, e.getMessage());
            return false;
        }
    }

    static String packageOf(String content) {
        if (content == null) return null;
        Matcher m = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String read(Path p) {
        if (p == null) return null;
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}
