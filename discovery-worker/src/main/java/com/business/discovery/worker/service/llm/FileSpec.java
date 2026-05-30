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
public class FileSpec {
    private String fileName;
    private String filePath;
    private String fileType;
    private String layer;
    private String createdDate;
    private String updatedDate;
    private String status;
    private String description;
    private List<PublicFunction> publicFunctions;
    private List<PublicVariable> publicVariables;
    private List<ApiEndpoint> apiEndpoints;
    private List<ApiEndpoint> apiEndpointsConsumed;
    private List<String> importsFrom;
    private List<String> dependsOn;
}
