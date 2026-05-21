package com.business.discovery.services.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContainerImageService {

    private final DockerClient dockerClient;

    @Value("${docker.worker.image}")
    private String workerImage;

    // ─── Pull image from registry ─────────────────────────

    public void pullImage() {
        log.info("Pulling worker image: {}", workerImage);

        try {
            dockerClient.pullImageCmd(workerImage)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion(5, TimeUnit.MINUTES);

            // PullImageResultCallback does not always propagate registry 404s —
            // verify the image actually landed before returning
            if (!imageExistsLocally()) {
                throw new RuntimeException(
                        "Image pull completed but image not found locally: " + workerImage
                        + " — build it first with: docker build -t " + workerImage + " .");
            }

            log.info("Successfully pulled image: {}", workerImage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Image pull interrupted: " + workerImage, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to pull image: " + workerImage, e);
        }
    }

    // ─── Check if image exists locally ───────────────────

    public boolean imageExistsLocally() {
        try {
            List<Image> images = dockerClient.listImagesCmd()
                    .withImageNameFilter(workerImage)
                    .exec();

            boolean exists = images != null && !images.isEmpty();
            log.info("Image {} exists locally: {}", workerImage, exists);
            return exists;

        } catch (Exception e) {
            log.warn("Failed to check image existence: {}", e.getMessage());
            return false;
        }
    }

    // ─── Ensure image is available ────────────────────────
    // Pull only if not present locally — avoids unnecessary pull on every run

    public void ensureImageAvailable() {
        if (!imageExistsLocally()) {
            log.info("Image not found locally — pulling from registry");
            pullImage();
        } else {
            log.info("Image already available locally: {}", workerImage);
        }
    }

    // ─── Force refresh image ──────────────────────────────
    // Pull latest even if exists locally — use before each deployment cycle

    public void refreshImage() {
        log.info("Force refreshing image: {}", workerImage);
        pullImage();
    }

    // ─── Remove dangling images ───────────────────────────
    // Clean up old worker images to reclaim disk space

    public void removeDanglingImages() {
        try {
            dockerClient.listImagesCmd()
                    .withDanglingFilter(true)
                    .exec()
                    .forEach(image -> {
                        try {
                            dockerClient.removeImageCmd(image.getId())
                                    .withForce(false)
                                    .exec();
                            log.info("Removed dangling image: {}", image.getId());
                        } catch (Exception e) {
                            log.warn("Could not remove image {}: {}", image.getId(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Failed to remove dangling images: {}", e.getMessage());
        }
    }
}