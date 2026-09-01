package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EnumValueImportPatcherTest {

    @TempDir
    Path frontendSrc;

    private void writeEnumType() throws Exception {
        Path t = frontendSrc.resolve("types/inquiry.ts");
        Files.createDirectories(t.getParent());
        Files.writeString(t, """
                // GENERATED from the backend API contract — do not edit by hand.
                export const InquiryType = {
                  FREE_TRIAL: 'FREE_TRIAL',
                  GENERAL_INQUIRY: 'GENERAL_INQUIRY',
                } as const;
                export type InquiryType = typeof InquiryType[keyof typeof InquiryType];
                export const InquiryTypeValues = ['FREE_TRIAL', 'GENERAL_INQUIRY'] as const;
                """);
    }

    private Path writePage(String rel, String body) throws Exception {
        Path p = frontendSrc.resolve("pages").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void upgradesTypeImportToValueWhenEnumUsedAsValue() throws Exception {
        writeEnumType();
        // the real LeadCaptureForm shape: type-imported, then values read at runtime
        Path form = writePage("LeadCaptureForm.tsx", """
                import type { InquiryType, InquiryTypeValues } from '@/types/inquiry';
                const opts = InquiryTypeValues.map((t) => t);
                const dflt = InquiryType.GENERAL_INQUIRY;
                """);

        assertThat(EnumValueImportPatcher.fix(frontendSrc)).isTrue();

        String out = Files.readString(form);
        assertThat(out).contains("import { InquiryType, InquiryTypeValues } from '@/types/inquiry';");
        assertThat(out).doesNotContain("import type { InquiryType");
    }

    @Test
    void splitsMixedImportKeepingTypeOnlyNamesAsTypeImport() throws Exception {
        writeEnumType();
        Path p = writePage("InquiryForm.tsx", """
                import type { InquiryDto, InquiryType } from '@/types/inquiry';
                const d = InquiryType.FREE_TRIAL;
                function render(x: InquiryDto) { return x; }
                """);

        assertThat(EnumValueImportPatcher.fix(frontendSrc)).isTrue();

        String out = Files.readString(p);
        assertThat(out).contains("import { InquiryType } from '@/types/inquiry';");
        assertThat(out).contains("import type { InquiryDto } from '@/types/inquiry';");
    }

    @Test
    void leavesTypeOnlyUsageUntouched() throws Exception {
        writeEnumType();
        Path p = writePage("Row.tsx", """
                import type { InquiryType } from '@/types/inquiry';
                type Props = { kind: InquiryType };
                const cast = 'FREE_TRIAL' as InquiryType;
                """);

        assertThat(EnumValueImportPatcher.fix(frontendSrc)).isFalse();      // no value usage → no change
        assertThat(Files.readString(p)).contains("import type { InquiryType }");
    }

    @Test
    void doesNotTouchGeneratedTypeFileItself() throws Exception {
        writeEnumType();
        String before = Files.readString(frontendSrc.resolve("types/inquiry.ts"));
        EnumValueImportPatcher.fix(frontendSrc);
        assertThat(Files.readString(frontendSrc.resolve("types/inquiry.ts"))).isEqualTo(before);
    }

    @Test
    void noOpWhenNoConstEnumsDeclared() throws Exception {
        // a plain string-union type file declares no const-object enum
        Path t = frontendSrc.resolve("types/x.ts");
        Files.createDirectories(t.getParent());
        Files.writeString(t, "export type Status = 'A' | 'B';\n");
        writePage("P.tsx", "import type { Status } from '@/types/x';\nconst s = Status;\n");

        assertThat(EnumValueImportPatcher.fix(frontendSrc)).isFalse();
    }
}
