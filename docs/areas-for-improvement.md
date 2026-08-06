# Areas for Improvement

Future enhancements identified during development. Not yet implemented — document here to revisit when the core system is stable.

---

## 1. Git Diff for Token Reduction

**Problem:** LLM calls send full file content even when only a few lines changed. This wastes tokens on context that is irrelevant to the task.

**Proposed Solution:** Use `git diff HEAD -- {filePath}` to get only the changed lines. Fall back to signature extraction (class + field + method declarations, no method bodies) for new files that have no prior commit to diff against.

**Where it helps:**

| Use Case | Tokens Before | Tokens After | Reduction |
|---|---|---|---|
| ErrorFixNode — broken file context | ~3,200 | ~800 | ~75% |
| Stage 2 spec check — updated file | ~1,200 | ~300 | ~75% |
| Stage 2 spec check — new file (signature extraction) | ~1,200 | ~400 | ~65% |
| Update mode — existing file sent to Flash | ~1,200 | ~300 | ~75% |

**Files that would change:**
- `GitService.java` — add `getDiffForFile(workspace, filePath)`
- `SignatureExtractor.java` — new utility, extracts Java/TypeScript signatures without method bodies
- `ErrorFixNode.java` — use diff instead of full file when prior commit exists
- `LlmGeneratorService.java` — `checkSpecCompliance` takes diff or signatures instead of full content
- `BackendGeneratorNode.java` / `FrontendGeneratorNode.java` — pass diff as `existingSection` in update mode
- `prompts/user/fix_file.txt` — update context section to reflect diff-based input
- `prompts/user/spec_compliance.txt` — note receives diff or signatures, not full file

**Why deferred:** Changes the fundamental way files are read and passed to LLMs across multiple nodes. Significant refactor — implement after core generation pipeline is stable and tested end-to-end.

---

## 2. Per-File LLM Retry with Backoff

**Problem:** A single LLM timeout during file generation (e.g. AuthContext.tsx in Urban Foundry) throws a `WorkerException` and crashes the entire generation run. Other files that come after in layer order are never generated.

**Proposed Solution:** Wrap each Flash call with a retry loop — 3 attempts with 2s/4s exponential backoff. If all 3 attempts fail, mark the file as `GENERATION_FAILED` in ARCHITECTURE.json and continue to the next file. Report all `GENERATION_FAILED` files at the end of the run.

**Files that would change:**
- `BackendGeneratorNode.java` — wrap `llm.generateFileContent` in retry loop
- `FrontendGeneratorNode.java` — same
- `FileSpec.java` — document `GENERATION_FAILED` as a valid status value

**Why deferred:** Low effort — can be implemented as a small isolated change when needed.

---

## 3. Compressed Instruction Format in Enrichment

**Problem:** Pro writes verbose prose instructions (~2,500 tokens per file). For a 44-file project this produces ~137k output tokens = $1.37 per project just for the enrichment call.

**Proposed Solution:** Add a compact format rule to `system/file_instruction_enrichment.txt`. Replace prose paragraphs with arrow notation:
```
METHOD: getAllItems(Optional<String> category): List<MenuItemDto>
  → if category present: menuRepo.findByCategory(category.get())
  → else: menuRepo.findAll()
  → map each to dto → return list
```
Cap each file instruction at 800 tokens. Target: reduce Pro output from 137k → ~55k tokens per project (~$0.82 saving per project).

**Files that would change:**
- `prompts/system/file_instruction_enrichment.txt` — add compact format rule + 800 token cap

**Why deferred:** Prompt-only change, low risk. Implement after verifying generation quality with current verbose format on more projects.

---

## 4. Skip Re-Enrichment on Retry

**Problem:** When a retry run triggers (e.g. 1 PLANNED file exists), the skip guard fails and both `generateArchitectureSpec` AND `enrichArchitectureSpec` are called again. The enrichment alone costs $1.37. For Urban Foundry, this meant paying $2.74 in Pro output just to regenerate 1 file.

**Proposed Solution:** Separate the skip guard into two independent checks:
1. Skip `generateArchitectureSpec` if no PLANNED files exist and no `requestedChanges`
2. Skip `enrichArchitectureSpec` if all file entries already have `coding_instruction` set

**Files that would change:**
- `ProjectPlanningNode.java` — split `shouldSkipPlanning` into `shouldSkipSpecGeneration` and `shouldSkipEnrichment`

**Why deferred:** High value, low effort — but depends on the per-file status machine (Item 5 below) being in place first so GENERATED files are correctly identified.

---

## 5. Per-File State Machine (PLANNED → GENERATED → VALIDATED → SPEC_COMPLIANT)

**Problem:** Validation is batch — all files compile together at the end. A single broken file causes all other files to lose their GENERATED status on retry. Urban Foundry re-generated all 44 files just because 1 had a compile error.

**Proposed Solution:** Per-file inline pipeline in generator nodes:
- **Stage 3** (PLANNED → GENERATED): Flash writes from `coding_instruction`
- **Stage 1** (GENERATED → VALIDATED): inline `mvn compile` / `tsc --noEmit` per file
- **Stage 2** (VALIDATED → SPEC_COMPLIANT): Pro checks generated file against `coding_instruction`

On re-run, each file resumes from its current state:

| Status | Stage 3 | Stage 1 | Stage 2 |
|---|---|---|---|
| PLANNED | Run | Run | Run |
| GENERATED | Skip | Run | Run |
| VALIDATED | Skip | Skip | Run |
| SPEC_COMPLIANT | Skip | Skip | Skip |

**New statuses:** `SPEC_COMPLIANT`, `GENERATION_FAILED`

**New prompts:**
- `prompts/system/spec_compliance.txt`
- `prompts/user/spec_compliance.txt`

**Files that would change:**
- `LlmGeneratorService.java` — add `checkSpecCompliance(filePath, content, instruction)`
- `ComplianceResult.java` — new record: `boolean compliant`, `List<String> issues`
- `BackendGeneratorNode.java` — 3-stage per-file loop, inject both `geminiPro` + `geminiFlash`
- `FrontendGeneratorNode.java` — same
- `BuildToolService.java` — add `runTscCheck(Path frontendDir)`
- `LlmConfig.java` — inject Pro into generator nodes
- `ProjectPlanningNode.java` — update skip guard for new status values

**Why deferred:** Significant change to generation flow. Implement after core pipeline is proven stable across multiple projects.
