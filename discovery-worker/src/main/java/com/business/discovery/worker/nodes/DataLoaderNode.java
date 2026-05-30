
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@Slf4j
@RequiredArgsConstructor
public class DataLoaderNode implements WorkerNode {

    private final ArchitectBriefRepository briefRepo;
    private final BusinessEntityRepository businessRepo;
    private final ContainerTaskRepository taskRepo;

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

        log.info("[DataLoaderNode] Loaded brief={} business='{}' task={}",
                brief.getId(), business.getTitle(), task.getId());
    }
}
