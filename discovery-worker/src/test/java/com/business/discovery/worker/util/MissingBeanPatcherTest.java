package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture is circuit-house attempt 2 (2026-07-12): PaymentService injected a RestTemplate
 * that no @Bean declared. Compiled clean on both sides, published an image, then
 * crash-looped on context refresh until the smoke gate timed out at 120s.
 */
class MissingBeanPatcherTest {

    @TempDir
    Path src;

    private static final String PAYMENT_SERVICE = """
            package com.circuithouse.service;

            import org.springframework.stereotype.Service;
            import org.springframework.web.client.RestTemplate;
            import com.circuithouse.repository.OrderRepository;

            @Service
            public class PaymentService {
                private final OrderRepository orderRepository;
                private final RestTemplate restTemplate;

                public PaymentService(OrderRepository orderRepository, RestTemplate restTemplate) {
                    this.orderRepository = orderRepository;
                    this.restTemplate = restTemplate;
                }
            }
            """;

    private static final String SECURITY_CONFIG = """
            package com.circuithouse.config;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            public class SecurityConfig {
            }
            """;

    @BeforeEach
    void fixtures() throws Exception {
        write("com/circuithouse/service/PaymentService.java", PAYMENT_SERVICE);
        write("com/circuithouse/config/SecurityConfig.java", SECURITY_CONFIG);
    }

    private void write(String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void suppliesTheRestTemplateBeanThatKilledCircuitHouse() throws Exception {
        assertThat(MissingBeanPatcher.fix(src)).isEqualTo(1);

        Path config = src.resolve("com/circuithouse/config/RestTemplateConfig.java");
        assertThat(config).exists();

        String content = Files.readString(config);
        // lands in the existing config package, inside the component scan
        assertThat(content).contains("package com.circuithouse.config;");
        assertThat(content).contains("@Configuration");
        assertThat(content).contains("@Bean");
        assertThat(content).contains("public RestTemplate restTemplate()");
        assertThat(content).contains("return new RestTemplate();");
        assertThat(content).contains("import org.springframework.web.client.RestTemplate;");
    }

    @Test
    void doesNothingWhenTheBeanIsAlreadyDeclared() throws Exception {
        write("com/circuithouse/config/AppConfig.java", """
                package com.circuithouse.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.client.RestTemplate;

                @Configuration
                public class AppConfig {
                    @Bean
                    public RestTemplate restTemplate() {
                        return new RestTemplate();
                    }
                }
                """);

        assertThat(MissingBeanPatcher.fix(src)).isZero();
        assertThat(src.resolve("com/circuithouse/config/RestTemplateConfig.java")).doesNotExist();
    }

    @Test
    void doesNothingWhenNobodyInjectsIt() throws Exception {
        Files.writeString(src.resolve("com/circuithouse/service/PaymentService.java"), """
                package com.circuithouse.service;
                import org.springframework.stereotype.Service;

                @Service
                public class PaymentService {
                    public void pay() {}
                }
                """);

        assertThat(MissingBeanPatcher.fix(src)).isZero();
        assertThat(src.resolve("com/circuithouse/config/RestTemplateConfig.java")).doesNotExist();
    }

    @Test
    void isIdempotentAcrossAttempts() throws Exception {
        assertThat(MissingBeanPatcher.fix(src)).isEqualTo(1);
        assertThat(MissingBeanPatcher.fix(src)).isZero();   // config now exists AND provides the bean
    }

    @Test
    void detectsFieldInjectionToo() throws Exception {
        Files.writeString(src.resolve("com/circuithouse/service/PaymentService.java"), """
                package com.circuithouse.service;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;

                @Service
                public class PaymentService {
                    private final RestTemplate restTemplate;
                }
                """);

        assertThat(MissingBeanPatcher.fix(src)).isEqualTo(1);
        assertThat(src.resolve("com/circuithouse/config/RestTemplateConfig.java")).exists();
    }
}
