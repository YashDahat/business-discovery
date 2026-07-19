package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.ArchitectureSpec;
import com.business.discovery.worker.service.llm.FeatureSpec;
import com.business.discovery.worker.service.llm.FileSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ServicePlanPrunerTest {

    private static final String CLAIMED = "frontend/src/services/orderService.ts";
    private static final String UNCLAIMED = "frontend/src/services/adminOrderService.ts";
    private static final String LOCAL = "frontend/src/services/local/cartService.ts";
    private static final String PAGE = "frontend/src/pages/AdminOrdersPage.tsx";

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        ArchitectureSpec spec = new ArchitectureSpec();
        spec.setFiles(new ArrayList<>(List.of(
                file(CLAIMED, null),
                file(UNCLAIMED, null),
                file(LOCAL, null),
                file(PAGE, new ArrayList<>(List.of(UNCLAIMED, CLAIMED))))));
        FeatureSpec feature = FeatureSpec.builder()
                .featureName("order-admin")
                .filePaths(new ArrayList<>(List.of(UNCLAIMED, CLAIMED, PAGE)))
                .build();
        spec.setFeatures(new ArrayList<>(List.of(feature)));
        ArchitectureJsonUtil.write(workspace, spec);
    }

    private static FileSpec file(String path, List<String> importsFrom) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path)
                .fileType("FRONTEND")
                .importsFrom(importsFrom)
                .build();
    }

    @Test
    void stripsUnclaimedServiceEverywhere() throws Exception {
        List<String> stripped = ServicePlanPruner.prune(workspace, Set.of(CLAIMED));

        assertThat(stripped).containsExactly(UNCLAIMED);
        ArchitectureSpec after = ArchitectureJsonUtil.read(workspace);
        assertThat(after.getFiles()).extracting(FileSpec::getFilePath)
                .containsExactly(CLAIMED, LOCAL, PAGE);
        FileSpec page = after.getFiles().stream()
                .filter(f -> PAGE.equals(f.getFilePath())).findFirst().orElseThrow();
        assertThat(page.getImportsFrom()).containsExactly(CLAIMED);
        assertThat(after.getFeatures().get(0).getFilePaths()).containsExactly(CLAIMED, PAGE);
    }

    @Test
    void keepsClaimedAndLocalServices() {
        ServicePlanPruner.prune(workspace, Set.of(CLAIMED));
        // second run with nothing new to strip is a no-op
        assertThat(ServicePlanPruner.prune(workspace, Set.of(CLAIMED))).isEmpty();
    }

    @Test
    void noArchitectureJsonIsNoOp(@TempDir Path empty) {
        assertThat(ServicePlanPruner.prune(empty, Set.of(CLAIMED))).isEmpty();
    }
}
