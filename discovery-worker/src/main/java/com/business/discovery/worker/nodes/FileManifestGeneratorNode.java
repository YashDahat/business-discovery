package com.business.discovery.worker.nodes;

import com.business.discovery.worker.constants.FailureType;
import com.business.discovery.worker.context.WorkerContext;
import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.model.ArchitectBrief;
import com.business.discovery.worker.model.BusinessEntity;
import com.business.discovery.worker.model.ContainerTask;
import com.business.discovery.worker.repository.ArchitectBriefRepository;
import com.business.discovery.worker.repository.BusinessEntityRepository;
import com.business.discovery.worker.repository.ContainerTaskRepository;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileEntry;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Order(1)
@Slf4j
@RequiredArgsConstructor
public class FileManifestGeneratorNode implements WorkerNode {

    private final ArchitectBriefRepository briefRepo;
    private final BusinessEntityRepository businessRepo;
    private final ContainerTaskRepository taskRepo;
    private final LlmGeneratorService llm;

    @Override
    public void execute(WorkerContext ctx) {
        ArchitectBrief brief = briefRepo.findById(ctx.getBriefId())
                .orElseThrow(() -> new WorkerException(FailureType.INFRA,
                        "ArchitectBrief not found: " + ctx.getBriefId()));

        BusinessEntity business = businessRepo.findById(ctx.getBusinessId())
                .orElseThrow(() -> new WorkerException(FailureType.INFRA,
                        "BusinessEntity not found: " + ctx.getBusinessId()));

        ContainerTask task = taskRepo.findById(ctx.getTaskId())
                .orElseThrow(() -> new WorkerException(FailureType.INFRA,
                        "ContainerTask not found: " + ctx.getTaskId()));

        ctx.setBrief(brief);
        ctx.setBusiness(business);
        ctx.setTask(task);

        BriefContext briefCtx = buildBriefContext(brief, business);
        ctx.setBriefCtx(briefCtx);
        List<FileEntry> manifest = llm.generateFileManifest(briefCtx);
        ctx.setFileManifest(manifest);

        log.info("[FileManifestGeneratorNode] Generated manifest with {} files for '{}'",
                manifest.size(), business.getTitle());
    }

    private BriefContext buildBriefContext(ArchitectBrief brief, BusinessEntity business) {
        return new BriefContext(
                business.getTitle(),
                nullSafe(brief.getBusinessCategory(), business.getCategory()),
                nullSafe(brief.getLocation(), "India"),
                brief.getWebsiteType() != null ? brief.getWebsiteType().name() : "INFORMATIONAL",
                nullSafeList(brief.getMustHaveFeatures()),
                nullSafeList(brief.getNiceToHaveFeatures()),
                nullSafeMap(brief.getRecommendedTechStack()),
                nullSafeList(brief.getSeoKeywords()),
                nullSafe(brief.getDesignDirection(), "modern and professional"),
                nullSafe(brief.getColorScheme(), "blue and white"),
                nullSafe(brief.getTone(), "professional"),
                nullSafe(brief.getCompetitorInsights(), ""),
                nullSafe(brief.getIndustryInsights(), ""),
                nullSafe(brief.getArchitecturalNotes(), ""),
                brief.getRequestedChanges()
        );
    }

    private String nullSafe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private List<String> nullSafeList(List<String> list) {
        return list != null ? list : List.of();
    }

    private Map<String, String> nullSafeMap(Map<String, String> map) {
        return map != null ? map : Map.of(
                "frontend", "React 19 + TypeScript",
                "backend", "Spring Boot 3",
                "database", "PostgreSQL"
        );
    }
}
