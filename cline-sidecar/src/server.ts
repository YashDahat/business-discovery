import express from "express";
// NOTE: the Cline SDK is TS-first; `Agent` is the documented entrypoint.
// See https://docs.cline.bot/sdk/quickstart and cline/cline sdk/README.md.
import { Agent } from "@cline/sdk";
import { buildMcpTools, type McpBridge } from "./mcp/clineMcpBridge";

const PORT = Number(process.env.PORT ?? 8091);

// Cline uses its NATIVE gemini provider pointed at the Spring Boot proxy (host root; Cline appends
// /v1beta). No OpenAI-compat shim → native function-calling works (MCP tools). The sidecar holds only
// the internal service token (sent as x-goog-api-key), never the real Gemini key.
const GEMINI_BASE_URL = process.env.CLINE_GEMINI_BASE_URL ?? "http://app:8090";
const INTERNAL_TOKEN = process.env.CLINE_INTERNAL_TOKEN ?? "";
const MODEL_ID = process.env.CLINE_MODEL ?? "gemini-2.5-pro";
// Native Gemini provider (@cline/llms ProviderId.GEMINI = "gemini").
const PROVIDER_ID = process.env.CLINE_PROVIDER_ID ?? "gemini";

// Base URL for Spring Boot's internal MCP endpoints (two-layer auth). The MCP grant arrives per turn
// on /chat; kept here so /mcp/selftest can prove the sidecar → Spring Boot auth chain end to end.
const SPRING_BASE_URL = process.env.SPRING_BASE_URL ?? "http://app:8090";
let lastGrant: string | null = null;

const app = express();
app.use(express.json({ limit: "2mb" }));

app.get("/health", (_req, res) => {
  res.json({ status: "ok", model: MODEL_ID, provider: PROVIDER_ID, geminiBaseUrl: GEMINI_BASE_URL });
});

/**
 * One Cline turn.
 * Body: { sessionId: string, message: string, context: string, grant?: string }
 * Returns: { reply: string, usage?: {...} }
 *
 * A fresh Agent is built per turn, seeded with `context` (user + project + latest-5 history). When a
 * scoped `grant` is present, our MCP server is connected and its tools are attached so Cline can call
 * back into Spring Boot (e.g. get_project_context) — every tool call carries the grant, so it is
 * authorized as the real user and pinned to this project. Spring Boot's chat_memory is the source of
 * truth for history.
 */
