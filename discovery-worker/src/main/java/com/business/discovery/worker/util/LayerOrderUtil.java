package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileEntry;

/**
 * Determines the generation order for backend and frontend files.
 *
 * Backend:  Entity → DTO → Util/Exception/Mapper → Repository → Service → Controller → Config
 * Frontend: Types → Constants → Utils → API Services → Hooks → Components → Pages → App/Router
 *
 * Lower number = generated first. Files sorted ascending before the generation loop so every
 * file is written after all files it depends on — OrderService always sees OrderResponse on disk.
 *
 * Layer is inferred from file path. FileSpec.layer from ARCHITECTURE.json is not used because
 * the planning LLM uses inconsistent values ("SERVICE_LAYER" vs "SERVICE" etc.).
 */
public final class LayerOrderUtil {

    private LayerOrderUtil() {}

    // ── Backend ───────────────────────────────────────────────────────────

    public static int backendPriority(FileEntry entry) {
        String path = entry.path().toLowerCase();
        String file = fileName(path);

        if (path.contains("/entity/") || path.contains("/model/")
                || file.endsWith("entity.java") || file.endsWith("model.java")) return 10;

        if (path.contains("/dto/")
                || file.endsWith("dto.java") || file.endsWith("request.java")
                || file.endsWith("response.java") || file.endsWith("vo.java")) return 20;

        if (path.contains("/exception/") || path.contains("/util/")
                || path.contains("/constant/") || path.contains("/mapper/")
                || path.contains("/helper/") || path.contains("/enums/")
                || file.endsWith("exception.java") || file.endsWith("util.java")
                || file.endsWith("utils.java") || file.endsWith("constants.java")
                || file.endsWith("mapper.java") || file.endsWith("enum.java")) return 30;

        if (path.contains("/repository/") || file.endsWith("repository.java")) return 40;

        if (path.contains("/service/")
                || file.endsWith("service.java") || file.endsWith("serviceimpl.java")) return 50;

        if (path.contains("/controller/") || file.endsWith("controller.java")) return 60;

        if (path.contains("/config/") || path.contains("/configuration/") || path.contains("/security/")
                || file.endsWith("config.java") || file.endsWith("configuration.java")) return 70;

        return 99;
    }

    public static String backendLayerName(FileEntry entry) {
        return switch (backendPriority(entry)) {
            case 10 -> "ENTITY";
            case 20 -> "DTO";
            case 30 -> "UTIL";
            case 40 -> "REPOSITORY";
            case 50 -> "SERVICE";
            case 60 -> "CONTROLLER";
            case 70 -> "CONFIG";
            default -> "UNKNOWN";
        };
    }

    // ── Frontend ──────────────────────────────────────────────────────────

    public static int frontendPriority(FileEntry entry) {
        String path = entry.path().toLowerCase();
        String file = fileName(path);

        if (path.contains("/types/") || path.contains("/interfaces/")
                || file.endsWith(".types.ts") || file.endsWith(".interface.ts")
                || file.equals("types.ts")) return 10;

        if (path.contains("/constants/")
                || file.endsWith(".constants.ts") || file.equals("constants.ts")) return 20;

        if (path.contains("/utils/") || path.contains("/helpers/") || path.contains("/lib/")
                || file.endsWith(".util.ts") || file.endsWith(".utils.ts")
                || file.endsWith("utils.ts")) return 30;

        // API service layer — makes all HTTP calls; sits before hooks which consume it
        if (path.contains("/services/") || path.contains("/api/")
                || file.endsWith("service.ts") || file.endsWith("api.ts")
                || file.equals("client.ts")) return 40;

        if (path.contains("/hooks/") || file.startsWith("use")) return 50;

        if (path.contains("/components/") || file.endsWith(".component.tsx")) return 60;

        if (path.contains("/pages/") || path.contains("/views/")
                || file.endsWith(".page.tsx")) return 70;

        if (file.equals("app.tsx") || file.equals("app.ts")
                || file.equals("main.tsx") || file.equals("index.tsx")
                || file.equals("router.tsx") || file.contains("router")) return 80;

        return 99;
    }

    public static String frontendLayerName(FileEntry entry) {
        return switch (frontendPriority(entry)) {
            case 10 -> "TYPE";
            case 20 -> "CONSTANT";
            case 30 -> "UTIL";
            case 40 -> "SERVICE";
            case 50 -> "HOOK";
            case 60 -> "COMPONENT";
            case 70 -> "PAGE";
            case 80 -> "APP";
            default -> "UNKNOWN";
        };
    }

    // ── Internal ──────────────────────────────────────────────────────────

    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
