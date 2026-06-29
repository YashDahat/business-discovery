package com.business.discovery.worker.service.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectDependencies {
    /** Spring Initializr starter IDs — e.g. web, data-jpa, postgresql, security, mail */
    private List<String> springBootStarters;
    /** npm package names installed after Vite scaffold — e.g. @tanstack/react-query, zod */
    private List<String> npmPackages;
    /**
     * Explicit Maven dependencies for third-party libraries not available as Spring Initializr
     * starters (e.g. Razorpay, Cloudinary, Twilio). Declared by the planning LLM in the spec.
     * Injected into pom.xml by ProjectPlanningNode before any file generation begins.
     */
    private List<MavenCoordinate> mavenDependencies;
}