app.post("/chat", async (req, res) => {
  const { sessionId, message, context, grant } = req.body ?? {};
  if (!message || typeof message !== "string") {
    res.status(400).json({ error: "message is required" });
    return;
  }
  if (typeof grant === "string" && grant) lastGrant = grant;

  // Attach MCP tools for this turn (grant-scoped). If MCP setup fails, continue without tools so the
  // chat still answers rather than hard-failing.
  let bridge: McpBridge | null = null;
  let tools: unknown[] = [];
  if (typeof grant === "string" && grant) {
    try {
      bridge = await buildMcpTools({ grant, internalToken: INTERNAL_TOKEN, springBaseUrl: SPRING_BASE_URL });
      tools = bridge.tools;
      console.log(`[cline-sidecar] session=${sessionId} attached ${tools.length} MCP tool(s):`,
        (tools as any[]).map(t => t?.name).join(", "));
    } catch (e) {
      console.error(`[cline-sidecar] MCP setup FAILED (session=${sessionId}) — continuing WITHOUT tools:`,
        e instanceof Error ? (e.stack ?? e.message) : e);
    }
  } else {
    console.warn(`[cline-sidecar] session=${sessionId} has NO grant — Cline gets no MCP tools this turn`);
  }

  const systemPrompt =
    (context ?? "") +
    "\n\nYou have tools to act on THIS project (the project is fixed by the session — you cannot target " +
    "another project):\n" +
    "- get_project_context: read the current user and the project.\n" +
    "- update_architect_brief: apply changes to the project brief. Pass only the fields that change " +
    "(designDirection, colorScheme, tone, mustHaveFeatures, niceToHaveFeatures, recommendedPages, " +
    "seoKeywords). This persists the brief; it does NOT deploy or regenerate the site.\n" +
    "When the user asks to change the project, call update_architect_brief with just the changed fields, " +
    "then confirm what you changed.\n" +
    "\nYou also have web research tools (Tavily) for looking things up on the live web:\n" +
    "- web_search: search the web for current facts, research, competitor/industry info.\n" +
    "- web_extract: fetch clean content from one or more specific URLs you already have.\n" +
    "- web_crawl: crawl a site from a starting URL, following links to gather content.\n" +
    "- web_map: discover a site's URL structure without extracting content.\n" +
    "Prefer web_search for open questions, then web_extract on the most relevant URLs. Cite the URLs " +
    "you used in your answer.\n" +
    "\nYou have a real execution environment for THIS project (the repo is cloned into a per-project " +
    "sandbox container with Python, TypeScript/tsx, Node and Maven; the project is fixed by the session):\n" +
    "- repo_status: check whether a repo exists yet and its branches.\n" +
    "- create_repo: create the repo when none exists.\n" +
    "- list_files / read_file: browse and read files in the workspace.\n" +
    "- checkout_branch: switch to a git branch (createNew=true to start a new one). Do this FIRST when " +
    "the user names a specific branch — the workspace defaults to the working branch otherwise, and all " +
    "edits/commits then follow the checked-out branch. Verify the returned branch before claiming success.\n" +
    "- pull_latest: pull the current branch's newest commits from origin (do this right after checkout).\n" +
    "- write_file: create/overwrite a file with complete content. edit_file: replace a unique snippet.\n" +
    "- run_command: run shell commands in the workspace (install deps, run scripts, build/test) — e.g. " +
    "python, npx tsx, npm install, npm run build, ./mvnw compile.\n" +
    "- commit_and_push: stage + commit + push to the working branch. open_pull_request: PR it for review.\n" +
    "- stop_sandbox: stop/reset the sandbox (uncommitted changes are lost; it's recreated on next use).\n" +
    "- run_demo: start a demo of the last generated build.\n" +
    "RULES: (1) If repo_status shows no repo and the user wants changes, ASK them to confirm, then call " +
    "create_repo — never create a repo silently. (2) To edit: read_file first, then write_file (full " +
    "content) or edit_file (unique snippet). (3) When it makes sense, run_command to build/test your " +
    "changes before committing, and report failures. (4) Edits are local to the sandbox until " +
    "commit_and_push (working branch), then open_pull_request — never commit to the default branch " +
    "directly. (5) run_demo reflects the last generated build, not your uncommitted edits — say so if the " +
    "user expects to see changes live.";

  try {
    const agent = new Agent({
      providerId: PROVIDER_ID,
      modelId: MODEL_ID,
      // Custom OpenAI-compatible endpoint + auth (AgentRuntimeConfigWithProvider.baseUrl / apiKey).
      apiKey: INTERNAL_TOKEN,
      baseUrl: GEMINI_BASE_URL,
      systemPrompt,
      tools: tools as never,
    });

    // AgentRunResult exposes `outputText` (+ usage); there is no `.text` field.
    const result = await agent.run(message);
    const reply = (result?.outputText ?? "").toString();

    res.json({ reply, usage: result?.usage ?? null });
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    console.error(`[cline-sidecar] chat failed (session=${sessionId}):`, msg);
    res.status(500).json({ error: "cline_run_failed", detail: msg });
  } finally {
    if (bridge) await bridge.dispose().catch(() => {});
  }
});

/**
 * Proves the two-layer MCP auth chain from the sidecar: calls Spring Boot's /internal/mcp/context with
 * X-Internal-Token (service auth) + X-Mcp-Grant (scoped grant). Grant comes from the body or the last
 * /chat turn. 200 with the resolved user+brief means both layers validated end to end.
 */
app.post("/mcp/selftest", async (req, res) => {
  const grant = (req.body?.grant as string) ?? lastGrant;
  if (!grant) {
    res.status(400).json({ error: "no grant available — pass { grant } or run a /chat turn first" });
    return;
  }
  try {
    const upstream = await fetch(`${SPRING_BASE_URL}/internal/mcp/context`, {
      headers: { "X-Internal-Token": INTERNAL_TOKEN, "X-Mcp-Grant": grant },
    });
    const text = await upstream.text();
    res.status(upstream.status).type("application/json").send(text);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    res.status(502).json({ error: "spring_boot_unreachable", detail: msg });
  }
});

app.listen(PORT, () => {
  console.log(`[cline-sidecar] listening on :${PORT} → gemini ${GEMINI_BASE_URL} (provider ${PROVIDER_ID}, model ${MODEL_ID})`);
});
