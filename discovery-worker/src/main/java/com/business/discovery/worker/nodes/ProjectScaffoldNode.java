package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
@Order(2)
@Slf4j
public class ProjectScaffoldNode implements WorkerNode {

    @Override
    public void execute(WorkerContext ctx) {
        BusinessEntity business = ctx.getBusiness();
        ArchitectBrief brief = ctx.getBrief();

        String slug = toSlug(business.getTitle());
        Map<String, String> techStack = brief.getRecommendedTechStack() != null
                ? brief.getRecommendedTechStack()
                : Map.of("frontend", "React 19", "backend", "Spring Boot 3", "database", "PostgreSQL");

        Path workspace = ctx.getWorkspaceDir();

        try {
            createDirectories(workspace, slug);
            writeBackendPom(workspace, slug, business.getTitle());
            writeFrontendPackageJson(workspace, slug, business.getTitle());
            writeAppProperties(workspace, slug);
            writeDockerfile(workspace);
            writeDockerCompose(workspace, slug);
            log.info("[ProjectScaffoldNode] Scaffold created for '{}' at {}", business.getTitle(), workspace);
        } catch (IOException e) {
            throw new WorkerException(FailureType.INFRA,
                    "Failed to scaffold project: " + e.getMessage(), e);
        }
    }

    private void createDirectories(Path workspace, String slug) throws IOException {
        Files.createDirectories(workspace.resolve("backend/src/main/java/com/" + slug + "/controller"));
        Files.createDirectories(workspace.resolve("backend/src/main/java/com/" + slug + "/model"));
        Files.createDirectories(workspace.resolve("backend/src/main/java/com/" + slug + "/repository"));
        Files.createDirectories(workspace.resolve("backend/src/main/java/com/" + slug + "/service"));
        Files.createDirectories(workspace.resolve("backend/src/main/resources/static"));
        Files.createDirectories(workspace.resolve("frontend/src"));
        Files.createDirectories(workspace.resolve("frontend/public"));
    }

    private void writeBackendPom(Path workspace, String slug, String businessName) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.%s</groupId>
                    <artifactId>%s-backend</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <name>%s Backend</name>
                    <properties>
                        <java.version>17</java.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <optional>true</optional>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>com.fasterxml.jackson.datatype</groupId>
                            <artifactId>jackson-datatype-jsr310</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <configuration>
                                    <excludes>
                                        <exclude>
                                            <groupId>org.projectlombok</groupId>
                                            <artifactId>lombok</artifactId>
                                        </exclude>
                                    </excludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(slug, slug, businessName);
        Files.writeString(workspace.resolve("backend/pom.xml"), content);
    }

    private void writeFrontendPackageJson(Path workspace, String slug, String businessName) throws IOException {
        String content = """
                {
                  "name": "%s-frontend",
                  "version": "0.0.1",
                  "private": true,
                  "type": "module",
                  "scripts": {
                    "dev": "vite",
                    "build": "tsc && vite build",
                    "preview": "vite preview"
                  },
                  "dependencies": {
                    "react": "^19.0.0",
                    "react-dom": "^19.0.0",
                    "react-router-dom": "^6.22.0",
                    "axios": "^1.6.0"
                  },
                  "devDependencies": {
                    "@types/react": "^19.0.0",
                    "@types/react-dom": "^19.0.0",
                    "@vitejs/plugin-react": "^4.2.0",
                    "typescript": "^5.3.0",
                    "vite": "^5.1.0"
                  }
                }
                """.formatted(slug);
        Files.writeString(workspace.resolve("frontend/package.json"), content);
    }

    private void writeAppProperties(Path workspace, String slug) throws IOException {
        String content = """
                spring.application.name=%s
                server.port=8080
                spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/%s}
                spring.datasource.username=${DB_USERNAME:postgres}
                spring.datasource.password=${DB_PASSWORD:}
                spring.jpa.hibernate.ddl-auto=update
                spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect
                spring.mvc.static-path-pattern=/static/**
                spring.web.resources.static-locations=classpath:/static/
                """.formatted(slug, slug);
        Files.writeString(workspace.resolve("backend/src/main/resources/application.properties"), content);
    }

    private void writeDockerfile(Path workspace) throws IOException {
        String content = """
                # Stage 1: Build React frontend
                FROM node:20-alpine AS frontend-build
                WORKDIR /app/frontend
                COPY frontend/package*.json ./
                RUN npm install
                COPY frontend/ .
                RUN npm run build

                # Stage 2: Build Spring Boot backend (with embedded frontend)
                FROM maven:3.9-eclipse-temurin-17 AS backend-build
                WORKDIR /app
                COPY backend/ .
                COPY --from=frontend-build /app/frontend/dist ./src/main/resources/static
                RUN mvn package -q -DskipTests

                # Stage 3: Run
                FROM eclipse-temurin:17-jre-alpine
                WORKDIR /app
                COPY --from=backend-build /app/target/*.jar app.jar
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """;
        Files.writeString(workspace.resolve("Dockerfile"), content);
    }

    private void writeDockerCompose(Path workspace, String slug) throws IOException {
        String content = """
                version: "3.9"
                services:
                  app:
                    build: .
                    ports:
                      - "8080:8080"
                    environment:
                      - DB_URL=jdbc:postgresql://db:5432/%s
                      - DB_USERNAME=postgres
                      - DB_PASSWORD=password
                    depends_on:
                      - db
                  db:
                    image: postgres:16-alpine
                    environment:
                      POSTGRES_DB: %s
                      POSTGRES_USER: postgres
                      POSTGRES_PASSWORD: password
                    volumes:
                      - pgdata:/var/lib/postgresql/data
                volumes:
                  pgdata:
                """.formatted(slug, slug);
        Files.writeString(workspace.resolve("docker-compose.yml"), content);
    }

    static String toSlug(String businessName) {
        if (businessName == null) return "business";
        return businessName.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("^[0-9]+", "");
    }
}
