package com.business.discovery.worker;

import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.orchestrator.WorkerOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

@SpringBootApplication
@RequiredArgsConstructor
@Slf4j
public class WorkerApplication implements CommandLineRunner {

    private final WorkerOrchestrator orchestrator;
    private final Environment environment;

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WorkerApplication.class);
        // Disable Spring Boot exit hooks — we call System.exit() explicitly
        app.setRegisterShutdownHook(false);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        boolean testMode = environment.matchesProfiles("test");
        try {
            orchestrator.run();
            log.info("[worker] SUCCESS — exiting 0");
            if (!testMode) System.exit(0);
        } catch (WorkerException e) {
            log.error("[WORKER FAILED] type={} message={}", e.getFailureType(), e.getMessage());
            if (!testMode) System.exit(1);
        } catch (Exception e) {
            log.error("[WORKER INFRA ERROR] {}", e.getMessage(), e);
            if (!testMode) System.exit(1);
        }
    }
}
