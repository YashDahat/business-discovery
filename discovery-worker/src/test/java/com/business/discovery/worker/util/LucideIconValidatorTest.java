package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LucideIconValidatorTest {

    @TempDir
    Path frontendSrc;

    // A representative slice of the real lucide-react surface.
    private static final Set<String> EXPORTS = Set.of(
            "Waves", "Droplets", "Droplet", "Dumbbell", "Activity", "Users", "User",
            "Star", "Sparkles", "Circle", "Calendar", "CalendarDays", "Pencil", "PencilIcon", "Route");

    private Path writePage(String rel, String body) throws Exception {
        Path p = frontendSrc.resolve("pages").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void tierA_normalizesSingularToPluralAndRewritesUsages() throws Exception {
        // 'Wave' is not exported; the real icon is the plural 'Waves' — a certain typo, auto-fixed.
        Path page = writePage("Pool.tsx", """
                import { Wave } from 'lucide-react';
                export const Pool = () => <Wave className="h-4" />;
                """);

        assertThat(LucideIconValidator.fix(frontendSrc, EXPORTS)).isTrue();

        String out = Files.readString(page);
        assertThat(out).contains("import { Waves } from 'lucide-react';");
        assertThat(out).contains("<Waves className=\"h-4\" />");
        assertThat(out).doesNotContain("Wave ").doesNotContain("<Wave ");
        assertThat(out).doesNotContain("FIXME");
    }

    @Test
    void tierA_stripsIconSuffixWhenBareFormExists() throws Exception {
        // Only 'Circle' exists here, not 'CircleIcon' — strip the suffix.
        Path page = writePage("Dot.tsx", """
                import { CircleIcon } from 'lucide-react';
                export const Dot = () => <CircleIcon />;
                """);

        assertThat(LucideIconValidator.fix(frontendSrc, EXPORTS)).isTrue();

        String out = Files.readString(page);
        assertThat(out).contains("import { Circle } from 'lucide-react';");
        assertThat(out).contains("<Circle />");
    }

    @Test
    void tierB_annotatesHallucinatedIconWithoutRewriting() throws Exception {
        Path page = writePage("Facilities.tsx", """
                import { SwimmingPool } from 'lucide-react';
                export const Facilities = () => <SwimmingPool />;
                """);

        // Only a Tier B annotation → fix() returns false (no build-fixing change), but the file is written.
        assertThat(LucideIconValidator.fix(frontendSrc, EXPORTS)).isFalse();

        String out = Files.readString(page);
        assertThat(out).contains("// FIXME[invalid-icon]: 'SwimmingPool' is not exported by lucide-react");
        // Concept table: swim/pool -> Waves/Droplets — a verified, real suggestion.
        assertThat(out).contains("Waves");
        // The import itself is left untouched so the build stays red and the agent runs.
        assertThat(out).contains("import { SwimmingPool } from 'lucide-react';");
    }

    @Test
    void leavesValidIconsUntouched() throws Exception {
        Path page = writePage("Card.tsx", """
                import { Dumbbell, Star } from 'lucide-react';
                export const Card = () => <div><Dumbbell /><Star /></div>;
                """);
        String before = Files.readString(page);

        assertThat(LucideIconValidator.fix(frontendSrc, EXPORTS)).isFalse();
        assertThat(Files.readString(page)).isEqualTo(before);
    }

    @Test
    void keepsValidSiblingWhileAnnotatingInvalidOne() throws Exception {
        Path page = writePage("Mixed.tsx", """
                import { Dumbbell, SwimmingPool } from 'lucide-react';
                export const Mixed = () => <div><Dumbbell /><SwimmingPool /></div>;
                """);

        LucideIconValidator.fix(frontendSrc, EXPORTS);

        String out = Files.readString(page);
        assertThat(out).contains("// FIXME[invalid-icon]: 'SwimmingPool'");
        assertThat(out).contains("import { Dumbbell, SwimmingPool } from 'lucide-react';");
    }

    @Test
    void isIdempotent_doesNotDuplicateAnnotation() throws Exception {
        Path page = writePage("Facilities.tsx", """
                import { SwimmingPool } from 'lucide-react';
                export const Facilities = () => <SwimmingPool />;
                """);

        LucideIconValidator.fix(frontendSrc, EXPORTS);
        String afterFirst = Files.readString(page);
        LucideIconValidator.fix(frontendSrc, EXPORTS);
        String afterSecond = Files.readString(page);

        assertThat(afterSecond).isEqualTo(afterFirst);
        // exactly one FIXME line
        assertThat(afterSecond.split("FIXME\\[invalid-icon]", -1)).hasSize(2);
    }

    @Test
    void skipsGeneratedFiles() throws Exception {
        Path page = writePage("Gen.tsx", """
                // GENERATED — do not edit
                import { SwimmingPool } from 'lucide-react';
                """);
        String before = Files.readString(page);

        LucideIconValidator.fix(frontendSrc, EXPORTS);
        assertThat(Files.readString(page)).isEqualTo(before);
    }

    @Test
    void noOpWhenExportSetEmpty() throws Exception {
        Path page = writePage("Any.tsx", "import { SwimmingPool } from 'lucide-react';\n");
        String before = Files.readString(page);

        assertThat(LucideIconValidator.fix(frontendSrc, Set.of())).isFalse();
        assertThat(Files.readString(page)).isEqualTo(before);
    }
}
