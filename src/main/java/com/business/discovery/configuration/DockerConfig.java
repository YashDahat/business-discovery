package com.business.discovery.configuration;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.api.DockerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class DockerConfig {

    @Value("${docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${docker.worker.image}")
    private String workerImage;

    @Value("${docker.pool.max-size:2}")
    private int maxPoolSize;

    @Value("${docker.container.max-lifetime-minutes:30}")
    private int maxLifetimeMinutes;

    @Bean
    public DockerClient dockerClient() {
        log.info("DOCKER_HOST from ENV: {}", System.getenv("DOCKER_HOST"));
        log.info("DOCKER_HOST from @Value: {}", dockerHost);

        // Force TCP — ignore any Unix socket fallback
        String effectiveHost = dockerHost.startsWith("tcp://")
                ? dockerHost
                : "tcp://docker-proxy:2375";

        log.info("Effective Docker host: {}", effectiveHost);

        DockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(effectiveHost)
                .withDockerTlsVerify(false)
                .build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .maxConnections(10)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(60))
                .build();

        DockerClient client = DockerClientImpl.getInstance(config, httpClient);

        try {
            client.pingCmd().exec();
            log.info("Docker client connected successfully — host: {}", effectiveHost);
        } catch (Exception e) {
            log.warn("Docker daemon not reachable: {}", e.getMessage());
        }

        return client;
    }
}