FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src
# -Dmaven.test.skip=true (not just -DskipTests) skips COMPILING tests too, so the
# test-scoped deps (testcontainers ~17MB, junit-jupiter) are never downloaded — they
# were the flaky Maven Central download that truncated the build. The packaged jar
# excludes tests regardless. Retry flags harden the remaining (compile-scoped) downloads
# against truncated responses (GET is idempotent → safe to retry after request sent).
RUN mvn clean package -q \
        -Dmaven.test.skip=true \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Dmaven.wagon.http.retryHandler.requestSentEnabled=true \
        --no-transfer-progress

# ── Runtime image ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Node.js 20 LTS + npm, plus docker CLI + compose plugin for SmokeTestNode/ImagePublishNode
# (client only — the daemon is reached over DOCKER_HOST via docker-proxy)
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        git \
        gnupg \
        ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && apt-get install -y --no-install-recommends maven \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu jammy stable" \
        > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

# Git identity (required for git commit)
RUN git config --global user.email "worker@discovery.local" \
    && git config --global user.name "Discovery Worker"

# Playwright Chromium + its OS deps, for the in-process e2e test runner (E2eTestNode /
# PlaywrightRunnerService). Verified installable on this jammy base at planning time. Keep the
# version in sync with worker.e2e.playwright-version (the @playwright/test dev dep the frontend
# scaffold pins) so the cached browser revision matches the runner.
RUN npx --yes playwright@1.48.0 install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
