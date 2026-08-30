# Cline Integration — Discovery Platform

**Scope:** How Cline (open-source AI coding agent) is used for post-launch client change requests and code review, on top of the existing Phase 3 (Coder Agent) generation pipeline.

**Stack context:** Backend — Java / Spring Boot. Frontend — React + Tailwind. Client-facing conversation — Phase 1 chat interface (LangChain4j + PostgreSQL memory).

---

## 1. Why Cline

Three open-source coding agents were evaluated for this role: Aider, OpenHands, and Cline.

| Tool | Best at | Why not the primary pick here |
|---|---|---|
| Aider | Simple, lightweight file editing | Editor-first, not review-first — review logic would need to be custom-built |
| OpenHands | Run–observe–fix loops, verification | Heavier runtime/sandbox footprint; better suited as a fallback verifier than the primary driver |
| **Cline** | **Change requests + code review as first-class workflows** | — |

**Decision: Cline**, for two reasons specific to this project:

1. Both required roles — **feature/change requests** and **code review** — are documented, purpose-built Cline workflows, not something bolted on. Less custom glue code to write and maintain.
2. It ships as CLI, SDK, *and* has a Plan/Act mode split that maps directly onto "understand the requirement, then execute" — which is exactly the flow this project needs for client-submitted requests.

Language support (Java + TypeScript) was not a differentiator — all three tools are language-agnostic; they edit text files and run shell commands, with the LLM doing the actual code reasoning.

---

## 2. What Cline provides

- **CLI 2.0** (Feb 2026) — terminal-first, with a headless mode for automation/CI (`--json`, `--yolo`, piped stdin).
- **SDK** (`@cline/sdk`, May 2026) — TypeScript packages (`@cline/core`, `@cline/agents`, `@cline/llms`, `@cline/shared`) for embedding the agent runtime directly into your own product. Requires Node.js 22+.
- **Plan / Act modes** — Plan mode explores the codebase and asks clarifying questions without touching files; Act mode executes an approved plan (file edits, terminal commands).
- **Model-agnostic (BYOK)** — supports Anthropic, Google Gemini, AWS Bedrock, and 9+ other providers. No re-integration needed to keep using the project's existing Gemini + Claude routing.
- **Automated PR review workflow** — SME identification via git history, related issue/PR discovery, and deep code analysis (intent, edge cases) — used by Cline's own repo for its PR reviews.

---

## 3. Where Cline sits in the platform

Two roles, layered on top of the existing Phase 3 container-per-project pattern:

