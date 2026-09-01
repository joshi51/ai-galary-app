# Phase 8 — Natural-Language AI Search: Design Spec

Companion spec for [the plan](../plans/2026-08-29-local-ai-photo-manager.md)'s Phase 8 entry and [ARCHITECTURE.md](../../ARCHITECTURE.md) (§2, §11, §14, §19). Produced 2026-08-31. Status: **Approved — ready for implementation planning.**

## 1. Scope

- A local, on-device LLM (llama.cpp) translates a natural-language photo query into a single structured tool call.
- The LLM never touches the filesystem or database directly — it can only select a tool and parameters, validated and executed by a controlled `:tools` layer that delegates to existing/new `:domain` use cases.
- Covers all example queries in the plan: "Show me photos of Rahul," "...from 2025," "Find photos with Rahul and Priya," "Find my largest photos," "Find duplicate photos," "Show me photos taken in Delhi."
- Structured logging/tracing of query → intent → tool → params → result → response, without persisting private photo content.
- The LLM engine/model is isolated behind `:llm:runtime` so it can be replaced later without touching `:llm:orchestration`, `:tools`, or any UI.
- No LLM involvement in Phase 9/10's organization/undo flows — those are separate, later phases building on this one's `:llm:*`/`:tools` foundation.

## 2. Decisions locked by this spec

- **llama.cpp built from source**, not a prebuilt wrapper library: a pinned upstream commit is vendored and compiled via CMake/NDK inside `:llm:runtime`, targeting `arm64-v8a` only, with the server/examples/multimodal code excluded from the build. Chosen specifically to get direct access to llama.cpp's **GBNF grammar-constrained sampling** API, which most prebuilt Android wrapper libraries don't expose — grammar-constrained decoding is the load-bearing reliability mechanism for this phase (see §5), so building from source is not optional polish.
- **Model: Llama-3.2-1B-Instruct, GGUF, Q4_K_M quantization** (~0.7GB) — no Google-branded model, per the plan's Global Constraints. Formal license/provenance verification (Meta Llama 3.2 Community License terms, pinned download URL + SHA-256) happens during implementation, documented in ARCHITECTURE.md with the same rigor as Phase 4 §33's FaceNet record — this spec locks the model choice, not yet the verified download details.
- **Tool set is consolidated to five tools**, not the plan's illustrative eight: `search_people`/`search_by_date`/`search_by_location`/`search_photos` collapse into one `search_photos` tool with optional parameters. A small (1B) model choosing between four overlapping search tools is a real misrouting risk; one tool with optional fields removes the ambiguity entirely while still covering every example query. Final set: `search_photos`, `find_duplicates`, `find_similar_photos`, `get_photo_metadata`, `get_storage_statistics`.
- **Structured output via GBNF grammar**, not free-form JSON the model is merely asked to produce. The grammar is generated from the tool registry's parameter schemas and passed to llama.cpp's sampler, making syntactically-invalid output structurally impossible (a malformed *value*, e.g. a nonexistent person name, is still possible and is caught by `:tools` validation).
- **No second LLM call for a natural-language summary.** The "final response" is a deterministic template built from the chosen tool + params + result count (e.g. "Found 12 photos of Rahul from 2025"), avoiding a second on-device inference pass per query.
- **Single-turn only** — no conversation memory across queries in this phase, matching ARCHITECTURE.md §14's sequence diagram exactly.
- **NL search is added to the existing Search screen** (`:feature:search`), not a new screen — a query box above Phase 6's deterministic person/date/location filters, sharing the same results grid.
- **Tracing is logcat-only this phase** (via the existing `core.common.Logger`), not a new persisted trace table — Phase 11 already owns durable AI-diagnostics surfacing, so persisting trace history is deferred there rather than building a parallel mechanism now.

## 3. Module additions

