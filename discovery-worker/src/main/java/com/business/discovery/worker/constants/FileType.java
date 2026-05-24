package com.business.discovery.worker.constants;

public enum FileType {
    BACKEND,   // Java Spring Boot: entities, repos, services, controllers, SQL
    FRONTEND,  // React / TypeScript components, pages, hooks
    INFRA,     // Dockerfile, docker-compose, GitHub Actions CI
    CONFIG     // pom.xml, package.json, application.properties
}
