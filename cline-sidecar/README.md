# cline-sidecar

Node service embedding the [Cline SDK](https://docs.cline.bot/sdk/overview). The Spring Boot app calls it
once per project chat turn to get a Cline (Plan-mode, read-only) reply seeded with user + project context.

## Contract

- `POST /chat` — `{ sessionId, message, context, grant }` → `{ reply, usage? }`
- `GET /health` — `{ status: "ok", ... }`
- `POST /mcp/selftest` — `{ grant? }` → proxies to Spring Boot `/internal/mcp/context` with the two-layer
  headers; proves the MCP auth chain end to end (uses the last `/chat` grant if none supplied).

## MCP + two-layer auth

Cline acts on our system only through Spring Boot's `/internal/mcp/**` endpoints, double-gated:
`X-Internal-Token` (service auth) + `X-Mcp-Grant` (short-lived, brief-pinned, user-scoped grant minted
by Spring Boot per chat turn).

**Wiring (connected):** per `/chat` turn, `src/mcp/clineMcpBridge.ts` connects an `InMemoryMcpManager`
(`@cline/core`) to the stdio MCP server `src/mcp/projectContextServer.ts` and turns its tools into
`AgentTool[]`, which are passed to the Cline `Agent`. The MCP server is spawned per turn with the turn's
grant in `MCP_GRANT`, so every tool call forwards `X-Internal-Token` + `X-Mcp-Grant` to Spring Boot.
Tool exposed today: `get_project_context` (read-only; the model sees it namespaced as
`discovery-mcp__get_project_context`).

`/mcp/selftest` remains as a standalone diagnostic for the auth chain.

## LLM routing (no provider key here)

Cline uses its **native `gemini` provider** with `baseUrl` pointed at the Spring Boot proxy host root
(Cline appends `/v1beta`). Spring Boot's `/v1beta/**` forwarder swaps the internal token for the real
Gemini key and forwards to Google. **No OpenAI-compatibility shim** — that shim mishandles function/tool
calling, which broke MCP; native Gemini calling works. The sidecar holds only `CLINE_INTERNAL_TOKEN`
(sent as `x-goog-api-key`), never the Gemini key.

## Environment

| Var | Default | Purpose |
|---|---|---|
| `PORT` | `8091` | HTTP port |
| `CLINE_GEMINI_BASE_URL` | `http://app:8090` | Spring Boot proxy host root (Cline adds `/v1beta`) |
| `CLINE_INTERNAL_TOKEN` | *(empty)* | Shared token sent as `x-goog-api-key`; proxy swaps in the real key |
| `CLINE_MODEL` | `gemini-2.5-pro` | Gemini model id |
| `CLINE_PROVIDER_ID` | `gemini` | Cline native provider (`@cline/llms` `ProviderId.GEMINI`) |

## SDK surface (verified against @cline/sdk 0.0.79)

`src/server.ts` uses `new Agent({ providerId, modelId, apiKey, baseUrl, systemPrompt, tools })`
(`AgentRuntimeConfigWithProvider`) and reads the reply from `AgentRunResult.outputText`. Provider id
`gemini` matches `@cline/llms` `ProviderId.GEMINI`; the native provider honors a custom `baseUrl`
(`normalizeGeminiBaseUrl` appends `/v1beta`). `@cline/sdk` is pinned to `0.0.79`.

Note: `@cline/sdk` is early (0.0.x). Re-verify these fields when bumping the version.

## Local run

```bash
cd cline-sidecar
npm install
CLINE_INTERNAL_TOKEN=dev CLINE_GEMINI_BASE_URL=http://localhost:8090 npm start
curl localhost:8091/health
```