```
:llm:runtime        (new)
  llm.runtime.LlamaCppEngine       — JNI wrapper: load/unload model, tokenize, generate-with-grammar
  llm.runtime.ModelLoader          — lazy load, idle-timeout unload (mirrors :ml:embeddings' pattern)
  llm.runtime.LlmModelDownloader   — HTTP download, SHA-256 pinned verification, temp-file-then-rename
  cpp/                             — vendored llama.cpp source (pinned commit) + JNI bridge + CMakeLists.txt

:llm:orchestration  (new)
  llm.orchestration.IntentParser   — builds prompt + injects grammar for the registered tool set
  llm.orchestration.ToolCallLoop   — runs inference, parses/validates JSON, dispatches, retry/fallback
  llm.orchestration.TraceLogger    — structured logcat tracing (see §7)

:tools              (new)
  tools.ToolRegistry
  tools.SearchPhotosTool / FindDuplicatesTool / FindSimilarPhotosTool /
        GetPhotoMetadataTool / GetStorageStatisticsTool
  tools.ToolValidator              — shared param validation helpers (range/type/whitelist)
```

`:domain` gains: `LlmEngine` interface (implemented by `:llm:runtime`'s `LlamaCppEngine`), `ToolCall`/`ToolResult` models, `GetPhotoMetadataUseCase`, `GetStorageStatisticsUseCase` (new — everything else reuses Phase 6/7's existing use cases unchanged).

Per the architecture's locked boundary: `:llm:orchestration` depends only on `:tools`'s interfaces, never on `:data:*`/`:fsops`/raw file APIs. Each `tools.*Tool` validates its typed parameters and calls exactly one `:domain` use case — no business logic in the tool layer itself.

## 4. Tool definitions

| Tool | Params (all optional unless noted) | Delegates to |
|---|---|---|
| `search_photos` | `people: [string]`, `startDate/endDate: string (ISO date)`, `location: string`, `sortBy: "newest"\|"largest"\|"smallest"` | Phase 6 `SearchPhotosUseCase` — `people` names resolved to person IDs via `PersonRepository` lookup by name before building `PhotoSearchFilter`; `location` resolved via the existing saved-location bounding-box mechanism (Phase 6 §7) since there's no free-text geocoding on-device |
| `find_duplicates` | none | Phase 7 `DetectDuplicatesUseCase` |
| `find_similar_photos` | none | Phase 7's visually-similar group query |
| `get_photo_metadata` | `photoId: long` (required) | new `GetPhotoMetadataUseCase` → `PhotoRepository` |
| `get_storage_statistics` | none | new `GetStorageStatisticsUseCase` — aggregates photo count, total size, people count, face count, duplicate/similar group counts |

`ToolValidator` enforces: required params present, `photoId` refers to a photo that actually exists (existence check via `PhotoRepository`, not just a numeric-range check — a hallucinated ID must not proceed to the use case), `sortBy`/`location` values are within the enum/known-saved-locations whitelist, date strings parse to valid millisecond ranges. Any failure returns a typed validation error, never an exception that could crash the orchestrator.

**`people` name resolution is the one place free-text can fail silently wrong**: if the LLM extracts "Rahul" but no person is named exactly "Rahul" (e.g. it's "Rahul K." or unnamed), `SearchPhotosTool` does a case-insensitive exact match first, then falls back to reporting "no person found matching 'Rahul'" as a tool result (shown to the user) rather than silently searching with zero people filters (which would return unrelated photos and look like a wrong answer with no explanation).

## 5. Grammar-constrained decoding

Each tool's parameter schema (§4) compiles to a GBNF rule (string, optional-string, enum, ISO-date-pattern, integer); the root grammar is a one-of across the five tool-call shapes: `root ::= search-photos-call | find-duplicates-call | ...`. This is generated once per app session (the tool set doesn't change at runtime) and passed to `LlamaCppEngine.generate(prompt, grammar)`.

**Prompt structure**: a system prompt naming the five tools and their parameters plus 4-5 few-shot examples covering the plan's example queries, followed by the user's query. One inference call per query, temperature low (near-deterministic) since this is intent classification, not creative generation.