1. **Change request execution** — client asks for a modification to a *live* site → Cline edits the existing repo.
2. **Code review** — pass over a diff (Cline's own or a generated one) before it goes to PR.

Cline is **not** used for the initial full-site generation (Phase 3 first-pass). That stays on the existing structured, brief-driven pipeline, since it needs tighter per-file/token control than a chat-oriented agent naturally exposes.

---

## 4. Workload tiers & container strategy

Not every interaction with Cline carries the same risk, so not every interaction should carry the same cost. A fresh, isolated container is only required for the one workload that actually mutates or executes code — the other two use lighter-weight paths.

| Workload | Example | Container needed? | Why |
|---|---|---|---|
| **Change requests** | "Add a WhatsApp button to the homepage" | **Yes — dedicated, ephemeral** | Cline edits files and runs build/test commands. This is real untrusted code execution and needs full isolation, exactly as designed in Section 5. |
| **Project Q&A** | "How does the checkout flow work?" | **No — small shared, read-only pool** | Only requires *reading* the repo (Plan-mode-only exploration, no Act mode). No mutation risk, so a small pool of persistent, read-only containers with the repo kept in sync can serve many concurrent questions — no cold start, no build toolchain needed. |
| **Deployment triggers** | "Deploy the last approved change to staging" | **No container at all** | This is just Cline calling Spring Boot's `trigger_deployment_pipeline` MCP tool (Section 9), which calls GitHub Actions. The actual build/deploy work runs on GitHub Actions' own runners — no Cline container is involved in the deploy step itself. |

**Effect on concurrency planning:** for a mixed load like 3 users making changes, 5 asking questions, and 2 deploying, the real footprint is **3 dedicated containers + a small reusable Q&A pool (2–3 containers total, not 5) + 0 containers for deploys** — not 10 heavyweight sandboxes. This also reduces LLM provider load on the read path, since Plan-mode-only exploration typically needs fewer, cheaper calls than a full edit-build-fix loop (Section 8).

**Routing decision:** the triage step already planned for Phase 2 (Section 10) should classify each incoming request into one of these three tiers *before* deciding whether to spawn anything — "is this asking about the project, asking to change it, or asking to deploy it" becomes the first branch, ahead of picking a target repo.



**Boundary rule: Cline talks only to Spring Boot.** It never calls GitHub, CI/CD, or any other third-party system directly — Spring Boot is the sole integration point with the outside world. Cline reports its results back to Spring Boot, and Spring Boot is what actually creates the PR.

```
Client requests change (chat, Phase 1)
        │
        ▼
Spring Boot orchestrator — triage & pick target repo
        │
        ▼
Cline SDK, Plan mode — explore repo, ask clarifying questions
   (round-trips through the existing React chat UI, via Spring Boot)
        │
   plan confirmed
        ▼
Spawn isolated container (React repo OR Spring Boot repo)
        │
        ▼
Cline headless, Act mode — edits code, structured JSON output
        │
        ▼
Validate — compile/build, then Cline (or OpenHands) review pass
        │
        ▼
Cline reports result to Spring Boot (diff, files touched, review outcome)
        │
        ▼
Spring Boot creates the GitHub PR — scoped App token (1-hour expiry)
        │
        ▼
Client notified in chat
```

## 5. End-to-end flow

### 5.1 System communication overview

**Spring Boot is the sole gateway — including LLM calls.** Cline never talks to GitHub, React, or the LLM providers (Gemini/Claude) directly. Every path routes through Spring Boot, which means Spring Boot is the only place in the system that holds *any* external credential — GitHub App token or LLM provider API key.

```
                         ┌──────────┐
                         │ React app│
                         │ Chat UI  │
                         └────┬─────┘
                              │  HTTP/WS — chat, status
                              ▼
   ┌──────────────┐    ┌──────────────┐    ┌──────────┐
   │ Cline service│◄──►│ Spring Boot  │◄──►│  GitHub  │
   │ Node SDK/CLI │    │ Sole gateway │    │ PR+Actions│
   └──────────────┘    └──────┬───────┘    └──────────┘
                               │
                               ▼
                        ┌──────────────┐
                        │ LLM providers│
                        │ Gemini+Claude│
                        └──────────────┘
```

- **React ↔ Spring Boot** — direct, two-way (HTTP/WebSocket). Client messages in; Plan mode clarifying questions and task status out.
- **Spring Boot ↔ Cline service** — two-way (process invocation, Section 5.5). Spring Boot spawns the container and starts Plan/Act mode; Cline reports its result (diff, files touched, review outcome) back. Cline's own LLM inference calls also route through Spring Boot — see 4.2.
- **Spring Boot ↔ GitHub** — two-way. Spring Boot creates the PR with the scoped App token, and receives webhook updates on build/deploy status. This is the only place GitHub credentials live.
- **Spring Boot ↔ LLM providers** — two-way. Spring Boot is the only component that holds Gemini/Claude API keys and calls those providers directly.

### 5.2 LLM call routing — Cline never calls Gemini/Claude directly

Cline's provider configuration is set to a custom OpenAI-compatible endpoint pointing at Spring Boot, rather than a named provider with real credentials:

```
provider: {
  type: "openai-compatible",
  baseURL: "https://internal-spring-boot/v1",
  apiKey: "<internal service token, not a real provider key>",
  model: "gemini-2.5-pro"   // Spring Boot maps this to the actual routed model
}
```

Cline sends chat completion requests to Spring Boot exactly as it would to a real provider. Spring Boot forwards them to Gemini or Claude using its own stored keys, and streams the response back in the same shape Cline expects.

**Why this is worth building, beyond credential hygiene:**

- **Model routing becomes enforceable, not just configured.** The "cheap model for triage, strong model for generation" strategy (Section 8) can be applied centrally per call, independent of Cline's local settings.
- **Token/cost accounting is provider-verified, not self-reported.** Spring Boot logs the real usage directly from the provider response, rather than trusting Cline's own metrics (Section 7).
- **A network-layer backstop on the retry-spiral risk (Section 7).** Spring Boot can hard-cap call count or cost per `change_request_id`, independent of whether Cline's own `attempt_count` logic behaves correctly.
- **A natural place for guardrails** — prompt injection filtering, secret redaction, content policy checks — before anything reaches an external LLM.

**Engineering cost to plan for:** this requires Spring Boot to implement a proper OpenAI-compatible **streaming** endpoint (SSE) — Cline expects streamed completions, not single blocking responses. Worth scoping as its own task.


### 5.3 Requirement understanding (Plan mode)

- No standalone Cline GUI exists to embed in the React frontend. Instead, Plan mode is called from Spring Boot via the SDK; its clarifying questions are rendered as ordinary messages in the existing React chat UI.
- Container spawn is explicitly gated on plan confirmation — Act mode (and therefore the container) only starts after the client (or an internal low-risk-approval rule) confirms the plan.

### 5.4 Repos

- React and Spring Boot are kept as **separate repos**, each targeted by its own container run.
- A request touching both (e.g. new API + new UI for it) runs as **two sequential container invocations** — backend first, frontend second, fed the resulting API shape. Avoids Cline having to reason across two codebases in one pass, and keeps validation (`mvn`/`gradle` vs `npm run build`) clean per repo.

### 5.5 Cross-language integration

Cline's SDK is Node.js-based; Spring Boot does not call it as a native library. Two supported patterns:

1. **Process invocation** — Spring Boot's `ProcessBuilder` runs the container (`docker run …`), which internally runs Cline headless. Spring Boot waits for exit code and reads output from a shared volume. *(Recommended starting point — fewer moving parts, matches the existing container lifecycle.)*
2. **HTTP callback** — container/Cline service POSTs its result to a Spring Boot endpoint on completion. Better for long-running or async-status use cases.

### 5.6 Container invocation (headless)

```bash
cline --json "Add a WhatsApp click-to-chat floating button to the homepage. \
Use the existing color scheme (#XXXXXX) and match current button styling." \
> result.json
```

- `--json` gives structured, parseable output (files touched, summary, success/failure) for the orchestrator to log.
- No need to specify target files manually — Cline reads the existing codebase itself.

---

## 6. Data to capture

Extends the platform's existing analytics catalogue (Section 3.3-style entities) with a new table:

| Field | Notes |
|---|---|
| `client_id` | |
| `request_text` | Raw client request from chat |
| `plan_summary` | Cline Plan mode output, pre-confirmation |
| `files_touched` | From Cline's JSON output |
| `diff_size` | |
| `attempt_count` | See retry cap below |
| `review_status` | From the review pass |
| `compile_status` | |
| `pr_url` | |
| `token_usage` / `cost` | Per-task, from Cline's built-in cost tracking |

Cline tracks token/cost per task natively — this data should be read directly from the SDK's task metrics rather than re-implemented.

---

## 7. Token & cost management

**What's solid:**
- Built-in real-time cost tracking per task.
- A real compaction pipeline (`@cline/core`) — separate budgeting for full request vs. transcript, with deterministic (safe fallback) and LLM-driven compaction modes. Dropped tool outputs are replaced with summaries, not silently lost.
- Since Cline's LLM calls now route through Spring Boot (Section 5.2), token/cost figures no longer need to rely solely on Cline's self-reported metrics — Spring Boot can log provider-verified usage directly from each proxied response.

**What's not solid yet:**
- No first-party cross-session usage analytics/reporting dashboard (open community request as of mid-2026). Per-task tracking is fine; historical/aggregate reporting is not built in — this project's own `change_request` table (Section 6) should be the source of truth for that, not Cline's UI.

**Known risk — retry spirals:** on stubborn errors, Cline can iterate 15–20+ rounds trying to fix its own fix, each round consuming more tokens. **A hard `attempt_count` cap (3–5) is required**, not optional — escalate to human review after the cap rather than trusting the agent to self-limit.

---

## 8. Latency expectations

This is a multi-minute, asynchronous workflow — not a sub-second request/response pattern. Approximate breakdown:

| Stage | Typical time |
|---|---|
| Spring Boot → Cline SDK call | ~10–50ms (network only) |
| Plan mode reasoning + clarifications | 5–30s per round (LLM-bound) |
| Container spawn | 5–30s (cold start) — reduce with a pre-warmed container pool |
| Cline headless execution | 30s – several minutes |
| Build/validation | 10–60s+ |
| PR creation | 1–3s |

**Design implication:** the React UI should treat this as a submitted task with a status indicator, notified on completion via the chat interface — not a blocking synchronous call.

**Optimizations:**
- Pool pre-warmed containers instead of cold `docker run` per request.
- Persistent per-client repo volumes with `git pull` instead of full clones.
- Route triage / clarifying-question generation to a cheap, fast model (e.g. Gemini Flash); reserve the heavier model for actual code generation.

---

## 9. Deployment — MCP tool trigger, not direct agent action

**Cline does not run deployment commands directly, and does not call GitHub Actions directly either — the same boundary rule from Section 5.1 applies here.** It has shell access in Act mode and technically *could* run `kubectl apply` / `docker push` / `terraform apply`, but this is deliberately avoided. Deployment needs to stay deterministic and auditable — a CI/CD pipeline runs the same steps the same way every time; an agent improvising deploy commands introduces variability into the one part of the system that should have none.

**Chosen pattern: Cline decides *when*, Spring Boot decides *how*.**

Cline is given a single narrow MCP tool — **hosted by Spring Boot**, not by any external service:

```
trigger_deployment_pipeline(environment: "staging" | "production", change_request_id: string)
```

- Cline calls this tool the same way it reports any other result — it's a call to Spring Boot, nothing else. Spring Boot is what then calls GitHub Actions' `workflow_dispatch` (or merges the already-approved PR) internally.
- Cline never sees, needs, or holds a GitHub token. Its only decision is **whether and when** to call the tool, based on its own review pass and build validation succeeding — it has no way to reach the deployment surface itself, because that surface doesn't exist on Cline's side of the boundary.

**Updated end-to-end flow (extends Section 5):**

```
… (as in Section 5, through PR created by Spring Boot) …
        │
        ▼
Cline review/validation pass confirms readiness
        │
        ▼
Cline calls Spring Boot's MCP tool → trigger_deployment_pipeline(environment, change_request_id)
        │
        ▼
Spring Boot calls GitHub Actions (deterministic) — build → test → deploy
```

**Safeguards required around the tool:**

1. **Environment gating.** `staging` may fire on Cline's own decision — low-risk, reversible, standard "continuous delivery" behavior. `production` requires either a separate human approval step before Spring Boot will act on the call, or a distinct, explicitly confirmed second action — never auto-fired straight off a single successful staging run.
2. **Idempotency / rate limiting.** Given the retry-spiral risk (Section 7), Spring Boot must dedupe on `change_request_id` — Cline calling the tool twice for the same request must not double-fire a deployment, and it should reject/no-op if a deploy for that ID is already in flight.
3. **Audit logging.** Every call logs `change_request_id`, timestamp, environment, and outcome against the `change_request` table (Section 6) — this is the audit trail for *why* a deployment happened, which matters more here than for a code edit.
4. **Credential scoping.** Spring Boot alone holds the deploy-triggering credentials (e.g. a GitHub App token scoped only to `workflow_dispatch`). Cline is never given deploy credentials, GitHub credentials, or any external credentials at all — it only ever authenticates to Spring Boot.

**Legitimate secondary use of Cline in this area:** editing deployment *configuration as code* — e.g. updating a GitHub Actions workflow YAML or a Dockerfile — is a normal code-editing task, not a deployment action, and stays within Cline's existing change-request role (Section 3). The edited file still goes back to Spring Boot like any other change, not straight to GitHub.

---

## 10. Implementation phases

Rollout is staged so each phase proves the riskiest new piece before adding the next layer of polish — get one repo's loop working before doubling the surface area, get the client-facing flow working before hardening it, and don't build deployment automation on top of a pipeline that isn't retry-safe yet.

### Phase 1 — Foundation: single repo, manual trigger
- Container image with Cline, git, and one repo's build toolchain (start with either React or Spring Boot).
- Spring Boot's `ProcessBuilder`-based container spawn and result read-back (Section 5.6).
- A minimal pass-through OpenAI-compatible endpoint in Spring Boot (Section 5.2) — no routing or guardrails yet, just forwarding, with streaming working.
- Spring Boot creates the GitHub PR with a scoped App token.
- Trigger everything manually via a script or direct API call, not through chat.
- **Goal:** prove the full loop — Cline edits code, validates, PR appears — works end to end for one repo.

### Phase 2 — Both repos + client-facing flow
- Extend the pipeline to cover both the React and Spring Boot repos (Section 5.4), including the sequential two-container pattern for cross-cutting requests (backend first, frontend fed the resulting API shape).
- Wire Plan mode into the real Phase 1 chat interface so clients see clarifying questions and confirm a plan before a container spawns (Section 5.3).
- Add a cheap-model triage step to classify each request and pick the target repo(s).
- Start logging to a basic `change_request` table (Section 6) — request text, files touched, PR URL.
- **Goal:** a real client can submit a request through chat and get a PR, for either or both repos.

### Phase 3 — Hardening: retries, review, safety caps
- Add the hard `attempt_count` cap with escalation to human review on failure (Section 7).
- Add Cline's automated review pass before PR creation.
- Add container pooling and persistent per-client repo volumes to cut cold-start latency (Section 8).
- Expand the LLM gateway to do real model routing per task type — cheap model for triage, strong model for generation — enforced centrally in Spring Boot rather than left to Cline's local config.
- Start logging provider-verified token/cost per request.
- **Goal:** the pipeline is reliable and cost-controlled enough for repeated real use, not just a demo.

### Phase 4 — Deployment integration
- Add the `trigger_deployment_pipeline` MCP tool hosted by Spring Boot (Section 9).
- Let staging deploys fire on Cline's own confidence after review; gate production behind a separate human approval step.
- Add idempotency/dedup on `change_request_id` and full audit logging of every deploy trigger against the `change_request` table.
- **Goal:** an approved change request can go from merged PR to live on staging without manual ops work.

### Phase 5 — Guardrails, observability, scale
- Add prompt injection filtering and secret redaction in the LLM gateway before anything reaches Gemini or Claude (Section 5.2).
- Build an observability layer on top of the `change_request` table to cover the usage-analytics gap Cline itself doesn't provide (Section 7).
- Enforce rate limits and hard cost ceilings network-side in the Spring Boot gateway, independent of Cline's own limits.
- Tune container pool sizing and concurrent request handling for multiple clients at once.
- **Goal:** production-grade, multi-tenant reliability and a real safety net, not just functional correctness.

---

## 11. Open caveats

- No SOC 2 / HIPAA / ISO 27001 certification on Cline as of Q2 2026 — not a blocker currently, worth revisiting if client data handling requirements tighten.
- CLI/SDK-first identity is newer (2026) than more established tools like Aider — actively developed, but with a shorter stability track record.
- Retry-spiral risk (Section 7) requires enforced caps at the orchestration layer, not just monitoring.
- Deployment tool (Section 9) safeguards — environment gating, idempotency, audit logging — must all be in place before Cline is given the `trigger_deployment_pipeline` tool. Credential exposure specifically is no longer a risk to guard against: Cline is architecturally incapable of holding GitHub, LLM provider, or any other external credentials, since it never talks to anything but Spring Boot (Section 5.1).

---

## 12. References

- Cline CLI docs: https://docs.cline.bot/usage/cli-overview
- Cline SDK docs: https://docs.cline.bot/sdk/overview
- Cline GitHub: https://github.com/cline/cline
