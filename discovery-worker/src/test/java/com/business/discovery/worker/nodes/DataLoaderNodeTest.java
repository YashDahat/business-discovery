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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataLoaderNodeTest {

    @Mock private ArchitectBriefRepository briefRepo;
    @Mock private BusinessEntityRepository businessRepo;
    @Mock private ContainerTaskRepository taskRepo;
    @Mock private WorkerContext ctx;

    @InjectMocks
    private DataLoaderNode node;

    @Test
    void loadsAllEntitiesAndSetsOnContext() {
        UUID briefId = UUID.randomUUID();
        UUID businessId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(ctx.getBriefId()).thenReturn(briefId);
        when(ctx.getBusinessId()).thenReturn(businessId);
        when(ctx.getTaskId()).thenReturn(taskId);

        ArchitectBrief brief = ArchitectBrief.builder().id(briefId).businessCategory("Restaurant").build();
        BusinessEntity business = BusinessEntity.builder().id(businessId).title("Shree Cafe").build();
        ContainerTask task = ContainerTask.builder().id(taskId)
                .status(ContainerTask.ContainerTaskStatus.RUNNING).build();

        when(briefRepo.findById(briefId)).thenReturn(Optional.of(brief));
        when(businessRepo.findById(businessId)).thenReturn(Optional.of(business));
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));

        node.execute(ctx);

        verify(ctx).setBrief(brief);
        verify(ctx).setBusiness(business);
        verify(ctx).setTask(task);
    }

    @Test
    void missingBrief_throwsInfraException() {
        UUID briefId = UUID.randomUUID();
        when(ctx.getBriefId()).thenReturn(briefId);
        when(briefRepo.findById(briefId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }

    @Test
    void missingBusiness_throwsInfraException() {
        UUID briefId = UUID.randomUUID();
        UUID businessId = UUID.randomUUID();
        when(ctx.getBriefId()).thenReturn(briefId);
        when(ctx.getBusinessId()).thenReturn(businessId);
        when(briefRepo.findById(briefId))
                .thenReturn(Optional.of(ArchitectBrief.builder().id(briefId).build()));
        when(businessRepo.findById(businessId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }

    @Test
    void missingTask_throwsInfraException() {
        UUID briefId = UUID.randomUUID();
        UUID businessId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(ctx.getBriefId()).thenReturn(briefId);
        when(ctx.getBusinessId()).thenReturn(businessId);
        when(ctx.getTaskId()).thenReturn(taskId);
        when(briefRepo.findById(briefId))
                .thenReturn(Optional.of(ArchitectBrief.builder().id(briefId).build()));
        when(businessRepo.findById(businessId))
                .thenReturn(Optional.of(BusinessEntity.builder().id(businessId).title("Test").build()));
        when(taskRepo.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> node.execute(ctx))
                .isInstanceOf(WorkerException.class)
                .satisfies(ex -> assertThat(((WorkerException) ex).getFailureType())
                        .isEqualTo(FailureType.INFRA));
    }
}
