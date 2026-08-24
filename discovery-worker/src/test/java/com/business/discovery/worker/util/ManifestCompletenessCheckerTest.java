package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement Point A detection. Fixtures reproduce the farmaaish-restaurant defect: AdminLayout is
 * named only in feature prose (not in files[], not on disk) → must be flagged; SiteLayout ships from
 * the foundation (on disk, not in files[]) → must NOT be flagged.
 */
class ManifestCompletenessCheckerTest {

    @TempDir Path workspace;

    private Path src() { return workspace.resolve("frontend/src"); }

    private void writeOnDisk(String rel, String content) throws IOException {
        Path p = src().resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private FileSpec page(String path, List<String> importsFrom, String description) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).fileType("FRONTEND")
                .importsFrom(importsFrom).description(description)
                .featureName("admin").build();
    }

    @Test
    void flagsProseOnlyRefButNotOnDiskScaffold() throws IOException {
        writeOnDisk("shell/SiteLayout.tsx", "export function SiteLayout() { return null; }");

        FeatureSpec admin = FeatureSpec.builder().featureName("admin")
                .featureInstruction("All admin pages are wrapped in the <AdminLayout> component from "
                        + "@/components/AdminLayout. Public pages use @/shell/SiteLayout instead.")
                .build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/AdminDashboardPage.tsx", List.of(), "Admin home.")))
                .features(List.of(admin))
                .build();

        List<ManifestCompletenessChecker.MissingRef> missing =
                ManifestCompletenessChecker.findMissingFrontend(spec, workspace);
        List<String> paths = missing.stream().map(ManifestCompletenessChecker.MissingRef::importPath).toList();

        assertThat(paths).contains("frontend/src/components/AdminLayout.tsx");   // prose-only → flagged
        assertThat(paths).doesNotContain("frontend/src/shell/SiteLayout.tsx");   // on disk → provided
    }

    @Test
    void flagsDanglingStructuredImport() {
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/AdminMenuPage.tsx",
                        List.of("frontend/src/components/admin/menu/MenuTable.tsx"), "Menu admin.")))
                .features(List.of())
                .build();

        List<String> paths = ManifestCompletenessChecker.findMissingFrontend(spec, workspace)
                .stream().map(ManifestCompletenessChecker.MissingRef::importPath).toList();

        assertThat(paths).contains("frontend/src/components/admin/menu/MenuTable.tsx");
    }

    @Test
    void doesNotFlagPlannedFileOrBareDirectoryProseRef() {
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(
                        page("frontend/src/pages/AdminMenuPage.tsx",
                                List.of("frontend/src/components/admin/menu/MenuTable.tsx"),
                                "Uses components from @/components/ui and @/hooks."),
                        // the referenced component IS planned → provided
                        page("frontend/src/components/admin/menu/MenuTable.tsx", List.of(), "table")))
                .features(List.of())
                .build();

        List<String> paths = ManifestCompletenessChecker.findMissingFrontend(spec, workspace)
                .stream().map(ManifestCompletenessChecker.MissingRef::importPath).toList();

        assertThat(paths).doesNotContain("frontend/src/components/admin/menu/MenuTable.tsx"); // planned
        assertThat(paths).noneMatch(p -> p.contains("/ui")); // bare-directory prose ref ignored
    }

    @Test
    void carriesReferencedByForContext() {
        FeatureSpec admin = FeatureSpec.builder().featureName("admin")
                .featureInstruction("pages wrapped in <AdminLayout> from @/components/AdminLayout")
                .build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/AdminOrdersPage.tsx", List.of(), "orders")))
                .features(List.of(admin))
                .build();

        ManifestCompletenessChecker.MissingRef mr =
                ManifestCompletenessChecker.findMissingFrontend(spec, workspace).stream()
                        .filter(m -> m.importPath().endsWith("AdminLayout.tsx")).findFirst().orElseThrow();

        assertThat(mr.referencedBy()).contains("feature:admin");
    }

    // ── hook-symbol (findDanglingHooks) ──────────────────────────────────────

    private FileSpec controller(String path, String... handlerNames) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).fileType("BACKEND")
                .publicFunctions(java.util.Arrays.stream(handlerNames)
                        .map(n -> com.business.discovery.worker.service.llm.PublicFunction.builder().name(n).build())
                        .toList())
                .featureName("classes").build();
    }

    private FileSpec componentWithRole(String path, String fileRole) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).fileType("FRONTEND").fileRole(fileRole)
                .description("component").featureName("classes").build();
    }

    @Test
    void flagsHookNamedInProseWithNoBackingService() {
        FeatureSpec f = FeatureSpec.builder().featureName("classes")
                .featureInstruction("The dashboard calls useAnalyticsSummary() to render KPIs.")
                .build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/AdminDashboardPage.tsx", List.of(), "home")))
                .features(List.of(f)).build();

        List<String> hooks = ManifestCompletenessChecker.findDanglingHooks(spec, workspace)
                .stream().map(ManifestCompletenessChecker.DanglingHook::hookName).toList();

        assertThat(hooks).contains("useAnalyticsSummary");
    }

    @Test
    void doesNotFlagServiceBackedHook() {
        // abs-fitness regression safety: ClassForm's role names useCreateGymClass/useUpdateGymClass;
        // the backend has createGymClass/updateGymClass handlers, so the mechanical generator will emit
        // those hooks → they must NOT be flagged.
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(
                        controller("backend/src/main/java/com/absfitness/controller/AdminClassController.java",
                                "createGymClass", "updateGymClass", "getAllGymClasses"),
                        componentWithRole("frontend/src/components/admin/ClassForm.tsx",
                                "COMPONENT — consumes the useCreateGymClass, useUpdateGymClass, and useGymClasses hooks.")))
                .features(List.of()).build();

        List<String> hooks = ManifestCompletenessChecker.findDanglingHooks(spec, workspace)
                .stream().map(ManifestCompletenessChecker.DanglingHook::hookName).toList();

        assertThat(hooks).doesNotContain("useCreateGymClass", "useUpdateGymClass", "useGymClasses");
    }

    @Test
    void doesNotFlagFoundationHookOnDisk() throws IOException {
        writeOnDisk("hooks/useAuth.ts", "export function useAuth() { return null; }");
        FeatureSpec f = FeatureSpec.builder().featureName("classes")
                .featureInstruction("The page reads the current user via useAuth().").build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/ProfilePage.tsx", List.of(), "profile")))
                .features(List.of(f)).build();

        List<String> hooks = ManifestCompletenessChecker.findDanglingHooks(spec, workspace)
                .stream().map(ManifestCompletenessChecker.DanglingHook::hookName).toList();

        assertThat(hooks).doesNotContain("useAuth");
    }

    @Test
    void doesNotFlagCompositeHookDeclaredAsPublicFunction() {
        FileSpec composite = FileSpec.builder()
                .fileName("useClassBooking.ts").filePath("frontend/src/hooks/useClassBooking.ts")
                .fileType("FRONTEND")
                .publicFunctions(List.of(com.business.discovery.worker.service.llm.PublicFunction.builder()
                        .name("useClassBooking").build()))
                .featureName("classes").build();
        FeatureSpec f = FeatureSpec.builder().featureName("classes")
                .featureInstruction("ClassCard uses useClassBooking(gymClassId) to book.").build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(composite,
                        page("frontend/src/components/classes/ClassCard.tsx", List.of(), "card")))
                .features(List.of(f)).build();

        List<String> hooks = ManifestCompletenessChecker.findDanglingHooks(spec, workspace)
                .stream().map(ManifestCompletenessChecker.DanglingHook::hookName).toList();

        assertThat(hooks).doesNotContain("useClassBooking");
    }

    @Test
    void carriesReferencedByForDanglingHook() {
        FeatureSpec f = FeatureSpec.builder().featureName("classes")
                .featureInstruction("Calls useGhostHook().").build();
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(page("frontend/src/pages/X.tsx", List.of(), "x")))
                .features(List.of(f)).build();

        ManifestCompletenessChecker.DanglingHook dh =
                ManifestCompletenessChecker.findDanglingHooks(spec, workspace).stream()
                        .filter(h -> h.hookName().equals("useGhostHook")).findFirst().orElseThrow();

        assertThat(dh.referencedBy()).contains("feature:classes");
    }

    private FileSpec backendFile(String path, List<String> importsFrom) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).fileType("BACKEND")
                .importsFrom(importsFrom).description("backend class")
                .featureName("orders").build();
    }

    @Test
    void flagsDanglingBackendImportButNotOnDiskScaffoldOrPlanned() throws IOException {
        // an on-disk foundation class (scaffold) — must never be flagged
        Path scaffold = workspace.resolve("backend/src/main/java/com/foundation/config/SecurityConfig.java");
        Files.createDirectories(scaffold.getParent());
        Files.writeString(scaffold, "package com.foundation.config; class SecurityConfig {}");

        String dangling = "backend/src/main/java/com/farmaaish/dto/OrderItemResponse.java";
        String planned  = "backend/src/main/java/com/farmaaish/dto/MenuItemDto.java";
        ArchitectureSpec spec = ArchitectureSpec.builder()
                .files(List.of(
                        backendFile("backend/src/main/java/com/farmaaish/dto/OrderResponse.java",
                                List.of(dangling, planned,
                                        "backend/src/main/java/com/foundation/config/SecurityConfig.java")),
                        backendFile(planned, List.of())))
                .features(List.of())
                .build();

        List<String> paths = ManifestCompletenessChecker.findMissingBackend(spec, workspace)
                .stream().map(ManifestCompletenessChecker.MissingRef::importPath).toList();

        assertThat(paths).contains(dangling);                              // dangling imports_from → flagged
        assertThat(paths).doesNotContain(planned);                         // planned → provided
        assertThat(paths).noneMatch(p -> p.contains("SecurityConfig"));    // on-disk scaffold → provided
    }
}
