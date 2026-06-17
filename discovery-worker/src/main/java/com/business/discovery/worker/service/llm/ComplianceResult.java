package com.business.discovery.worker.service.llm;

import java.util.List;

public record ComplianceResult(boolean compliant, List<String> issues) {

    public static ComplianceResult ok() {
        return new ComplianceResult(true, List.of());
    }

    public static ComplianceResult drift(List<String> issues) {
        return new ComplianceResult(false, issues);
    }
}
