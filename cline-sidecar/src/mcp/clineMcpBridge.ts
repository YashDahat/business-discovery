import path from "node:path";
import { fileURLToPath } from "node:url";
import { InMemoryMcpManager, createMcpTools, createDefaultMcpServerClientFactory } from "@cline/sdk";

/**
 * Bridges our MCP stdio server into the lightweight @cline/agents `Agent`.
 *
 * `@cline/core`'s MCP machinery connects to the stdio server (projectContextServer.ts) and exposes its
 * tools as `AgentTool[]` (which extend `AgentToolDefinition`), so they drop straight into `Agent.tools`.
 * A fresh manager + server process is created per Cline turn so the turn's scoped grant is passed to the
 * MCP server via env (MCP_GRANT) — the server forwards it to Spring Boot on every tool call.
 */
const SERVER_NAME = "discovery-mcp";

export interface McpBridge {
  tools: unknown[];
  dispose: () => Promise<void>;
}

export async function buildMcpTools(opts: {
  grant: string;
  internalToken: string;
  springBaseUrl: string;
}): Promise<McpBridge> {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const mcpServerPath = path.join(here, "projectContextServer.ts");

  const manager = new InMemoryMcpManager({ clientFactory: createDefaultMcpServerClientFactory() });
  await manager.registerServer({
    name: SERVER_NAME,
    transport: {
      type: "stdio",
      // Run the TypeScript MCP server directly via tsx (no build step), same as the sidecar itself.
      command: process.execPath,
      args: ["--import", "tsx", mcpServerPath],
      env: {
        ...process.env,
        MCP_INTERNAL_TOKEN: opts.internalToken,
        MCP_GRANT: opts.grant,
        SPRING_BASE_URL: opts.springBaseUrl,
      } as Record<string, string>,
    },
  });
  await manager.connectServer(SERVER_NAME);

  // IMPORTANT: the default name transform prefixes tools as `discovery-mcp__<tool>` — the HYPHEN in the
  // server name produces function names Gemini rejects/silently drops (its function names must match
  // ^[a-zA-Z_][a-zA-Z0-9_.]*$), so the model never sees usable tools. Emit the bare tool name instead
  // (they're already valid identifiers and unique — one server), which also matches our grant tool names.
  const tools = await createMcpTools({
    serverName: SERVER_NAME,
    provider: manager,
    nameTransform: ({ toolName }) => toolName,
  });
  return { tools, dispose: () => manager.dispose() };
}
