package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileContract;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.WorkspaceReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batched arch-spec behavior: the outline call's in-process retry loop and the
 * enrichment call's per-file detail merge (outline owns the file list).
 */
class LlmGeneratorServiceBatchedSpecTest {

    /** Fake LLM: scripted responses, call counting. */
    static class FakeLlm extends LlmGeneratorService {
        final Deque<String> responses = new ArrayDeque<>();
        int singleTurnCalls = 0;
        int toolCalls = 0;

        @Override
        protected String doCallLlm(String systemPrompt, String userPrompt) {
            singleTurnCalls++;
            return responses.poll();
        }

        @Override
        protected String callLlmWithTools(String systemPrompt, String userPrompt, WorkspaceReader reader) {
            toolCalls++;
            return responses.poll();
        }
    }

    private static BriefContext brief() {
        return new BriefContext("MultiFit Aundh", "Gym", "Pune", null,
                List.of("Class booking"), List.of(), List.of(),
                Map.of("frontend", "React 19"), List.of(),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static final String VALID_OUTLINE = """
            {"generated_at":"2026-07-06T00:00:00Z","business_name":"MultiFit Aundh",
             "base_package":"com.multifit",
             "files":[{"file_name":"Trainer.java","file_path":"backend/src/main/java/com/multifit/model/Trainer.java",
                       "file_type":"BACKEND","layer":"MODEL","status":"PLANNED",
                       "description":"JPA entity for trainers","feature_name":"trainer-management"}],
             "features":[{"feature_name":"trainer-management","feature_display_name":"Trainer Management",
                          "feature_type":"BACKEND",
                          "file_paths":["backend/src/main/java/com/multifit/model/Trainer.java"]}]}""";

    // ── Outline retry loop ────────────────────────────────────────────────────

    @Test
    void outline_retriesEmptyStubThenGarbageThenSucceeds() {
        FakeLlm llm = new FakeLlm();
        // attempt 1: the observed degenerate stub; attempt 2: truncated JSON; attempt 3: valid
        llm.responses.add("{\"generated_at\":\"x\",\"business_name\":\"M\",\"files\":[],\"features\":[]}");
        llm.responses.add("{\"generated_at\":\"x\",\"files\":[{\"file_name\":\"Trun");
        llm.responses.add(VALID_OUTLINE);

        ArchitectureSpec spec = llm.generateArchitectureSpec(brief(), "multifit");

        assertThat(llm.singleTurnCalls).isEqualTo(3);
        assertThat(spec.getFiles()).hasSize(1);
        assertThat(spec.getFeatures()).hasSize(1);
    }

    @Test
    void outline_throwsAfterThreeFailedAttempts() {
        FakeLlm llm = new FakeLlm();
        for (int i = 0; i < 3; i++) {
            llm.responses.add("{\"files\":[],\"features\":[]}");
        }

        assertThatThrownBy(() -> llm.generateArchitectureSpec(brief(), "multifit"))
                .isInstanceOf(WorkerException.class)
                .hasMessageContaining("after 3 attempts");
        assertThat(llm.singleTurnCalls).isEqualTo(3);
    }

    // ── Enrichment detail merge ───────────────────────────────────────────────

    private static FeatureSpec feature(String... paths) {
        return FeatureSpec.builder()
                .featureName("trainer-management")
                .featureDisplayName("Trainer Management")
                .featureType("BACKEND")
                .filePaths(List.of(paths))
                .build();
    }

    private static FileSpec lightFile(String path) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path)
                .fileType("BACKEND").layer("CONTROLLER").status("PLANNED")
                .description("outline-level description")
                .build();
    }

    private static final String INSTRUCTION =
            "Implement the trainer management vertical slice: TrainerController exposes GET /api/v1/trainers "
            + "returning List<TrainerDto> via TrainerService.getAllTrainers(); persistence via TrainerRepository.";

    @Test
    void enrichFeature_mergesDetailByPath_andDropsUnknownPaths() {
        String ctrl = "backend/src/main/java/com/multifit/controller/TrainerController.java";
        FileSpec file = lightFile(ctrl);
        FakeLlm llm = new FakeLlm();
        llm.responses.add("""
                {"feature_instruction":"%s",
                 "files":[
                   {"file_path":"%s",
                    "file_role":"CONTROLLER — exposes getAllTrainers(): List<TrainerDto>",
                    "description":"REST controller for trainer endpoints",
                    "public_functions":[{"name":"getAllTrainers","parameters":[],"return_type":"ResponseEntity<List<TrainerDto>>","description":"lists trainers"}],
                    "api_endpoints":[{"method":"GET","path":"/api/v1/trainers","request_body":null,"response_body":"List<TrainerDto>","description":"list"}]},
                   {"file_path":"backend/src/main/java/com/multifit/model/Phantom.java",
                    "file_role":"should be dropped — not in the outline"}
                 ]}""".formatted(INSTRUCTION, ctrl));

        FeatureSpec result = llm.enrichFeature(feature(ctrl), List.of(file), Map.of(), brief(), null);

        assertThat(result.getFeatureInstruction()).contains("TrainerController");
        assertThat(file.getFileRole()).contains("getAllTrainers");
        assertThat(file.getDescription()).isEqualTo("REST controller for trainer endpoints");
        assertThat(file.getPublicFunctions()).hasSize(1);
        assertThat(file.getApiEndpoints()).hasSize(1);
        assertThat(file.getApiEndpoints().get(0).getPath()).isEqualTo("/api/v1/trainers");
    }

