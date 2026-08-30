import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

/**
 * Standalone MCP stdio server exposing Cline-callable tools that act on our system — every tool call
 * goes back through Spring Boot's /internal/mcp/** endpoints with the two-layer auth headers:
 *
 *   X-Internal-Token — proves the caller is our sidecar (service auth, Layer 1)
 *   X-Mcp-Grant      — the short-lived, brief-pinned, user-scoped grant (authorization, Layer 2)
 *
 * The grant is provided per-process via MCP_GRANT (a fresh server is launched per Cline turn with that
 * turn's grant). Tools: get_project_context (read) + update_architect_brief (write, persist-only).
 *
 * Runnable standalone (`tsx src/mcp/projectContextServer.ts`); this is the exact call an MCP tool makes.
 */
const SPRING_BASE_URL = process.env.SPRING_BASE_URL ?? "http://app:8090";
const INTERNAL_TOKEN = process.env.MCP_INTERNAL_TOKEN ?? "";
const GRANT = process.env.MCP_GRANT ?? "";

async function callSpringBoot(path: string, method = "GET", body?: unknown): Promise<string> {
  const res = await fetch(`${SPRING_BASE_URL}${path}`, {
    method,
    headers: {
      "X-Internal-Token": INTERNAL_TOKEN,
      "X-Mcp-Grant": GRANT,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`Spring Boot ${path} returned HTTP ${res.status}: ${text}`);
  }
  return text;
}

const server = new McpServer({ name: "discovery-mcp", version: "0.1.0" });

server.registerTool(
  "get_project_context",
  {
    title: "Get project context",
    description:
      "Returns the current user and the project (brief) this session is scoped to. Read-only; " +
      "the project is fixed by the session grant and cannot be changed via arguments.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/context");
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "update_architect_brief",
  {
    title: "Update project brief",
    description:
      "Apply changes to THIS project's architect brief. Pass only the fields that change. The project " +
      "is fixed by the session and cannot be targeted via arguments. This PERSISTS the brief; it does " +
      "not deploy or regenerate the site (that is a separate, explicit step).",
    inputSchema: {
      designDirection: z.string().optional(),
      colorScheme: z.string().optional(),
      tone: z.string().optional(),
      mustHaveFeatures: z.array(z.string()).optional(),
      niceToHaveFeatures: z.array(z.string()).optional(),
      recommendedPages: z.array(z.string()).optional(),
      seoKeywords: z.array(z.string()).optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/brief", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

// --- Tavily web capabilities (proxied through Spring Boot; the Tavily key stays server-side) ---
// These are NOT scoped to the project — they are general web research/reading tools.

server.registerTool(
  "web_search",
  {
    title: "Web search (Tavily)",
    description:
      "Search the web and get an answer plus ranked results (title, url, content, score). Use for " +
      "current facts, research, competitor/industry lookups. Returns Tavily's raw JSON.",
    inputSchema: {
      query: z.string(),
      searchDepth: z.enum(["basic", "advanced"]).optional(),
      topic: z.enum(["general", "news"]).optional(),
      maxResults: z.number().int().positive().optional(),
      includeAnswer: z.boolean().optional(),
      includeRawContent: z.boolean().optional(),
      includeDomains: z.array(z.string()).optional(),
      excludeDomains: z.array(z.string()).optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/web/search", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "web_extract",
  {
    title: "Extract page content (Tavily)",
    description:
      "Fetch and extract clean content (and optionally images) from one or more specific URLs. Use " +
      "when you already have URLs and want their full readable content.",
    inputSchema: {
      urls: z.array(z.string()).min(1),
      extractDepth: z.enum(["basic", "advanced"]).optional(),
      includeImages: z.boolean().optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/web/extract", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "web_crawl",
  {
    title: "Crawl a website (Tavily)",
    description:
      "Crawl a site starting from a URL, following links to gather page content. Use to explore a " +
      "whole site/section. Optionally guide it with natural-language instructions.",
    inputSchema: {
      url: z.string(),
      maxDepth: z.number().int().positive().optional(),
      limit: z.number().int().positive().optional(),
      instructions: z.string().optional(),
      extractDepth: z.enum(["basic", "advanced"]).optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/web/crawl", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "web_map",
  {
    title: "Map a website (Tavily)",
    description:
      "Discover a site's structure (the list of URLs) from a starting URL, without extracting content. " +
      "Use to understand a site's layout before extracting or crawling specific pages.",
    inputSchema: {
      url: z.string(),
      maxDepth: z.number().int().positive().optional(),
      limit: z.number().int().positive().optional(),
      instructions: z.string().optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/web/map", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

// --- Repo tools: read / edit / run THIS project's generated code repo (proxied through Spring Boot;
// the repo is fixed by the session — resolved server-side from the grant, never from arguments). ---

server.registerTool(
  "repo_status",
  {
    title: "Repo status",
    description:
      "Report the project's GitHub repo: whether it exists yet (hasRepo), its url/name, the default " +
      "branch, and the working branch edits go to. Call this before editing to know if a repo must be " +
      "created first.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/repo/status", "POST", {});
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "create_repo",
  {
    title: "Create project repo",
    description:
      "Create the GitHub repo for THIS project (named after the business). Only call after the user " +
      "confirms they want a repo created. No-op if one already exists.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/repo/create", "POST", {});
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "open_pull_request",
  {
    title: "Open pull request",
    description:
      "Open (or update) a pull request from the working branch into the default branch, so the user can " +
      "review your changes. Returns the PR url.",
    inputSchema: {
      title: z.string().optional(),
      body: z.string().optional(),
    },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/repo/pr", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "run_demo",
  {
    title: "Run project demo",
    description:
      "Start a running demo of the project and return its url. NOTE: this runs the last generated/" +
      "published build — it does NOT reflect uncommitted edits you just made.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/repo/run", "POST", {});
    return { content: [{ type: "text", text: json }] };
  }
);

// --- Sandbox tools: a real workspace (the repo cloned into /workspace of a per-project container with
// Python, TypeScript/tsx, Node and Maven) where you can write/edit files and RUN scripts, then commit.
// This is the primary way to change the code — it replaces the old Contents-API file tools. ---

server.registerTool(
  "list_files",
  {
    title: "List workspace files",
    description:
      "List files/directories at a path in the project workspace (empty path = repo root).",
    inputSchema: { path: z.string().optional() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/list", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "read_file",
  {
    title: "Read workspace file",
    description:
      "Read a file's full content from the project workspace. Read before editing so you write back the " +
      "complete, correct content.",
    inputSchema: { path: z.string() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/read", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "write_file",
  {
    title: "Write workspace file",
    description:
      "Create or overwrite a file in the workspace with the COMPLETE content (parent dirs auto-created). " +
      "Nothing is committed until you call commit_and_push.",
    inputSchema: { path: z.string(), content: z.string() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/write", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "edit_file",
  {
    title: "Edit workspace file",
    description:
      "Replace an exact, UNIQUE snippet in a workspace file. `find` must occur exactly once — include " +
      "enough surrounding context to make it unique. For large rewrites use write_file instead.",
    inputSchema: { path: z.string(), find: z.string(), replace: z.string() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/edit", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "run_command",
  {
    title: "Run command in workspace",
    description:
      "Run a bash command in the workspace (Linux/Ubuntu, full CLI: git, curl/wget, grep/sed/awk, jq, " +
      "vim/nano, ps/df/free, ping/ip, ssh, passwordless sudo, plus python/node/tsx/npm/mvn) and get " +
      "stdout/stderr/exitCode. Use it to install deps and run/build/test code — e.g. `python script.py`, " +
      "`npx tsx script.ts`, `npm install`, `npm run build`, `./mvnw -q compile`. Pipes, &&, subshells and " +
      "other bash syntax work. One-shot with a timeout; a non-zero exitCode is returned (not an error).",
    inputSchema: { command: z.string(), timeoutSec: z.number().int().positive().optional() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/run", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "checkout_branch",
  {
    title: "Checkout branch",
    description:
      "Switch the workspace to a git branch and make it the active branch — subsequent edits, " +
      "commit_and_push and open_pull_request all use it. Checks out an existing local/remote branch; " +
      "set createNew=true to start (or reset) the branch from the current HEAD. Commit or discard " +
      "uncommitted changes first, or the switch is refused. Returns the verified current branch.",
    inputSchema: { branch: z.string(), createNew: z.boolean().optional() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/checkout", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "pull_latest",
  {
    title: "Pull latest",
    description:
      "Pull the latest commits for the current branch from origin (rebasing local commits on top). Use " +
      "after checkout_branch to make sure the workspace is up to date before editing. No-op if the branch " +
      "isn't on origin yet; fails cleanly on conflicts or uncommitted changes.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/sandbox/pull", "POST", {});
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "commit_and_push",
  {
    title: "Commit & push",
    description:
      "Stage everything, commit with your message, and push to the working branch. Do this once the " +
      "change set is ready and (ideally) building; then call open_pull_request for review.",
    inputSchema: { message: z.string() },
  },
  async (args) => {
    const json = await callSpringBoot("/internal/mcp/sandbox/commit", "POST", args);
    return { content: [{ type: "text", text: json }] };
  }
);

server.registerTool(
  "stop_sandbox",
  {
    title: "Stop sandbox",
    description:
      "Stop and remove this project's sandbox container. Uncommitted changes in the workspace are lost; " +
      "a fresh sandbox (re-cloned from the branch) is created automatically on the next file/command. " +
      "Use to free resources or to reset a broken workspace.",
    inputSchema: {},
  },
  async () => {
    const json = await callSpringBoot("/internal/mcp/sandbox/stop", "POST", {});
    return { content: [{ type: "text", text: json }] };
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);
