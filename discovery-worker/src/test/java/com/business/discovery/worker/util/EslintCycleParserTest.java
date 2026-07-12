package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EslintCycleParserTest {

    // Real shape of `eslint --format json` output: array of files, each with a messages array.
    // Mixes an import-x/no-cycle error with an unrelated lint message that must be ignored.
    private static final String WITH_CYCLE = """
            [
              {
                "filePath": "/workspace/frontend/src/pages/AdminMenuPage.tsx",
                "messages": [
                  {"ruleId": "import-x/no-cycle", "message": "Dependency cycle detected.", "line": 3, "column": 1, "severity": 2},
                  {"ruleId": "unused-imports/no-unused-vars", "message": "'x' is defined but never used.", "line": 5, "column": 7, "severity": 1}
                ]
              },
              {
                "filePath": "/workspace/frontend/src/components/MenuTable.tsx",
                "messages": []
              }
            ]
            """;

    private static final String CLEAN = """
            [
              {"filePath": "/workspace/frontend/src/App.tsx", "messages": []}
            ]
            """;

    @Test
    void extractsOnlyNoCycleViolations() {
        List<EslintCycleParser.Cycle> cycles = EslintCycleParser.parse(WITH_CYCLE);
        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0).filePath()).endsWith("AdminMenuPage.tsx");
        assertThat(cycles.get(0).line()).isEqualTo(3);
        assertThat(cycles.get(0).message()).contains("cycle");
    }

    @Test
    void matchesLegacyImportPluginRuleId() {
        String legacy = WITH_CYCLE.replace("import-x/no-cycle", "import/no-cycle");
        assertThat(EslintCycleParser.parse(legacy)).hasSize(1);
    }

    @Test
    void returnsEmptyWhenNoCycles() {
        assertThat(EslintCycleParser.parse(CLEAN)).isEmpty();
    }

    // eslint crashed (missing plugin / bad config) → stderr text, not JSON. Must NOT throw and must
    // NOT report a cycle, so the caller degrades to a warning instead of failing every build.
    @Test
    void returnsEmptyOnUnparseableOutput() {
        assertThat(EslintCycleParser.parse("Oops! Something went wrong! Cannot find package 'eslint-plugin-import-x'"))
                .isEmpty();
    }

    @Test
    void returnsEmptyOnNullOrBlank() {
        assertThat(EslintCycleParser.parse(null)).isEmpty();
        assertThat(EslintCycleParser.parse("")).isEmpty();
        assertThat(EslintCycleParser.parse("   ")).isEmpty();
    }

    @Test
    void collectsCyclesAcrossMultipleFiles() {
        String twoFiles = """
                [
                  {"filePath": "/w/src/a.tsx", "messages": [{"ruleId": "import-x/no-cycle", "message": "Dependency cycle via ./b:2", "line": 1}]},
                  {"filePath": "/w/src/b.tsx", "messages": [{"ruleId": "import-x/no-cycle", "message": "Dependency cycle via ./a:2", "line": 1}]}
                ]
                """;
        assertThat(EslintCycleParser.parse(twoFiles)).hasSize(2);
    }
}
