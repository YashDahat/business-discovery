package com.business.discovery.worker.util;

public final class SlugUtil {

    private SlugUtil() {}

    public static String toSlug(String businessName) {
        if (businessName == null) return "business";
        String slug = businessName.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("^[0-9]+", "");
        return slug.isBlank() ? "business" : slug;
    }

    /** PascalCase class name from a business name, e.g. "MultiFit Aundh" → "MultifitAundh". */
    public static String toClassName(String businessName) {
        if (businessName == null) return "Business";
        String[] words = businessName.trim().split("[\\s\\-_]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            String clean = w.replaceAll("[^a-zA-Z0-9]", "");
            if (!clean.isEmpty()) sb.append(Character.toUpperCase(clean.charAt(0)))
                                    .append(clean.substring(1).toLowerCase());
        }
        String result = sb.toString().replaceAll("^[0-9]+", "");
        return result.isBlank() ? "Business" : result;
    }
}
