package com.business.discovery.worker.nodes;

import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectScaffoldNodeTest {

    private final ProjectScaffoldNode node = new ProjectScaffoldNode();

    @TempDir
    Path tempDir;

    @Test
    void scaffold_createsExpectedDirectories() {
        WorkerContext ctx = buildContext("Shree Restaurant", Map.of("frontend", "React 19", "backend", "Spring Boot"));

        node.execute(ctx);

        assertThat(tempDir.resolve("backend/src/main/java/com/shreerestaurant/controller")).exists().isDirectory();
        assertThat(tempDir.resolve("backend/src/main/java/com/shreerestaurant/model")).exists().isDirectory();
        assertThat(tempDir.resolve("backend/src/main/java/com/shreerestaurant/repository")).exists().isDirectory();
        assertThat(tempDir.resolve("backend/src/main/java/com/shreerestaurant/service")).exists().isDirectory();
        assertThat(tempDir.resolve("backend/src/main/resources/static")).exists().isDirectory();
        assertThat(tempDir.resolve("frontend/src")).exists().isDirectory();
        assertThat(tempDir.resolve("frontend/public")).exists().isDirectory();
    }

    @Test
    void scaffold_writesPomWithCorrectGroupId() throws IOException {
        WorkerContext ctx = buildContext("My Cafe", null);

        node.execute(ctx);

        String pom = Files.readString(tempDir.resolve("backend/pom.xml"));
        assertThat(pom).contains("<groupId>com.mycafe</groupId>");
        assertThat(pom).contains("<artifactId>mycafe-backend</artifactId>");
        assertThat(pom).contains("My Cafe");
        assertThat(pom).contains("spring-boot-starter-web");
        assertThat(pom).contains("spring-boot-starter-data-jpa");
    }

    @Test
    void scaffold_writesDockerComposeWithSlug() throws IOException {
        WorkerContext ctx = buildContext("Pizza Palace", Map.of("frontend", "React 19"));

        node.execute(ctx);

        String compose = Files.readString(tempDir.resolve("docker-compose.yml"));
        assertThat(compose).contains("pizzapalace");
        assertThat(compose).contains("postgres:16-alpine");
        assertThat(compose).contains("8080:8080");
    }

    @Test
    void scaffold_writesDockerfile() throws IOException {
        WorkerContext ctx = buildContext("Test Business", null);

        node.execute(ctx);

        String dockerfile = Files.readString(tempDir.resolve("Dockerfile"));
        assertThat(dockerfile).contains("node:20-alpine");
        assertThat(dockerfile).contains("maven:3.9-eclipse-temurin-17");
        assertThat(dockerfile).contains("eclipse-temurin:17-jre-alpine");
        assertThat(dockerfile).contains("EXPOSE 8080");
    }

    @Test
    void scaffold_writesFrontendPackageJson() throws IOException {
        WorkerContext ctx = buildContext("Food Corner", null);

        node.execute(ctx);

        String pkgJson = Files.readString(tempDir.resolve("frontend/package.json"));
        assertThat(pkgJson).contains("foodcorner-frontend");
        assertThat(pkgJson).contains("react");
        assertThat(pkgJson).contains("vite");
    }

    @Test
    void toSlug_stripsSpecialCharsAndSpaces() {
        assertThat(ProjectScaffoldNode.toSlug("Shree Restaurant")).isEqualTo("shreerestaurant");
        assertThat(ProjectScaffoldNode.toSlug("Pizza & Co.")).isEqualTo("pizzaco");
        assertThat(ProjectScaffoldNode.toSlug("123 ABC Shop")).isEqualTo("abcshop");
        assertThat(ProjectScaffoldNode.toSlug(null)).isEqualTo("business");
    }

    private WorkerContext buildContext(String businessName, Map<String, String> techStack) {
        WorkerContext ctx = mock(WorkerContext.class);
        BusinessEntity business = BusinessEntity.builder().title(businessName).build();
        ArchitectBrief brief = ArchitectBrief.builder().recommendedTechStack(techStack).build();
        when(ctx.getBusiness()).thenReturn(business);
        when(ctx.getBrief()).thenReturn(brief);
        when(ctx.getWorkspaceDir()).thenReturn(tempDir);
        return ctx;
    }
}
