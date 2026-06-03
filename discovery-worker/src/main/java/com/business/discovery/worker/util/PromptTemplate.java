package com.business.discovery.worker.util;

/**
 * Renders a prompt template by substituting {{key}} placeholders.
 * All occurrences of {{key}} are replaced — null values become empty strings.
 *
 * Usage:
 *   PromptTemplate.from(PromptLoader.load("user/arch_spec.txt"))
 *       .with("businessName", brief.businessName())
 *       .with("category",     brief.category())
 *       .render();
 */
public final class PromptTemplate {

    private String content;

    private PromptTemplate(String content) {
        this.content = content;
    }

    public static PromptTemplate from(String template) {
        return new PromptTemplate(template);
    }

    public PromptTemplate with(String key, String value) {
        content = content.replace("{{" + key + "}}", value != null ? value : "");
        return this;
    }

    public String render() {
        return content;
    }
}
