package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvVarScannerTest {

    @TempDir
    Path workspace;

    private void writeProps(String body) throws Exception {
        Path p = workspace.resolve("backend/src/main/resources/application.properties");
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    // ── envDefaultsFromProperties ────────────────────────────────────────────

    @Test
    void extractsNonEmptyDefaultsAndSkipsSecrets() throws Exception {
        writeProps("""
                s3.path-style=${S3_PATH_STYLE:true}
                jwt.expiration-ms=${JWT_EXPIRATION_MS:86400000}
                s3.endpoint=${S3_ENDPOINT:http://minio:9000}
                razorpay.key.secret=${RAZORPAY_KEY_SECRET:}
                db.url=${DB_URL}
                """);

        Map<String, String> defaults = EnvVarScanner.envDefaultsFromProperties(
                workspace.resolve("backend/src/main/resources/application.properties"));

        assertThat(defaults).containsEntry("S3_PATH_STYLE", "true");
        assertThat(defaults).containsEntry("JWT_EXPIRATION_MS", "86400000");
        assertThat(defaults).containsEntry("S3_ENDPOINT", "http://minio:9000"); // default keeps its own ':'
        assertThat(defaults).doesNotContainKey("RAZORPAY_KEY_SECRET");           // empty default = secret
        assertThat(defaults).doesNotContainKey("DB_URL");                        // bare ${..}, no default
    }

    @Test
    void returnsEmptyMapWhenPropertiesMissing() {
        assertThat(EnvVarScanner.envDefaultsFromProperties(
                workspace.resolve("backend/src/main/resources/application.properties"))).isEmpty();
    }

    // ── backfillEnvExampleDefaults ───────────────────────────────────────────

    @Test
    void backfillsBlankTypedValuesAndLeavesSecretsBlank() throws Exception {
        writeProps("""
                s3.path-style=${S3_PATH_STYLE:true}
                s3.region=${S3_REGION:us-east-1}
                razorpay.key.id=${RAZORPAY_KEY_ID:}
                """);
        Files.writeString(workspace.resolve(".env.example"), """
                # storage
                S3_PATH_STYLE=
                S3_REGION=
                RAZORPAY_KEY_ID=
                """);

        EnvVarScanner.backfillEnvExampleDefaults(workspace);

        String env = Files.readString(workspace.resolve(".env.example"));
        assertThat(env).contains("S3_PATH_STYLE=true");
        assertThat(env).contains("S3_REGION=us-east-1");
        assertThat(env).contains("RAZORPAY_KEY_ID=");        // secret stays blank
        assertThat(env).doesNotContain("RAZORPAY_KEY_ID=us-east-1");
        assertThat(env).contains("# storage");               // comments preserved
    }

    @Test
    void doesNotOverwriteExistingNonBlankValues() throws Exception {
        writeProps("s3.path-style=${S3_PATH_STYLE:true}\n");
        Files.writeString(workspace.resolve(".env.example"), "S3_PATH_STYLE=false\n");

        EnvVarScanner.backfillEnvExampleDefaults(workspace);

        assertThat(Files.readString(workspace.resolve(".env.example"))).contains("S3_PATH_STYLE=false");
    }

    @Test
    void noOpWhenNoEnvExample() throws Exception {
        writeProps("s3.path-style=${S3_PATH_STYLE:true}\n");
        EnvVarScanner.backfillEnvExampleDefaults(workspace); // must not throw
        assertThat(Files.exists(workspace.resolve(".env.example"))).isFalse();
    }
}
