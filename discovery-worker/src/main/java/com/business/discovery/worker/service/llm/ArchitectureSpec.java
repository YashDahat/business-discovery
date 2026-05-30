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
public class ArchitectureSpec {
    private String generatedAt;
    private String businessName;
    private String basePackage;
    private ProjectDependencies projectDependencies;
    private List<FileSpec> files;
}
