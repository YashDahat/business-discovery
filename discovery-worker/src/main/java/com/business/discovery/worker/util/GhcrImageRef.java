package com.business.discovery.worker.util;

/**
 * Builds GHCR image references for generated projects.
 * GHCR requires the full ref (owner and repo) to be lowercase.
 */
public final class GhcrImageRef {

    private GhcrImageRef() {}

    /**
     * ghcr.io/&lt;owner&gt;/&lt;repo&gt;:&lt;tag&gt; — repo name parsed from the GitHub repo URL,
     * falling back to the provided slug when the URL is absent (e.g. dev runs).
     */
    public static String build(String owner, String repoUrl, String fallbackSlug, String tag) {
        String repo = repoName(repoUrl);
        if (repo == null || repo.isBlank()) repo = fallbackSlug;
        return "ghcr.io/" + owner.toLowerCase() + "/" + repo.toLowerCase() + ":" + sanitizeTag(tag);
    }

    /** Swaps the tag on an existing ref: ghcr.io/o/r:old → ghcr.io/o/r:new */
    public static String retag(String imageRef, String newTag) {
        int colon = imageRef.lastIndexOf(':');
        String base = colon > imageRef.lastIndexOf('/') ? imageRef.substring(0, colon) : imageRef;
        return base + ":" + sanitizeTag(newTag);
    }

    static String repoName(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) return null;
        String name = repoUrl.substring(repoUrl.lastIndexOf('/') + 1);
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    /** Docker tags: max 128 chars of [A-Za-z0-9_.-], must not start with '.' or '-'. */
    static String sanitizeTag(String tag) {
        String clean = tag.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (clean.startsWith(".") || clean.startsWith("-")) clean = "t" + clean;
        return clean.length() > 128 ? clean.substring(0, 128) : clean;
    }
}