    @Test
    void enrichFeature_missingFilesArray_degradesToInstructionOnly() {
        String ctrl = "backend/src/main/java/com/multifit/controller/TrainerController.java";
        FileSpec file = lightFile(ctrl);
        FakeLlm llm = new FakeLlm();
        llm.responses.add("{\"feature_instruction\":\"" + INSTRUCTION + "\"}");

        FeatureSpec result = llm.enrichFeature(feature(ctrl), List.of(file), Map.of(), brief(), null);

        assertThat(result.getFeatureInstruction()).isNotBlank();
        assertThat(file.getFileRole()).isNull();                       // untouched
        assertThat(file.getDescription()).isEqualTo("outline-level description");
    }

    // ── Contract reconciliation ───────────────────────────────────────────────

    @Test
    void reconcileContracts_parsesMembersAndMethods() {
        FakeLlm llm = new FakeLlm();
        llm.responses.add("""
                {"files":[
                  {"module":"backend/src/main/java/com/x/service/SubService.java","members":[],
                   "methods":["createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto"]},
                  {"module":"frontend/src/components/classes/ClassSchedule.tsx",
                   "members":[{"name":"classes","type":"FitnessClassDto[]"},
                              {"name":"onSelectClass","type":"(c: FitnessClassDto) => void"}],
                   "methods":[]}
                ]}""");

        List<FileContract> out = llm.reconcileContracts("mixed", "instruction", "[]", "", "");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).methods()).extracting(FileContract.Method::signature)
                .containsExactly("createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto");
        assertThat(out.get(0).members()).isEmpty();
        assertThat(out.get(1).members()).extracting(FileContract.Member::name)
                .containsExactly("classes", "onSelectClass");
        assertThat(out.get(1).members().get(1).type()).isEqualTo("(c: FitnessClassDto) => void");
    }

    @Test
    void reconcileContracts_retriesThenThrowsOnGarbage() {
        FakeLlm llm = new FakeLlm();
        llm.responses.add("not json");
        llm.responses.add("{\"files\":\"not-an-array\"}");

        assertThatThrownBy(() -> llm.reconcileContracts("mixed", "x", "[]", "", ""))
                .isInstanceOf(WorkerException.class);
        assertThat(llm.singleTurnCalls).isEqualTo(2);           // both attempts consumed
    }

    // ── Peer-summary fallback for not-yet-detailed features ──────────────────

    @Test
    void buildApiSummary_includesDtoFieldShapes() {
        String dtoPath = "backend/src/main/java/com/multifit/dto/MembershipDto.java";
        FileSpec dto = FileSpec.builder()
                .fileName("MembershipDto.java").filePath(dtoPath)
                .fileType("BACKEND").layer("DTO").status("PLANNED")
                .publicVariables(List.of(
                        new com.business.discovery.worker.service.llm.PublicVariable(
                                "durationInMonths", "Integer", "plan length"),
                        new com.business.discovery.worker.service.llm.PublicVariable(
                                "price", "BigDecimal", "monthly price")))
                .build();

        Map<String, Object> summary = LlmGeneratorService.buildApiSummary(feature(dtoPath), List.of(dto));

        @SuppressWarnings("unchecked")
        Map<String, String> shapes = (Map<String, String>) summary.get("data_shapes");
        assertThat(shapes).isNotNull();
        assertThat(shapes.get("MembershipDto"))
                .contains("durationInMonths: Integer")
                .contains("price: BigDecimal");
    }

    @Test
    void buildApiSummary_fallsBackToRolesWhenNoHeavyFields() {
        String ctrl = "backend/src/main/java/com/multifit/controller/TrainerController.java";
        FileSpec light = lightFile(ctrl);

        Map<String, Object> summary = LlmGeneratorService.buildApiSummary(feature(ctrl), List.of(light));

        assertThat(summary).containsKey("note");
        assertThat(summary).containsKey("file_roles");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) summary.get("file_roles");
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).get("role")).isEqualTo("outline-level description");
    }
}
