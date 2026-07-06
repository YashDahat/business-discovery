package com.business.discovery.worker.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApiInventoryTest {

    @TempDir
    Path src;

    // Fixtures mirror real multifit-aundh generated code — the regularity we rely on.

    @BeforeEach
    void fixtures() throws Exception {
        write("com/x/controller/TrainerController.java", """
                package com.x.controller;
                @RestController
                @RequestMapping("/api/v1")
                public class TrainerController {
                    @GetMapping("/trainers")
                    public ResponseEntity<List<TrainerDto>> getAllTrainers() { return null; }
                }
                """);
        write("com/x/controller/AdminContentController.java", """
                package com.x.controller;
                @RestController
                @RequestMapping("/api/v1/admin")
                public class AdminContentController {
                    @PostMapping("/trainers")
                    public ResponseEntity<TrainerDto> createTrainer(@Valid @RequestBody CreateTrainerRequest request) { return null; }
                    @DeleteMapping("/trainers/{id}")
                    public ResponseEntity<Void> deleteTrainer(@PathVariable UUID id) { return null; }
                }
                """);
        write("com/x/dto/TrainerDto.java", """
                package com.x.dto;
                import lombok.Data;
                @Data
                public class TrainerDto {
                    private UUID id;
                    private String name;
                    @NotBlank
                    private String specialization;
                    private List<String> certifications;
                    private BookingStatus status;
                }
                """);
        write("com/x/dto/CreateTrainerRequest.java", """
                package com.x.dto;
                public class CreateTrainerRequest {
                    @NotBlank
                    private String name;
                    private BigDecimal hourlyRate;
                }
                """);
        write("com/x/model/BookingStatus.java", """
                package com.x.model;
                public enum BookingStatus {
                    CONFIRMED,
                    CANCELLED_BY_USER
                }
                """);
    }

    private void write(String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void extractsEndpointsWithBasePathAndTypes() {
        ApiInventory inv = ApiInventory.extract(src);

        assertThat(inv.endpoints()).hasSize(3);

        ApiInventory.Endpoint list = find(inv, "GET", "/api/v1/trainers");
        assertThat(list.responseType()).isEqualTo("TrainerDto");
        assertThat(list.responseIsList()).isTrue();
        assertThat(list.handlerName()).isEqualTo("getAllTrainers");

        ApiInventory.Endpoint create = find(inv, "POST", "/api/v1/admin/trainers");
        assertThat(create.requestType()).isEqualTo("CreateTrainerRequest");
        assertThat(create.responseType()).isEqualTo("TrainerDto");
        assertThat(create.responseIsList()).isFalse();

        ApiInventory.Endpoint delete = find(inv, "DELETE", "/api/v1/admin/trainers/{id}");
        assertThat(delete.responseType()).isNull(); // Void
        assertThat(delete.pathParams()).hasSize(1);
        assertThat(delete.pathParams().get(0).name()).isEqualTo("id");
        assertThat(delete.pathParams().get(0).javaType()).isEqualTo("UUID");
    }

    @Test
    void extractsDtoFieldsWithRequiredness() {
        ApiInventory inv = ApiInventory.extract(src);

        ApiInventory.TypeDef dto = inv.types().get("TrainerDto");
        assertThat(dto.fields()).extracting(ApiInventory.Field::name)
                .containsExactly("id", "name", "specialization", "certifications", "status");
        assertThat(fieldOf(dto, "specialization").required()).isTrue();
        assertThat(fieldOf(dto, "name").required()).isFalse();
        assertThat(fieldOf(dto, "certifications").javaType()).isEqualTo("List<String>");
    }

    @Test
    void extractsEnumConstants() {
        ApiInventory inv = ApiInventory.extract(src);

        ApiInventory.TypeDef status = inv.types().get("BookingStatus");
        assertThat(status.isEnum()).isTrue();
        assertThat(status.enumConstants()).containsExactly("CONFIRMED", "CANCELLED_BY_USER");
    }

    private static ApiInventory.Endpoint find(ApiInventory inv, String method, String path) {
        return inv.endpoints().stream()
                .filter(e -> e.httpMethod().equals(method) && e.path().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError(method + " " + path + " not extracted"));
    }

    private static ApiInventory.Field fieldOf(ApiInventory.TypeDef def, String name) {
        return def.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