**Failure handling**:
- Grammar-constrained output is always syntactically valid JSON matching *some* tool shape, but a param value can still be wrong (hallucinated `photoId`, unresolvable person name, `sortBy` value outside the enum in the unlikely event the grammar itself doesn't fully constrain it). `:tools` validation is the real safety net, not the grammar.
- A `:tools` validation failure triggers exactly one re-prompt ("Your last response had an invalid parameter: X. Try again.") before falling back to a user-visible "Couldn't understand that — try the filters above" message. Never more than one retry, to keep worst-case on-device latency bounded.
- Model not yet downloaded → the query box is disabled with a "Download search assistant model" prompt (Settings); the existing Phase 6 deterministic filters remain fully functional regardless.
- Model load OOM/failure → caught, reported, search degrades to deterministic-only for that session (does not crash the app), consistent with ARCHITECTURE.md §20's `llama.cpp Runtime` failure-scenario row.

## 6. UI (`:feature:search`)

`SearchScreen` gains a `TextField` + send action above the existing person/date/location picker UI ("Ask in plain English…"). Submitting:
1. Shows a loading/thinking indicator (inference + tool execution, expected low-single-digit seconds on a 1B Q4 model per ARCHITECTURE.md §18's threading model).
2. On success: populates the same paginated results grid Phase 6 already built, plus a one-line templated status ("Found 12 photos of Rahul from 2025", "No photos match that").
3. On tool-level "no person found" or validation-fallback: shows the specific message from §4/§5 instead of an empty grid with no explanation.

The two search modes (NL query box, deterministic filters below it) are independent — submitting an NL query does not populate/replace the filter chips; a user can still fall back to manual filters at any time, including whenever the model isn't downloaded.

## 7. Logging/tracing

`TraceLogger` emits one structured logcat line per stage per query, under a single tag (`LlmTrace`), e.g.:

```
LlmTrace: query="Show me photos of Rahul from 2025"
LlmTrace: intent=search_photos params={people=[Rahul], startDate=2025-01-01, endDate=2025-12-31}
LlmTrace: validation=OK
LlmTrace: tool_result photoCount=12 durationMs=340
LlmTrace: response="Found 12 photos of Rahul from 2025" totalLatencyMs=1850
```

Rules: query text and interpreted params are logged as-is (they're user intent, not photo content, and never leave the device — consistent with the app's on-device-only privacy model). Tool results log **counts and IDs only** — never filenames, file paths, or GPS coordinates — matching ARCHITECTURE.md §19's "record tool names, parameter shapes, and result counts/IDs, never raw photo bytes, filenames with personal content, or embedding vectors." No log line is persisted to disk/Room in this phase; it's `adb logcat`-visible for development/verification only, per §2's decision to defer durable trace history to Phase 11.

## 8. Testing (business-logic only, per project convention)

- `ToolValidatorTest` / per-tool validation tests: required-param enforcement, `photoId`-existence check (against a fake repository), `sortBy`/location whitelist enforcement, date-range parsing, the "unresolvable person name" path.
- `ToolRegistry` dispatch test: routes a given tool name to the correct tool, unknown tool name → typed error.
- Grammar-generation test: pure function from a tool's parameter schema → GBNF fragment, snapshot/string-equality assertions (deterministic output, no native dependency).
- LLM-output parsing test: valid JSON → `ToolCall`, malformed/unknown-tool JSON → parse error (this is pure JSON handling, exercised without any native inference).
- `GetStorageStatisticsUseCaseTest` / `GetPhotoMetadataUseCaseTest` against fakes.
- No tests for the native JNI/llama.cpp bridge or actual inference quality (grammar adherence, tool-selection accuracy on real prompts) — those require a real device/emulator and are verified manually during the implementation phase, the same treatment Phases 3/4/7 gave ML Kit/TFLite.

## 9. Known scope cuts (explicit, not oversights)

- No multi-turn conversation memory — every query is independent.
- No natural-language summary beyond the deterministic template (§2) — an explicit, approved trade against doubling per-query on-device latency.
- No free-text geocoding for `location` — reuses Phase 6's saved-location-only mechanism; "Delhi" only resolves if the user has saved a location named/near that.
- `find_similar_photos` and `find_duplicates` take no query-specific parameters this phase (they surface the existing Phase 7 groupings) — a request like "find photos similar to this one" (with a specific reference photo) is not supported; only "find duplicate/similar photos" in general.
- Persisted, UI-visible trace history is deferred to Phase 11's diagnostics screen — this phase's tracing is logcat-only.
