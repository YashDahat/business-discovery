package com.business.discovery.worker.service;

import com.business.discovery.worker.service.ComposeLaunchService.GateReport;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchResult;
import com.business.discovery.worker.service.ComposeLaunchService.LaunchSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end run of the smoke-test launcher against a real generated project on the
 * dev host. Needs a local docker daemon and a generated project directory.
 *
 * Excluded from normal builds (surefire only picks up *Test). Run explicitly:
 *   ./mvnw -f discovery-worker/pom.xml test -Dtest=ComposeLaunchServiceIT \
 *       -Dsmoke.it.workspace=/tmp/log-house-restaurant -Dsmoke.it=true
 */
class ComposeLaunchServiceIT {

    @Test
    @EnabledIfSystemProperty(named = "smoke.it", matches = "true")
    void launchesGatesAndTearsDownGeneratedProject() {
        Path workspace = Path.of(System.getProperty("smoke.it.workspace", "/tmp/log-house-restaurant"));
        assertThat(Files.exists(workspace.resolve("docker-compose.yml")))
                .as("workspace has a compose file")
                .isTrue();

        ComposeLaunchService service = new ComposeLaunchService();
        ReflectionTestUtils.setField(service, "sharedNetwork", "shared-network");
        ReflectionTestUtils.setField(service, "inContainer", false); // dev host: published port
        ReflectionTestUtils.setField(service, "bootTimeoutSeconds", 120);
        ReflectionTestUtils.setField(service, "dockerHost", "");     // local daemon

        LaunchSpec spec = new LaunchSpec("smoke-it", "smoke-it-app:latest", 18080);
        try {
            LaunchResult launch = service.launch(workspace, spec);
            assertThat(launch.success())
                    .as("compose up --build succeeded: %s", launch.output())
                    .isTrue();

            GateReport report = service.runGates(launch.baseUrl(), workspace);
            System.out.println("=== GATE REPORT ===\n" + report.summary());

            if (!report.passed()) {
                System.out.println("=== CONTAINER LOGS (tail) ===");
                String logs = service.collectLogs(workspace, spec.projectName());
                System.out.println(logs.length() > 6000 ? logs.substring(logs.length() - 6000) : logs);
            }
            assertThat(report.passed()).as("all smoke gates pass").isTrue();
        } finally {
            service.down(workspace, spec.projectName(), true);
        }
    }
}
