package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DayPickerPropPatcherTest {

    @TempDir
    Path frontendSrc;

    private Path write(String rel, String body) throws Exception {
        Path p = frontendSrc.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void rewritesBareInitialFocusOnMultilineCalendar() throws Exception {
        // The real BookingFilter shape: a multiline <Calendar> with an arrow in onSelect (the `=>`
        // must not be mistaken for the tag end).
        Path p = write("components/booking/BookingFilter.tsx", """
                <Calendar
                  mode="single"
                  initialFocus
                  selected={startDate}
                  onSelect={(d) => setStartDate(d)}
                />
                """);

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isTrue();

        String out = Files.readString(p);
        assertThat(out).contains("autoFocus");
        assertThat(out).doesNotContain("initialFocus");
        assertThat(out).contains("onSelect={(d) => setStartDate(d)}"); // untouched
    }

    @Test
    void rewritesInitialFocusExpressionForm() throws Exception {
        Path p = write("A.tsx", "<Calendar mode=\"single\" initialFocus={true} />\n");

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isTrue();
        assertThat(Files.readString(p)).contains("autoFocus={true}").doesNotContain("initialFocus");
    }

    @Test
    void handlesDayPickerTagToo() throws Exception {
        Path p = write("B.tsx", "<DayPicker initialFocus mode=\"single\" />\n");

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isTrue();
        assertThat(Files.readString(p)).contains("<DayPicker autoFocus mode=\"single\" />");
    }

    @Test
    void doesNotTouchCalendarIconOrCalendarDays() throws Exception {
        // <CalendarIcon>/<CalendarDays> are lucide icons — the negative lookahead must exclude them,
        // and a stray `initialFocus` identifier outside any Calendar tag must be left alone.
        Path p = write("C.tsx", """
                import { CalendarIcon, CalendarDays } from 'lucide-react';
                const initialFocus = true;
                const a = <CalendarIcon className="h-4" />;
                const b = <CalendarDays />;
                """);
        String before = Files.readString(p);

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isFalse();
        assertThat(Files.readString(p)).isEqualTo(before);
    }

    @Test
    void isIdempotent() throws Exception {
        Path p = write("D.tsx", "<Calendar initialFocus mode=\"single\" />\n");

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isTrue();
        String afterFirst = Files.readString(p);
        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isFalse(); // nothing left to change
        assertThat(Files.readString(p)).isEqualTo(afterFirst);
    }

    @Test
    void skipsGeneratedFiles() throws Exception {
        Path p = write("E.tsx", "// GENERATED\n<Calendar initialFocus />\n");
        String before = Files.readString(p);

        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isFalse();
        assertThat(Files.readString(p)).isEqualTo(before);
    }

    @Test
    void noOpWhenNoCalendarInitialFocus() throws Exception {
        write("F.tsx", "<Calendar mode=\"single\" selected={d} />\n");
        assertThat(DayPickerPropPatcher.fix(frontendSrc)).isFalse();
    }
}
