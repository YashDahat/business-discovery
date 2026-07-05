package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GhcrImageRefTest {

    @Test
    void buildsLowercaseRefFromRepoUrl() {
        String ref = GhcrImageRef.build("YashDahat",
                "https://github.com/YashDahat/Log-House-Restaurant.git",
                "fallback", "attempt-2");
        assertThat(ref).isEqualTo("ghcr.io/yashdahat/log-house-restaurant:attempt-2");
    }

    @Test
    void fallsBackToSlugWhenRepoUrlMissing() {
        String ref = GhcrImageRef.build("owner", null, "my-biz", "attempt-1");
        assertThat(ref).isEqualTo("ghcr.io/owner/my-biz:attempt-1");
    }

    @Test
    void retagSwapsOnlyTheTag() {
        assertThat(GhcrImageRef.retag("ghcr.io/o/repo:attempt-1", "ab12cd3"))
                .isEqualTo("ghcr.io/o/repo:ab12cd3");
        assertThat(GhcrImageRef.retag("ghcr.io/o/repo:attempt-1", "demo"))
                .isEqualTo("ghcr.io/o/repo:demo");
    }

    @Test
    void sanitizesIllegalTagCharacters() {
        assertThat(GhcrImageRef.sanitizeTag("feature/foo bar")).isEqualTo("feature-foo-bar");
        assertThat(GhcrImageRef.sanitizeTag("-leading")).isEqualTo("t-leading");
    }
}
