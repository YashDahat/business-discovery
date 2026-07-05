package com.business.discovery.services.demo;

import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.DemoInstance;
import com.business.discovery.model.DemoInstance.DemoStatus;
import com.business.discovery.repository.ContainerTaskRepository;
import com.business.discovery.repository.DemoInstanceRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.PullImageResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs client demos from the GHCR image the worker published after smoke gates passed.
 * Pull + run only — the artifact is never rebuilt, so what the client sees is exactly
 * what the smoke test verified.
 *
 * Both containers join the shared docker network (user-defined bridge → container-name
 * DNS) so the master can poll the boot gate internally; the app additionally publishes
 * an allocated host port for the client's browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoService {

    private static final EnumSet<DemoStatus> ACTIVE =
            EnumSet.of(DemoStatus.PULLING, DemoStatus.STARTING, DemoStatus.RUNNING);

    private final DockerClient dockerClient;
    private final DemoInstanceRepository demoRepo;
    private final ContainerTaskRepository taskRepo;

    @Value("${docker.network.name:shared-network}")
    private String networkName;

    /** Inclusive range of host ports available for demos — also caps concurrent demos. */
    @Value("${app.demo.port-range:9000-9019}")
    private String portRange;

    /** Hostname clients use to reach demos, e.g. the EC2 public DNS. */
    @Value("${app.demo.public-host:localhost}")
    private String publicHost;

    @Value("${app.demo.max-lifetime-hours:48}")
    private int maxLifetimeHours;

    @Value("${app.demo.boot-timeout-seconds:90}")
    private int bootTimeoutSeconds;

    @Value("${worker.github.owner:}")
    private String githubOwner;

    @Value("${worker.github.token:}")
    private String githubToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Dedicated executor (not @Async): start() launches from within this bean, and
    // Spring's @Async proxy does not intercept self-invocations. Demos are rare and
    // long-lived, so a small on-demand pool is the right shape.
    private final ExecutorService launchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "demo-launcher");
        t.setDaemon(true);
        return t;
    });

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Creates the DemoInstance row and kicks off the async pull/start.
     * Returns immediately (202 semantics) — the UI polls getForBrief().
     */
    public DemoInstance start(UUID briefId) {
        demoRepo.findFirstByBriefIdAndStatusIn(briefId, ACTIVE).ifPresent(active -> {
            throw new IllegalStateException("Demo already " + active.getStatus() + " for brief " + briefId
                    + (active.getDemoUrl() != null ? " at " + active.getDemoUrl() : ""));
        });

        String imageRef = resolvePublishedImage(briefId);
        String slug = slugFromImage(imageRef);
        int hostPort = allocatePort();

        DemoInstance demo = demoRepo.save(DemoInstance.builder()
                .briefId(briefId)
                .slug(slug)
                .imageRef(imageRef)
                .hostPort(hostPort)
                .status(DemoStatus.PULLING)
                .demoUrl("http://" + publicHost + ":" + hostPort)
                .startedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(maxLifetimeHours))
                .build());

        launchExecutor.submit(() -> launchAsync(demo.getId()));
        return demo;
    }

    public DemoInstance getForBrief(UUID briefId) {
        return demoRepo.findFirstByBriefIdOrderByStartedAtDesc(briefId)
                .orElseThrow(() -> new IllegalArgumentException("No demo instance for brief " + briefId));
    }

    public List<DemoInstance> listActive() {
        return demoRepo.findByStatusIn(ACTIVE);
    }

    public DemoInstance stopForBrief(UUID briefId) {
        DemoInstance demo = demoRepo.findFirstByBriefIdAndStatusIn(briefId, ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No active demo for brief " + briefId));
        return stop(demo);
    }

    public DemoInstance stop(DemoInstance demo) {
        removeContainerQuietly(demo.getAppContainerId());
        removeContainerQuietly(demo.getDbContainerId());
        demo.setStatus(DemoStatus.STOPPED);
        demoRepo.save(demo);
        log.info("[Demo] Stopped demo {} ({})", demo.getSlug(), demo.getId());
        return demo;
    }

    // ── Async launch pipeline ──────────────────────────────────────────────

    void launchAsync(UUID demoId) {
        DemoInstance demo = demoRepo.findById(demoId).orElse(null);
        if (demo == null) return;

        try {
            pullImage(demo.getImageRef());

            demo.setStatus(DemoStatus.STARTING);
            demoRepo.save(demo);

            startContainers(demo);
            demoRepo.save(demo); // persist container ids before the (long) boot poll

            if (waitForBoot(demo)) {
                demo.setStatus(DemoStatus.RUNNING);
                demoRepo.save(demo);
                log.info("[Demo] {} RUNNING at {}", demo.getSlug(), demo.getDemoUrl());
            } else {
                fail(demo, "App did not become healthy within " + bootTimeoutSeconds
                        + "s — check container logs for " + appName(demo));
            }
        } catch (Exception e) {
            fail(demo, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void fail(DemoInstance demo, String message) {
        log.warn("[Demo] {} failed: {}", demo.getSlug(), message);
        removeContainerQuietly(demo.getAppContainerId());
        removeContainerQuietly(demo.getDbContainerId());
        demo.setStatus(DemoStatus.FAILED);
        demo.setErrorMessage(message);
        demoRepo.save(demo);
    }

    // ── Docker operations ──────────────────────────────────────────────────

    private void pullImage(String imageRef) throws InterruptedException {
        AuthConfig auth = new AuthConfig()
                .withRegistryAddress("ghcr.io")
                .withUsername(githubOwner)
                .withPassword(githubToken);

        log.info("[Demo] Pulling {}", imageRef);
        boolean done = dockerClient.pullImageCmd(imageRef)
                .withAuthConfig(auth)
                .exec(new PullImageResultCallback() {
                    @Override
                    public void onNext(PullResponseItem item) {
                        super.onNext(item);
                    }
                })
                .awaitCompletion(5, TimeUnit.MINUTES);
        if (!done) throw new IllegalStateException("Image pull timed out: " + imageRef);
    }

    private void startContainers(DemoInstance demo) {
        String dbName = dbName(demo);
        String appName = appName(demo);

        // Stale containers from a crashed run would block the names — clear them first
        removeContainerByNameQuietly(dbName);
        removeContainerByNameQuietly(appName);

        String dbPassword = randomToken(16);
        String jwtSecret = randomToken(64);

        CreateContainerResponse db = dockerClient.createContainerCmd("postgres:16-alpine")
                .withName(dbName)
                .withEnv(List.of(
                        "POSTGRES_DB=demo",
                        "POSTGRES_USER=demo",
                        "POSTGRES_PASSWORD=" + dbPassword))
                .withHostConfig(HostConfig.newHostConfig()
                        .withNetworkMode(networkName))
                .exec();
        dockerClient.startContainerCmd(db.getId()).exec();
        demo.setDbContainerId(db.getId());

        ExposedPort appPort = ExposedPort.tcp(8080);
        Ports bindings = new Ports();
        bindings.bind(appPort, Ports.Binding.bindPort(demo.getHostPort()));

        CreateContainerResponse app = dockerClient.createContainerCmd(demo.getImageRef())
                .withName(appName)
                .withEnv(List.of(
                        "DB_URL=jdbc:postgresql://" + dbName + ":5432/demo",
                        "DB_USERNAME=demo",
                        "DB_PASSWORD=" + dbPassword,
                        "JWT_SECRET=" + jwtSecret,
                        "JWT_EXPIRATION_MS=86400000",
                        "ADMIN_EMAIL=demo-admin@discovery.local",
                        "ADMIN_PASSWORD=demo-admin-" + randomToken(8),
                        "RAZORPAY_KEY_ID=demo-placeholder",
                        "RAZORPAY_KEY_SECRET=demo-placeholder",
                        "RAZORPAY_WEBHOOK_SECRET=demo-placeholder"))
                .withExposedPorts(appPort)
                .withHostConfig(HostConfig.newHostConfig()
                        .withNetworkMode(networkName)
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(demo.getHostPort()), appPort))
                        .withMemory(1024 * 1024 * 1024L))
                .exec();
        dockerClient.startContainerCmd(app.getId()).exec();
        demo.setAppContainerId(app.getId());

        log.info("[Demo] Containers started — db={} app={} port={}", dbName, appName, demo.getHostPort());
    }

    /** Polls the app's health endpoint over the shared network (container-name DNS). */
    private boolean waitForBoot(DemoInstance demo) {
        String healthUrl = "http://" + appName(demo) + ":8080/actuator/health";
        long deadline = System.currentTimeMillis() + bootTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(healthUrl))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return true;
            } catch (Exception ignored) {
                // container still starting — DNS or connection refused is expected early on
            }
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void removeContainerQuietly(String containerId) {
        if (containerId == null || containerId.isBlank()) return;
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (Exception e) {
            log.debug("[Demo] Could not remove container {}: {}", containerId, e.getMessage());
        }
    }

    private void removeContainerByNameQuietly(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).withRemoveVolumes(true).exec();
            log.info("[Demo] Removed stale container {}", name);
        } catch (Exception ignored) {
            // no stale container — the normal case
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String resolvePublishedImage(UUID briefId) {
        ContainerTask task = taskRepo.findTopByBriefIdOrderByCreatedAtDesc(briefId)
                .orElseThrow(() -> new IllegalArgumentException("No container task for brief " + briefId));
        String image = task.getPublishedImage();
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("No published image for brief " + briefId
                    + " — the latest attempt has not passed the smoke test / GHCR publish yet");
        }
        return image;
    }

    private int allocatePort() {
        String[] parts = portRange.split("-");
        int from = Integer.parseInt(parts[0].trim());
        int to = Integer.parseInt(parts[1].trim());
        for (int port = from; port <= to; port++) {
            if (!demoRepo.existsByHostPortAndStatusIn(port, ACTIVE)) {
                return port;
            }
        }
        throw new IllegalStateException("No free demo port in range " + portRange
                + " — stop an existing demo first");
    }

    static String slugFromImage(String imageRef) {
        // ghcr.io/owner/log-house-restaurant:ab12cd3 → log-house-restaurant
        String noTag = imageRef.contains(":") && imageRef.lastIndexOf(':') > imageRef.lastIndexOf('/')
                ? imageRef.substring(0, imageRef.lastIndexOf(':'))
                : imageRef;
        return noTag.substring(noTag.lastIndexOf('/') + 1);
    }

    private String dbName(DemoInstance demo) {
        return "demo-" + demo.getSlug() + "-db";
    }

    private String appName(DemoInstance demo) {
        return "demo-" + demo.getSlug() + "-app";
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
