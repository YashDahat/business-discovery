package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaFileTemplaterSpaControllerTest {

    private static FileSpec spec(String fileName) {
        FileSpec f = new FileSpec();
        f.setFileName(fileName);
        f.setFilePath("backend/src/main/java/com/vikramsfitnessstudio/controller/" + fileName);
        return f;
    }

    @Test
    @DisplayName("SpaController is templated regardless of layer priority")
    void classify_detectsSpaController() {
        assertThat(JavaFileTemplater.classify(spec("SpaController.java"), 60))
                .isEqualTo(JavaFileTemplater.TemplateType.SPA_CONTROLLER);
    }

    @Test
    @DisplayName("common alias filenames are templated too")
    void classify_detectsAliases() {
        for (String name : new String[]{"SPAController.java", "SpaForwardController.java",
                                        "ForwardController.java", "ClientRoutingController.java"}) {
            assertThat(JavaFileTemplater.classify(spec(name), 60))
                    .as(name)
                    .isEqualTo(JavaFileTemplater.TemplateType.SPA_CONTROLLER);
        }
    }

    @Test
    @DisplayName("an ordinary controller is left to the LLM")
    void classify_ignoresNormalController() {
        assertThat(JavaFileTemplater.classify(spec("OrderController.java"), 60))
                .isEqualTo(JavaFileTemplater.TemplateType.NONE);
    }

    @Test
    @DisplayName("emits @Controller with a dot-excluding path regex and no /** suffix")
    void generate_emitsCorrectShape() {
        String out = JavaFileTemplater.generate(
                spec("SpaController.java"), "com.vikramsfitnessstudio",
                JavaFileTemplater.TemplateType.SPA_CONTROLLER);

        assertThat(out).contains("package com.vikramsfitnessstudio.controller;");
        assertThat(out).contains("public class SpaController");
        assertThat(out).contains("return \"forward:/index.html\";");

        // @Controller, never @RestController — the latter returns the literal string
        assertThat(out).contains("@Controller");
        assertThat(out).doesNotContain("@RestController");

        // The regex must exclude dots so /assets/index.js stays with the resource handler,
        // and must not carry a /** suffix, which would swallow it. The emitted Java source
        // carries an escaped regex, so the file text is [^\\.]* — two literal backslashes.
        assertThat(out).contains("[^\\\\.]*");
        assertThat(out).doesNotContain("/**\"");
    }

    @Test
    @DisplayName("class name follows the filename alias")
    void generate_usesAliasClassName() {
        String out = JavaFileTemplater.generate(
                spec("ForwardController.java"), "com.vikramsfitnessstudio",
                JavaFileTemplater.TemplateType.SPA_CONTROLLER);

        assertThat(out).contains("public class ForwardController");
    }
}
