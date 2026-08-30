package com.business.discovery.services.cline;

import com.business.discovery.model.ArchitectBrief;
import com.business.discovery.model.ContainerTask;
import com.business.discovery.model.PlatformUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the system-prompt preamble handed to Cline for each chat turn. Two distinct pieces:
 *
 *  1. A STATIC persona ({@link #PERSONA}) — how Cline thinks and communicates. Injected at this
 *     gateway so it applies consistently to every interaction, independent of any per-repo config.
 *  2. PER-CLIENT context assembled from this business's real data — the ArchitectBrief (the original
 *     plan) plus recent project activity (latest build/PR, any in-flight change request). The fuller
 *     conversation history is layered on separately by {@link ClineChatService} (recent window +
 *     semantic recall), and what's ACTUALLY implemented is discoverable live via the sandbox read tools.
 *
 * Guardrail: proactive suggestions stay informational — Cline surfaces ideas but never auto-executes
 * them; acting on a suggestion is a separate, explicitly-confirmed change like any other.
 */
@Component
public class ProjectContextBuilder {

    /**
     * Static global persona — the senior-consultant character. Kept constant (not per-client) so the
     * voice and the "suggest, don't act" behaviour are identical across every business.
     */
    private static final String PERSONA = """
            == WHO YOU ARE ==
            You are a senior digital consultant and engineer working on behalf of this business. Beyond \
            completing the specific request accurately, you actively look for ways to help the business run \
            more efficiently or stand out more creatively — but you do NOT act on these unprompted. When you \
            notice something genuinely useful (a common feature this type of business is missing, an \
            inefficient pattern, a creative differentiation opportunity), offer it briefly at the END of your \
            response as an optional suggestion, never as an executed change. Limit yourself to 1–2 suggestions \
            per interaction, and only when they are specific to what you have actually observed about THIS \
            business — not generic advice. Speak plainly and avoid jargon unless the client uses it first.
            """;

    public String build(PlatformUser user, ArchitectBrief brief, ContainerTask task) {
        StringBuilder sb = new StringBuilder();

        sb.append(PERSONA).append("\n");

        sb.append("== USER ==\n");
        if (user != null) {
            sb.append("Name: ").append(nz(user.getName(), "Unknown")).append("\n");
            sb.append("Email: ").append(nz(user.getEmail(), "n/a")).append("\n");
            sb.append("Role: ").append(user.getRole() != null ? user.getRole().name() : "n/a").append("\n");
        } else {
            sb.append("Unauthenticated / system user\n");
        }

        // PER-CLIENT context: the ArchitectBrief is the ORIGINAL plan — the baseline picture of what this
        // business is and does. What's actually built now may differ (see PROJECT ACTIVITY + live repo).
        sb.append("\n== PROJECT (original brief) ==\n");
        sb.append("Category: ").append(nz(brief.getBusinessCategory(), "Local business")).append("\n");
        sb.append("Location: ").append(nz(brief.getLocation(), "India")).append("\n");
        appendLine(sb, "Website type", brief.getWebsiteType() != null ? brief.getWebsiteType().name() : null);
        if (task != null) {
            sb.append("Repository: ").append(nz(task.getGithubRepoUrl(), "not yet created")).append("\n");
            sb.append("Branch: ").append(nz(task.getGithubBranch(), "main")).append("\n");
        }
        appendMap(sb, "Recommended tech stack", brief.getRecommendedTechStack());
        appendList(sb, "Recommended pages", brief.getRecommendedPages());
        appendList(sb, "Must-have features", brief.getMustHaveFeatures());
        appendList(sb, "Nice-to-have features", brief.getNiceToHaveFeatures());
        appendList(sb, "SEO keywords", brief.getSeoKeywords());
        appendLine(sb, "Design direction", brief.getDesignDirection());
        appendLine(sb, "Color scheme", brief.getColorScheme());
        appendLine(sb, "Tone", brief.getTone());

        // What's been built / asked recently — so suggestions don't repeat old ground or contradict a
        // past decision. Conversation history is added separately (recent + semantic recall).
        sb.append("\n== PROJECT ACTIVITY ==\n");
        if (task != null) {
            appendLine(sb, "Latest build status", task.getStatus() != null ? task.getStatus().name() : null);
            appendLine(sb, "Latest pull request", task.getGithubPrUrl());
        }
        appendLine(sb, "Change request currently in flight", brief.getRequestedChanges());
        sb.append("To see what is ACTUALLY implemented right now (not just the original plan above), ")
          .append("explore the live repo with list_files / read_file.\n");

        // How to work — kept tight on purpose: this is sent every turn, so it must save far more tokens
        // (wasted tool calls, whole-file reads, re-reads) than it costs.
        sb.append("\n== OPERATING METHOD (be economical and accurate) ==\n")
          .append("- Locate before reading: use list_files, then run_command with grep/find to find the ")
          .append("exact spot. Read ONLY the files you need; don't read large files in full and don't ")
          .append("re-read a file you just wrote or edited.\n")
          .append("- Edit surgically: prefer edit_file with a unique snippet over rewriting a whole file.\n")
          .append("- Plan the few steps you need, then act; don't repeat tool calls or re-explore ground ")
          .append("you already covered this turn.\n")
          .append("- Verify from tool output, never assume: confirm the branch, the file contents, and the ")
          .append("build result before saying something is done. If run_command fails, read the error and ")
          .append("fix the cause — don't retry blindly.\n")
          .append("- After changing code, build/test it (npm run build, ./mvnw compile, run the script) ")
          .append("before commit_and_push.\n")
          .append("- Keep replies short: answer the question, don't echo file contents or restate the whole ")
          .append("plan back to the user.\n");

        sb.append("\n== INSTRUCTIONS ==\n");
        sb.append("You are Cline, assisting with this project. Answer using the project context above. ")
          .append("You can also act on this project through tools (the project is fixed by the session — ")
          .append("you cannot target another project):\n")
          .append("- Execution sandbox (repo cloned into a container with Python, TypeScript/tsx, Node, ")
          .append("Maven): list_files, read_file, checkout_branch, pull_latest, write_file, edit_file, ")
          .append("run_command, commit_and_push.\n")
          .append("- Repo lifecycle: repo_status, create_repo, open_pull_request. Run a demo: run_demo.\n")
          .append("- Update the project brief: update_architect_brief. Research the web: web_search, ")
          .append("web_extract, web_crawl, web_map.\n")
          .append("RULES:\n")
          .append("1. If the project has no repository yet (repo_status.hasRepo = false) and the user wants ")
          .append("changes, ASK them to confirm before creating one, then call create_repo. Do not create a ")
          .append("repo without confirmation.\n")
          .append("2. If the user names a specific branch, call checkout_branch FIRST and confirm the ")
          .append("returned branch — the workspace otherwise defaults to the working branch, and commit/PR ")
          .append("follow whatever is checked out. To change code: read_file first, then write_file (full ")
          .append("content) or edit_file (a unique snippet). When useful, run_command to build/test before ")
          .append("committing (e.g. npm run build, ./mvnw compile, python/npx tsx scripts) and report failures.\n")
          .append("3. Changes live only in the sandbox until commit_and_push (working branch), then ")
          .append("open_pull_request into the default branch. NEVER commit to the default branch directly.\n")
          .append("4. run_demo runs the last generated/published build — it does NOT reflect your ")
          .append("uncommitted edits; say so if the user expects to see new changes live.\n")
          .append("5. PROACTIVE SUGGESTIONS stay informational: your 1–2 optional suggestions go at the end, ")
          .append("clearly marked as suggestions, and are NEVER acted on in the same turn. Build a suggestion ")
          .append("only if the client explicitly asks for it in a later turn — the same confirmation as any ")
          .append("change request. Being helpful means surfacing the idea, not quietly expanding scope.\n")
          .append("Make changes only when the user asks; explain your plan first for anything non-trivial.\n");

        return sb.toString();
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private void appendList(StringBuilder sb, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            sb.append(label).append(":\n");
            values.forEach(v -> sb.append("  - ").append(v).append("\n"));
        }
    }

    private void appendMap(StringBuilder sb, String label, Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            sb.append(label).append(":\n");
            values.forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
        }
    }

    private String nz(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
