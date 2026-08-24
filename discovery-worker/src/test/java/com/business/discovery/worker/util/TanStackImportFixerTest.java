package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TanStackImportFixerTest {

    @Test
    void addsMissingHookToExistingImport() {
        String in = "import { useMutation, useQueryClient } from '@tanstack/react-query';\n"
                  + "const q = useQuery({ queryKey: ['x'] });\n";
        assertThat(TanStackImportFixer.fixContent(in))
                .startsWith("import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';")
                .contains("const q = useQuery");
    }

    @Test
    void addsNewImportWhenNoneExists() {
        String in = "const q = useQuery({ queryKey: ['x'] });\n";
        assertThat(TanStackImportFixer.fixContent(in))
                .startsWith("import { useQuery } from '@tanstack/react-query';\n")
                .endsWith("const q = useQuery({ queryKey: ['x'] });\n");
    }

    @Test
    void untouchedWhenAllUsedHooksAreImported() {
        String in = "import { useQuery } from '@tanstack/react-query';\nconst q = useQuery();\n";
        assertThat(TanStackImportFixer.fixContent(in)).isEqualTo(in);
    }

    @Test
    void doesNotMatchUseQueryClientAsUseQuery() {
        // useQueryClient is imported + used; useQuery itself is NOT used → nothing added.
        String in = "import { useQueryClient } from '@tanstack/react-query';\nconst c = useQueryClient();\n";
        assertThat(TanStackImportFixer.fixContent(in)).isEqualTo(in);
    }

    @Test
    void untouchedWhenNoHooksUsed() {
        String in = "export const x = 1;\n";
        assertThat(TanStackImportFixer.fixContent(in)).isEqualTo(in);
    }
}
