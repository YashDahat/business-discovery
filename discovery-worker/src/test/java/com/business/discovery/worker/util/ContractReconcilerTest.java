package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileContract;
import com.business.discovery.worker.service.llm.FileContract.Member;
import com.business.discovery.worker.service.llm.FileContract.Method;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.generator.LlmGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContractReconcilerTest {

    @Mock private LlmGeneratorService proLlm;

    private FileSpec file(String path, String layer, List<String> params) {
        FileSpec.FileSpecBuilder b = FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).layer(layer)
                .fileType(path.startsWith("frontend/") ? "FRONTEND" : "BACKEND")
                .fileRole("Original role for " + path);
        if (params != null) {
            b.publicFunctions(List.of(com.business.discovery.worker.service.llm.PublicFunction.builder()
                    .name(path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.\\w+$", ""))
                    .parameters(params).build()));
        }
        return b.build();
    }

    private ArchitectureSpec spec(List<FileSpec> files, FeatureSpec... features) {
        ArchitectureSpec s = new ArchitectureSpec();
        s.setFiles(new java.util.ArrayList<>(files));
        s.setFeatures(new java.util.ArrayList<>(List.of(features)));
        return s;
    }

    private FeatureSpec feature(String name, String type, String... paths) {
        return FeatureSpec.builder().featureName(name).featureType(type)
                .featureInstruction("Feature " + name).filePaths(List.of(paths)).build();
    }

    @Test
    void writesReconciledContractIntoFileRole_andSyncsStructuredFields_backend() {
        String svc  = "backend/src/main/java/com/x/service/MemberSubscriptionService.java";
        String dto  = "backend/src/main/java/com/x/dto/MemberSubscriptionDto.java";
        FileSpec service = file(svc, "SERVICE", List.of("wrongSignature()"));
        FileSpec dtoFile = file(dto, "DTO", null);

        ArchitectureSpec spec = spec(List.of(service, dtoFile),
                feature("membership", "BACKEND", svc, dto));

        when(proLlm.reconcileContracts(eq("membership"), any(), any(), any(), any())).thenReturn(List.of(
                new FileContract(svc, List.of(), List.of(
                        new Method("createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto"))),
                new FileContract(dto, List.of(
                        new Member("id", "UUID"),
                        new Member("status", "SubscriptionStatus")), List.of())));

        int updated = ContractReconciler.reconcile(spec, proLlm);

        assertThat(updated).isEqualTo(2);
        // fileRole carries the ground-truth interface for BOTH files (the field both generators read)
        assertThat(service.getFileRole())
                .contains("Original role")
                .contains("createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto");
        assertThat(dtoFile.getFileRole()).contains("{ id: UUID; status: SubscriptionStatus }");
        // structured sync: DTO fields → public_variables, service method → public_functions
        assertThat(dtoFile.getPublicVariables()).extracting(v -> v.getName()).containsExactly("id", "status");
        assertThat(service.getPublicFunctions().get(0).getName()).isEqualTo("createSubscription");
    }

    @Test
    void capturesPlannedToReconciledRecords_forContractsDoc() {
        String dto = "backend/src/main/java/com/x/dto/OrderDto.java";
        String svc = "backend/src/main/java/com/x/service/OrderService.java";
        FileSpec dtoFile = file(dto, "DTO", null);
        dtoFile.setPublicVariables(List.of(new com.business.discovery.worker.service.llm.PublicVariable(
                "userId", "UUID", null)));   // planned interface = UUID
        FileSpec svcFile = file(svc, "SERVICE", List.of("old()"));
        ArchitectureSpec spec = spec(List.of(dtoFile, svcFile), feature("orders", "BACKEND", dto, svc));

        when(proLlm.reconcileContracts(any(), any(), any(), any(), any())).thenReturn(List.of(
                new FileContract(dto, List.of(new Member("userId", "Integer")), List.of()),   // reconciled = Integer
                new FileContract(svc, List.of(), List.of(new Method("createOrder(): OrderDto")))));

        java.util.List<com.business.discovery.worker.service.llm.ContractRecord> records =
                new java.util.ArrayList<>();
        int updated = ContractReconciler.reconcile(spec, proLlm, "", "", records);

        assertThat(updated).isEqualTo(2);
        assertThat(records).hasSize(2);
        var r = records.stream().filter(x -> x.getModule().equals(dto)).findFirst().orElseThrow();
        assertThat(r.getFeatureName()).isEqualTo("orders");
        assertThat(r.getPlannedInterface()).contains("userId: UUID");
        assertThat(r.getReconciledInterface()).contains("userId: Integer");
    }

    @Test
    void rewritesComponentPropsAndClearsOpaque_frontend() {
        String child  = "frontend/src/components/classes/ClassSchedule.tsx";
        String parent = "frontend/src/pages/ClassesPage.tsx";
        FileSpec childF  = file(child,  "COMPONENT", List.of("ClassScheduleProps props")); // opaque
        FileSpec parentF = file(parent, "PAGE", List.of());

        ArchitectureSpec spec = spec(List.of(childF, parentF),
                feature("class-ui", "FRONTEND", child, parent));

        when(proLlm.reconcileContracts(any(), any(), any(), any(), any())).thenReturn(List.of(
                new FileContract(child, List.of(
                        new Member("classes", "FitnessClassDto[]"),
                        new Member("onSelectClass", "(c: FitnessClassDto) => void")), List.of())));

        int updated = ContractReconciler.reconcile(spec, proLlm);

        assertThat(updated).isEqualTo(1);
        assertThat(childF.getPublicFunctions().get(0).getParameters())
                .containsExactly("classes: FitnessClassDto[]", "onSelectClass: (c: FitnessClassDto) => void");
        // props flow into the card as concrete fields — no longer opaque
        assertThat(PlannedComponentPropsCard.build(spec.getFiles()).componentsWithOpaqueProps()).isEmpty();
    }

    @Test
    void reconciledContractInFileRoleIsIdempotentAcrossReruns() {
        String dto = "backend/src/main/java/com/x/dto/PlanDto.java";
        FileSpec dtoFile = file(dto, "DTO", null);
        FileSpec other  = file("backend/src/main/java/com/x/dto/OtherDto.java", "DTO", null);
        ArchitectureSpec spec = spec(List.of(dtoFile, other),
                feature("m", "BACKEND", dto, "backend/src/main/java/com/x/dto/OtherDto.java"));

        when(proLlm.reconcileContracts(any(), any(), any(), any(), any())).thenReturn(List.of(
                new FileContract(dto, List.of(new Member("durationMonths", "Integer")), List.of())));

        ContractReconciler.reconcile(spec, proLlm);
        ContractReconciler.reconcile(spec, proLlm);   // rerun (checkpointed retry)

        // exactly one reconciled block, not two stacked
        String role = dtoFile.getFileRole();
        assertThat(role.split("RECONCILED CONTRACT", -1)).hasSize(2);   // one occurrence → split yields 2 parts
        assertThat(role).contains("{ durationMonths: Integer }");
    }

    @Test
    void skipsFeaturesWithFewerThanTwoReconcilableFiles() {
        String only = "backend/src/main/java/com/x/dto/PlanDto.java";
        ArchitectureSpec spec = spec(List.of(file(only, "DTO", null)), feature("m", "BACKEND", only));

        assertThat(ContractReconciler.reconcile(spec, proLlm)).isZero();
        verifyNoInteractions(proLlm);
    }

    @Test
    void keepsPlannedInterfacesWhenReconcileThrows() {
        String a = "backend/src/main/java/com/x/dto/A.java";
        String b = "backend/src/main/java/com/x/dto/B.java";
        FileSpec fa = file(a, "DTO", null);
        String originalRole = fa.getFileRole();
        ArchitectureSpec spec = spec(List.of(fa, file(b, "DTO", null)), feature("m", "BACKEND", a, b));

        when(proLlm.reconcileContracts(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM down"));

        assertThat(ContractReconciler.reconcile(spec, proLlm)).isZero();
        assertThat(fa.getFileRole()).isEqualTo(originalRole);   // untouched on failure
    }

    @Test
    void skipsInfraFeatures() {
        String f = "infra/Dockerfile";
        ArchitectureSpec spec = spec(
                List.of(FileSpec.builder().filePath(f).fileType("INFRA").layer("INFRA").build()),
                feature("infra", "INFRA", f));
        assertThat(ContractReconciler.reconcile(spec, proLlm)).isZero();
        verifyNoInteractions(proLlm);
    }
}
