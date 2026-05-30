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
}
