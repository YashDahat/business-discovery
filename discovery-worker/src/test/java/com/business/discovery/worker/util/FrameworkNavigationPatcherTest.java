package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkNavigationPatcherTest {

    // ── Next.js: module + call-shape rewrite ──────────────────────────────────

    @Test
    void rewritesNextImportAndPushCall() {
        String in = """
                import { useRouter } from 'next/navigation';
                export function LoginForm() {
                  const router = useRouter();
                  const onOk = () => router.push('/dashboard');
                }
                """;
        String out = FrameworkNavigationPatcher.rewrite(in);
        assertThat(out).contains("import { useNavigate } from 'react-router-dom';");
        assertThat(out).doesNotContain("next/navigation");
        assertThat(out).doesNotContain("useRouter");
        assertThat(out).contains("const router = useNavigate();");
        assertThat(out).contains("router('/dashboard')");
    }

    @Test
    void rewritesLegacyNextRouterImport() {
        String in = "import { useRouter } from \"next/router\";\nconst r = useRouter();\nr.push('/x');";
        String out = FrameworkNavigationPatcher.rewrite(in);
        assertThat(out).contains("import { useNavigate } from 'react-router-dom';");
        assertThat(out).contains("const r = useNavigate();");
        assertThat(out).contains("r('/x')");
    }

    @Test
    void mapsReplaceBackForwardRefresh() {
        String in = """
                import { useRouter } from 'next/navigation';
                const nav = useRouter();
                nav.replace('/a');
                nav.back();
                nav.forward();
                nav.refresh();
                """;
        String out = FrameworkNavigationPatcher.rewrite(in);
        assertThat(out).contains("nav('/a')");
        assertThat(out).contains("nav(-1)");
        assertThat(out).contains("nav(1)");
        assertThat(out).contains("nav(0)");
    }

    @Test
    void leavesNonUseRouterNextImportsAlone() {
        String in = "import { usePathname } from 'next/navigation';\nconst p = usePathname();";
        assertThat(FrameworkNavigationPatcher.rewrite(in)).isEqualTo(in);
    }

    // ── Drop-in module swaps: Remix, bare react-router ────────────────────────

    @Test
    void swapsRemixToReactRouterDom() {
        String in = "import { useNavigate, Link, useParams } from '@remix-run/react';";
        String out = FrameworkNavigationPatcher.rewrite(in);
        // Module swapped, names untouched (Remix re-exports react-router).
        assertThat(out).isEqualTo("import { useNavigate, Link, useParams } from 'react-router-dom';");
    }

    @Test
    void swapsBareReactRouterToReactRouterDom() {
        String in = "import { useNavigate } from 'react-router';";
        assertThat(FrameworkNavigationPatcher.rewrite(in))
                .isEqualTo("import { useNavigate } from 'react-router-dom';");
    }

    @Test
    void doesNotDoubleSwapReactRouterDom() {
        String in = "import { useNavigate } from 'react-router-dom';";
        assertThat(FrameworkNavigationPatcher.rewrite(in)).isEqualTo(in);
    }

    @Test
    void swapsTypeOnlyRemixImport() {
        String in = "import type { LinkProps } from '@remix-run/react';";
        assertThat(FrameworkNavigationPatcher.rewrite(in))
                .isEqualTo("import type { LinkProps } from 'react-router-dom';");
    }

    // ── Idempotency & no-ops ──────────────────────────────────────────────────

    @Test
    void idempotent() {
        String in = """
                import { useRouter } from 'next/navigation';
                import { Link } from '@remix-run/react';
                const r = useRouter();
                r.push('/x');
                """;
        String once = FrameworkNavigationPatcher.rewrite(in);
        assertThat(FrameworkNavigationPatcher.rewrite(once)).isEqualTo(once);
    }

    @Test
    void leavesPlatformCodeUnchanged() {
        String in = "import { useNavigate } from 'react-router-dom';\nconst n = useNavigate();";
        assertThat(FrameworkNavigationPatcher.rewrite(in)).isEqualTo(in);
    }
}
