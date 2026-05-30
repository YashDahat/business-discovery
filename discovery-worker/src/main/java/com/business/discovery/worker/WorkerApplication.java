package com.business.discovery.worker;

import com.business.discovery.worker.errorhandler.WorkerException;
import com.business.discovery.worker.orchestrator.WorkerOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import java.util.concurrent.atomic.AtomicBoolean;

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

        // Guard: set to true before any System.exit() so the shutdown hook
        // knows we exited normally and skips the redundant progress push.
        AtomicBoolean normalExit = new AtomicBoolean(false);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!normalExit.get()) {
                log.warn("[worker] External shutdown signal received — pushing progress before exit");
                orchestrator.pushProgressOnShutdown();
            }
        }, "worker-shutdown-hook"));

        try {
            orchestrator.run();
            normalExit.set(true);
            log.info("[worker] SUCCESS — exiting 0");
            if (!testMode) System.exit(0);
        } catch (WorkerException e) {
            // pushProgressOnFailure already ran inside WorkerOrchestrator.run()
            normalExit.set(true);
            log.error("[WORKER FAILED] type={} message={}", e.getFailureType(), e.getMessage());
            if (!testMode) System.exit(1);
        } catch (Exception e) {
            normalExit.set(true);
            log.error("[WORKER INFRA ERROR] {}", e.getMessage(), e);
            if (!testMode) System.exit(1);
        }
    }
}
