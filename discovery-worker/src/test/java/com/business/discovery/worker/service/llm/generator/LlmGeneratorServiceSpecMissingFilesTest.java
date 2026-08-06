package com.business.discovery.worker.service.llm.generator;

import com.business.discovery.worker.service.llm.BriefContext;
import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.util.WorkspaceReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 4 parsing: specifyMissingFiles must turn a well-formed spec response into FileSpecs (robust to
 * public_functions being free-form strings), and degrade to an empty list on junk so the caller stubs.
 */
class LlmGeneratorServiceSpecMissingFilesTest {

    static class FakeLlm extends LlmGeneratorService {
        final Deque<String> responses = new ArrayDeque<>();
        int calls = 0;
        @Override protected String doCallLlm(String systemPrompt, String userPrompt) { calls++; return responses.poll(); }
        @Override protected String callLlmWithTools(String s, String u, WorkspaceReader r) { return responses.poll(); }
    }

    private static BriefContext brief() {
        return new BriefContext("Farmaaish Restaurant", "Restaurant", "Pune", null,
                List.of(), List.of(), List.of(), Map.of(), List.of(),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static final List<String> MISSING = List.of("frontend/src/components/AdminLayout.tsx");

    @Test
    void parsesFilesArrayIntoFileSpecs() {
        FakeLlm llm = new FakeLlm();
        // public_functions is intentionally a string array — must not break parsing.
        llm.responses.add("""
                {"files":[
                  {"file_name":"AdminLayout.tsx",
                   "file_path":"frontend/src/components/AdminLayout.tsx",
                   "file_type":"FRONTEND","layer":"COMPONENT","feature_name":"admin",
                   "description":"Admin shell: sidebar nav, wraps {children}, mirrors SiteLayout.",
                   "public_functions":["default export AdminLayout({ children })"],
                   "imports_from":["react-router-dom"],
                   "status":"PLANNED"}
                ]}""");

        List<FileSpec> out = llm.specifyMissingFiles(MISSING, "ctx", "exemplar", brief());

        assertThat(out).hasSize(1);
        FileSpec fs = out.get(0);
        assertThat(fs.getFilePath()).isEqualTo("frontend/src/components/AdminLayout.tsx");
        assertThat(fs.getFileType()).isEqualTo("FRONTEND");
        assertThat(fs.getStatus()).isEqualTo("PLANNED");
        assertThat(fs.getLayer()).isEqualTo("COMPONENT");
        assertThat(fs.getDescription()).contains("children");
        assertThat(fs.getImportsFrom()).contains("react-router-dom");
    }

    @Test
    void parsesBackendFileWithTypeInferredFromPath() {
        FakeLlm llm = new FakeLlm();
        // No explicit file_type — must be inferred from the backend/ path (generalization check).
        llm.responses.add("""
                {"files":[
                  {"file_name":"OrderItemResponse.java",
                   "file_path":"backend/src/main/java/com/farmaaish/dto/OrderItemResponse.java",
                   "layer":"DTO","feature_name":"orders",
                   "description":"Response DTO for one order line: menuItemId, name, quantity, price.",
                   "imports_from":[],"status":"PLANNED"}
                ]}""");

        List<FileSpec> out = llm.specifyMissingFiles(
                List.of("backend/src/main/java/com/farmaaish/dto/OrderItemResponse.java"), "ctx", "", brief());

        assertThat(out).hasSize(1);
        FileSpec fs = out.get(0);
        assertThat(fs.getFileType()).isEqualTo("BACKEND");   // inferred from path
        assertThat(fs.getLayer()).isEqualTo("DTO");
        assertThat(fs.getFilePath()).endsWith("OrderItemResponse.java");
    }

    @Test
    void emptyOnJunkResponseSoCallerStubs() {
        FakeLlm llm = new FakeLlm();
        llm.responses.add("not json at all");
        assertThat(llm.specifyMissingFiles(MISSING, "ctx", "", brief())).isEmpty();
    }

    @Test
    void emptyWhenNoFilesArray() {
        FakeLlm llm = new FakeLlm();
        llm.responses.add("{\"note\":\"nothing to add\"}");
        assertThat(llm.specifyMissingFiles(MISSING, "ctx", "", brief())).isEmpty();
    }

    @Test
    void emptyInputShortCircuitsWithoutCallingLlm() {
        FakeLlm llm = new FakeLlm();
        assertThat(llm.specifyMissingFiles(List.of(), "ctx", "", brief())).isEmpty();
        assertThat(llm.calls).isZero();
    }
}
