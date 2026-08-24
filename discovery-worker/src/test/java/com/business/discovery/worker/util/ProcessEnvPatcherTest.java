package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessEnvPatcherTest {

    @Test
    void rewritesNextPublicToVite() {
        assertThat(ProcessEnvPatcher.rewrite("const k = process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY;"))
                .isEqualTo("const k = import.meta.env.VITE_GOOGLE_MAPS_API_KEY;");
    }

    @Test
    void rewritesReactAppToVite() {
        assertThat(ProcessEnvPatcher.rewrite("process.env.REACT_APP_API_URL"))
                .isEqualTo("import.meta.env.VITE_API_URL");
    }

    @Test
    void keepsExistingViteName() {
        assertThat(ProcessEnvPatcher.rewrite("process.env.VITE_FOO"))
                .isEqualTo("import.meta.env.VITE_FOO");
    }

    @Test
    void plainNameGetsVitePrefix() {
        assertThat(ProcessEnvPatcher.rewrite("process.env.MY_KEY"))
                .isEqualTo("import.meta.env.VITE_MY_KEY");
    }

    @Test
    void rewritesEveryOccurrence() {
        assertThat(ProcessEnvPatcher.rewrite("`${process.env.NEXT_PUBLIC_A}-${process.env.REACT_APP_B}`"))
                .isEqualTo("`${import.meta.env.VITE_A}-${import.meta.env.VITE_B}`");
    }

    @Test
    void leavesContentWithoutProcessEnvUnchanged() {
        String s = "const x = import.meta.env.VITE_FOO;";
        assertThat(ProcessEnvPatcher.rewrite(s)).isEqualTo(s);
    }
}
