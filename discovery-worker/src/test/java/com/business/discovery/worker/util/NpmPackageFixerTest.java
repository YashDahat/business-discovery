package com.business.discovery.worker.util;

import com.business.discovery.worker.service.BuildToolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NpmPackageFixerTest {

    @TempDir
    Path frontendDir;

    private Path writeSource(String relativePath, String content) throws IOException {
        Path file = frontendDir.resolve("src").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    /**
     * The LLM hallucinates a non-existent Radix package when it wants the shadcn wrapper. The fixer
     * must rewrite the import to the local shadcn component (no install needed) — the rename path
     * never touches npm, so a plain BuildToolService is safe here.
     */
    @Test
    void rewritesHallucinatedRadixInputToShadcnComponent() throws IOException {
        Path login = writeSource("pages/LoginPage.tsx",
                "import { Input } from '@radix-ui/react-input';\nexport const LoginPage = () => <Input />;\n");
        String buildOutput =
                "src/pages/LoginPage.tsx(1,23): error TS2307: Cannot find module '@radix-ui/react-input' "
                        + "or its corresponding type declarations.";

        boolean fixed = NpmPackageFixer.fix(frontendDir, buildOutput, new BuildToolService());

        assertThat(fixed).isTrue();
        assertThat(Files.readString(login))
                .contains("from '@/components/ui/input'")
                .doesNotContain("@radix-ui/react-input");
    }

    /** A relative or @/ alias miss is a path problem, not a missing package — the fixer must ignore it. */
    @Test
    void ignoresAliasAndRelativeModuleMisses() throws IOException {
        writeSource("pages/MenuPage.tsx", "import { Foo } from '@/components/ui/foo';\n");
        String buildOutput =
                "src/pages/MenuPage.tsx(1,20): error TS2307: Cannot find module '@/components/ui/foo' "
                        + "or its corresponding type declarations.";

        boolean fixed = NpmPackageFixer.fix(frontendDir, buildOutput, new BuildToolService());

        assertThat(fixed).isFalse();
    }
}
