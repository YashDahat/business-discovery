package com.business.discovery.services.scraper;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScraperContainerService {

    private final DockerClient dockerClient;

    @Value("${scraper.container.name:scraper}")
    private String containerName;

    @Value("${scraper.container.image:gosom/google-maps-scraper:latest}")
    private String containerImage;

    @Value("${docker.network.name:shared-network}")
    private String networkName;

    public enum ContainerState { RUNNING, STOPPED, NOT_FOUND }

    public record ContainerStatus(ContainerState state, String containerId) {}

    public ContainerStatus getStatus() {
        try {
            var info = dockerClient.inspectContainerCmd(containerName).exec();
            Boolean running = info.getState().getRunning();
            String id = info.getId();
            return new ContainerStatus(
                Boolean.TRUE.equals(running) ? ContainerState.RUNNING : ContainerState.STOPPED,
                id
            );
        } catch (NotFoundException e) {
            return new ContainerStatus(ContainerState.NOT_FOUND, null);
        }
    }

    public void start() {
        ContainerStatus status = getStatus();

        if (status.state() == ContainerState.RUNNING) {
            log.info("[Scraper] Container '{}' is already running", containerName);
            return;
        }

        if (status.state() == ContainerState.STOPPED) {
            log.info("[Scraper] Restarting stopped container '{}'", containerName);
            dockerClient.startContainerCmd(containerName).exec();
            return;
        }

        // Pull image if not present locally
        pullImageIfAbsent();

        log.info("[Scraper] Creating container '{}' from image '{}'", containerName, containerImage);
        var created = dockerClient.createContainerCmd(containerImage)
                .withName(containerName)
                .withHostName(containerName)
                .withHostConfig(HostConfig.newHostConfig()
                        .withNetworkMode(networkName))
                .exec();

        dockerClient.startContainerCmd(created.getId()).exec();
        log.info("[Scraper] Container started — id: {}", created.getId());
    }

    public void stop() {
        ContainerStatus status = getStatus();

        if (status.state() == ContainerState.NOT_FOUND) {
            log.info("[Scraper] Container '{}' not found, nothing to stop", containerName);
            return;
        }

        if (status.state() == ContainerState.RUNNING) {
            log.info("[Scraper] Stopping container '{}'", containerName);
            dockerClient.stopContainerCmd(containerName).withTimeout(10).exec();
        }

        log.info("[Scraper] Removing container '{}'", containerName);
        dockerClient.removeContainerCmd(containerName).exec();
    }

    private void pullImageIfAbsent() {
        try {
            dockerClient.inspectImageCmd(containerImage).exec();
            log.info("[Scraper] Image '{}' already present locally", containerImage);
        } catch (NotFoundException e) {
            log.info("[Scraper] Pulling image '{}'…", containerImage);
            try {
                dockerClient.pullImageCmd(containerImage)
                        .start()
                        .awaitCompletion(120, java.util.concurrent.TimeUnit.SECONDS);
                log.info("[Scraper] Image pulled successfully");
            } catch (Exception ex) {
                throw new RuntimeException("Failed to pull scraper image: " + ex.getMessage(), ex);
            }
        }
    }
}
