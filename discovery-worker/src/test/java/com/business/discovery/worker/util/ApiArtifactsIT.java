package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline integration: extract + derive against a REAL generated project on the dev host.
 * Run explicitly:
 *   ./mvnw -f discovery-worker/pom.xml test -Dtest=ApiArtifactsIT \
 *       -Dapi.it=true -Dapi.it.workspace=/path/to/generated-project-clone
 */
class ApiArtifactsIT {

    @Test
    @EnabledIfSystemProperty(named = "api.it", matches = "true")
    void derivesTypesAndSdkFromRealBackend() throws Exception {
        Path workspace = Path.of(System.getProperty("api.it.workspace"));
        Path backendSrc = workspace.resolve("backend/src/main/java");
        assertThat(Files.exists(backendSrc)).isTrue();

        ApiInventory inv = ApiInventory.extract(backendSrc);
        System.out.println("=== ENDPOINTS (" + inv.endpoints().size() + ") ===");
        inv.endpoints().forEach(e -> System.out.println("  " + e.httpMethod() + " " + e.path()
                + "  req=" + e.requestType() + " res=" + e.responseType()
                + (e.responseIsList() ? "[]" : "")));
        System.out.println("=== TYPES (" + inv.types().size() + ") ===");
        inv.types().forEach((n, d) -> System.out.println("  " + n
                + (d.isEnum() ? " enum" + d.enumConstants() : " fields=" + d.fields().size())));

        assertThat(inv.endpoints()).isNotEmpty();
        assertThat(inv.types()).isNotEmpty();

        // Derive against the planned paths that exist in the real frontend
        List<String> typePaths = list(workspace, "frontend/src/types");
        List<String> servicePaths = list(workspace, "frontend/src/services");

        TsTypeGenerator.Result types = TsTypeGenerator.generate(inv, typePaths);
        Map<String, String> services = TsSdkGenerator.generate(inv, types.typeToPath(), servicePaths);

        System.out.println("=== DERIVED TYPE FILES ===");
        types.files().forEach((p, c) -> {
            System.out.println("--- " + p + " ---");
            System.out.println(c);
        });
        System.out.println("=== DERIVED SERVICE FILES ===");
        services.forEach((p, c) -> {
            System.out.println("--- " + p + " ---");
            System.out.println(c);
        });

        // The invented-field class: no derived type may contain a field the backend lacks.
        // multifit's MembershipPlan famously lacks 'features' — assert it can't appear.
        types.files().values().forEach(content ->
                assertThat(content).doesNotContain("features"));
    }

    private static List<String> list(Path workspace, String dir) throws Exception {
        Path d = workspace.resolve(dir);
        if (!Files.exists(d)) return List.of();
        try (var s = Files.list(d)) {
            return s.filter(p -> p.toString().endsWith(".ts"))
                    .map(p -> workspace.relativize(p).toString().replace('\\', '/'))
                    .toList();
        }
    }
}
