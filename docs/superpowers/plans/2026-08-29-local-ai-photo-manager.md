# Local AI Photo Intelligence & Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan phase-by-phase. **Deviation from the standard writing-plans format:** this project has a standing "basic-level testing only, scoped to business logic" preference (see Global Constraints), so tasks below are NOT structured as full TDD red/green/commit steps per the skill's default template — each phase is a scoped deliverable with a manual build/run verification gate, plus simple unit tests where the phase produces plain-Kotlin business logic (domain use cases, clustering/matching, tool/fsops validation).

**Goal:** Build a privacy-first, fully on-device Android photo management app with local face recognition, search, duplicate detection, natural-language search, and AI-assisted (user-confirmed) organization.

**Architecture:** Clean Architecture, modularized by layer/feature (`:core`, `:domain`, `:data:*`, `:feature:*`, `:ml:*`, `:llm:*`, `:tools`, `:fsops`), MVVM presentation via Compose, Hilt DI, Coroutines/Flow. The LLM never touches the filesystem or database directly — it only calls a validated `:tools` layer, and destructive operations always require explicit user confirmation before `:fsops` executes them. Full detail in [ARCHITECTURE.md](../../ARCHITECTURE.md).

**Tech Stack:** Kotlin, Jetpack Compose, Room, MediaStore, WorkManager, ML Kit (face detection), TFLite (face embeddings, image similarity — model TBD per Phase 4/7 evaluation), llama.cpp via JNI (local LLM, GGUF models — no Google-branded models), Room/SQLite brute-force vector search (upgradeable to `sqlite-vec`).

**Spec:** [docs/superpowers/specs/2026-08-29-local-ai-photo-manager-design.md](../specs/2026-08-29-local-ai-photo-manager-design.md)

> This is the canonical implementation plan, saved per the `superpowers:writing-plans` skill's `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md` convention. Keep this file — not a root-level copy — up to date as phases progress.

## Global Constraints

- No paid/mandatory cloud AI APIs (no OpenAI, Anthropic, Gemini, AWS AI, Firebase AI) — every AI feature runs fully on-device.
- App must operate with no internet connection after required models are installed (models are downloaded on first run, not bundled in the APK).
- **Basic-level testing only, scoped to business logic** — simple unit tests for plain Kotlin domain/use-case logic only (e.g. `:domain`, clustering/matching, `:tools`/`:fsops` validation); no UI/Compose/ViewModel/DI tests. Overrides the default full-coverage TDD task structure (see [CLAUDE.md](../../../CLAUDE.md)).
- Never commit to git unless explicitly asked in the current request.
- LLM never gets direct filesystem/database access — only validated `:tools` calls.
- Destructive/modifying filesystem operations always require explicit user confirmation.
- No library/model chosen without evaluating trade-offs first (see ARCHITECTURE.md decision log for LLM runtime, embedding models, etc.).
- Build incrementally: one phase must be stable (compiles, runs, manually verified) before the next starts.

---

### Phase 0: Master project instruction
**Status:** Done

**Deliverable:** Architecture-only response, no code. Complete architecture, major technical risks, recommended local ML/AI stack, development phases, database/indexing strategy, privacy & permissions approach, background photo scanning approach.

**Files:**
- Created: `ARCHITECTURE.md` (repo root), this spec doc, this plan doc

Delivered in-chat and in `ARCHITECTURE.md`. Key decisions locked: module structure, ML Kit for face detection, llama.cpp (GGUF models, no Google-branded LLM) for the local LLM, Room/SQLite brute-force vector search with `sqlite-vec` as the documented upgrade path, models downloaded on first run rather than bundled in the APK.

### Phase 0.5: Architecture finalization
**Status:** Done

**Deliverable:** No code. High-level architecture diagram, Android module/package structure, data-flow diagrams for photo ingestion / face recognition / NL search / organization actions, database schema, background-processing architecture, ML model execution architecture, security & permission model. For each major component: responsibility, inputs, outputs, dependencies, on-device or not, failure scenarios. Identify which architectural decisions should be locked now vs. deliberately left replaceable.

Delivered in [ARCHITECTURE.md](../../ARCHITECTURE.md) §10–21 (canonical, up-to-date version — this plan copy just tracks status).

### Phase 1: Basic Android shell
**Status:** Done

**Deliverable:** Kotlin, Jetpack Compose, modern Android architecture, DI, navigation, app/theme structure, repo/domain/data separation where appropriate, logging, error handling. Screens: Home, Photos, People, Search, Settings. No AI functionality.

**What was built:**
- Gradle multi-module project (Kotlin 2.3.20, AGP 9.3.2 with built-in Kotlin support, KSP 2.3.11, compileSdk/targetSdk 37, minSdk 26): `:app`, `:core:common`, `:core:ui`, `:domain`, `:data:preferences`, `:feature:home`, `:feature:photos`, `:feature:people`, `:feature:search`, `:feature:settings` — matching the module boundaries locked in [ARCHITECTURE.md](../../ARCHITECTURE.md) §11 (the `:data:media`, `:ml:*`, `:llm:*`, `:tools`, `:fsops` modules are deferred to the phases that need them).
- DI via Hilt (`PhotoManagerApplication` + `MainActivity` entry points, `CoreModule`/`PreferencesModule` for bindings).
- Navigation via Compose Navigation + a bottom nav bar (`PhotoManagerNavHost`, `TopLevelDestination`), all 5 screens wired and reachable.
- App/theme structure: Material3 theme (`PhotoManagerTheme`) with light/dark color schemes.
- Repository/domain/data separation demonstrated end-to-end with a real (not stubbed) feature: `domain.settings.SettingsRepository` (interface) → `data.preferences.DataStoreSettingsRepository` (DataStore-backed impl) → `SettingsViewModel`/`MainViewModel` → Settings screen theme picker (Light/Dark/System), verified round-tripping on-device.
- Logging: `core.common.Logger` interface, `AndroidLogger` impl bound via Hilt, plus an uncaught-exception logger installed in `Application.onCreate()` for error handling.
- Error handling: `AppResult`/`AppError` typed result wrapper in `:core:common` for future use-case error propagation.
- Basic testing infrastructure: JUnit wired into `:domain` and `:core:common`; one real unit-test class (`ThemeModeTest`, 3 cases) covering the one piece of genuine business logic this phase produced (`ThemeMode.resolveIsDark`) — no UI/ViewModel/DI tests, per the project's basic-level-testing-on-business-logic-only preference. Phase 1 otherwise has no domain logic to test (indexing/clustering/tool-validation logic arrives in later phases).

**Verification performed:**
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- `./gradlew :domain:test :core:common:test` — 3/3 tests passed.
- Installed and launched on an Android 15 (API 35) emulator (arm64, Google Play system image): app displayed with no crash, verified via `adb logcat` (no FATAL/AndroidRuntime errors) and screenshots. Navigated Home → Photos → People → Search → Settings; toggled the theme to Dark and confirmed the whole app (Material3 colors) switched live, then reset to System — confirming the domain/data/presentation wiring works, not just that screens render.

**Known follow-ups (non-blocking):** `hiltViewModel()` in `feature:settings/SettingsScreen.kt` emits a deprecation warning pointing to a newer `androidx.hilt.lifecycle.viewmodel.compose` package not yet available in the pinned `hilt-navigation-compose` version — cosmetic, revisit when that artifact stabilizes.

### Phase 2: Photo indexing
**Status:** Not Started

**Deliverable:** Discover photos via Android MediaStore. Request appropriate permissions. Store URIs/references, not copies. Extract metadata: URI, filename, MIME type, file size, width/height, creation/modification date, EXIF, location where available. Store metadata locally (Room). Incremental indexing (detect new/deleted photos, avoid rescanning unchanged ones). Background indexing via WorkManager with progress reporting and resume-after-interruption. Photos screen showing indexed photos.

**Verification gate:** Manually measure and report approximate indexing performance on the test device/emulator. No face recognition yet.

### Phase 3: Face detection
**Status:** Not Started

**Deliverable:** On-device face detection only (ML Kit, no remote calls). Detect all faces per indexed photo; store photo ID, bounding box, detection confidence, orientation/rotation info. Background processing, non-blocking, skip unchanged images, resumable. Debug UI to open a photo and view detected bounding boxes.

**Verification gate:** Manually verify against: memory usage, large images, device rotation, corrupted images, photos with many faces, background processing, cancellation. No person identification/grouping yet.

### Phase 4: Face embeddings
**Status:** Not Started

**Deliverable:** Evaluate local face-embedding model options (accuracy, model size, inference speed, Android compatibility, CPU/GPU/NPU support, licensing, offline capability, quantization) and choose one with justification. Implement pipeline: photo → detected face → crop/align → local embedding model → normalized embedding → local storage. Embeddings never leave the device; stored efficiently; versioned by model version (regenerate-on-change support); avoid reprocessing the same face; efficient model loading; avoid holding large numbers of tensors in memory.

**Verification gate:** Manual benchmark of embedding generation. Document model + license. No automatic person grouping yet.

### Phase 5: Automatic people discovery (clustering)
**Status:** Not Started

**Deliverable:** Cluster face embeddings into likely individuals. Support multiple initial clusters per person, merging people, splitting incorrectly grouped people, marking a face as incorrect, leaving unknown people unnamed, recalculating clusters, storing the clustering algorithm/model version. People screen: person/cluster, representative photos, photo count, face count, confidence/quality info. UI actions: "Name this person," "Merge with another person." No automatic name assignment.

### Phase 6: Search by people
**Status:** Not Started

**Deliverable:** Deterministic (non-LLM) database/vector queries. Support: photos of a selected person, photos with multiple selected people, person + date filtering, person + folder/location filtering where metadata exists. Pagination/lazy loading, appropriate indexes.

**Verification gate:** Manually validate against a simulated large dataset for responsiveness.

### Phase 7: Duplicate and similar-photo detection
**Status:** Not Started

**Deliverable:** Exact duplicates (deterministic content hash) and visually similar photos (evaluate an appropriate local image embedding model) as two distinct concepts. No LLM. Duplicate groups, similarity scores, visually-similar groupings, burst-photo grouping where feasible. UI to inspect groups. Never auto-delete — deletion always requires explicit user confirmation.

**Verification gate:** Document memory/performance implications.

### Phase 8: Natural-language AI search
**Status:** Not Started

**Deliverable:** Local/on-device LLM (llama.cpp) translates natural language into structured search intent and/or invokes controlled application tools; never directly accesses the filesystem or database. Controlled tool layer: `search_people`, `search_photos`, `search_by_date`, `search_by_location`, `find_duplicates`, `find_similar_photos`, `get_photo_metadata`, `get_storage_statistics`. Tool layer validates all parameters; LLM produces structured tool calls, not arbitrary code. Architect so the LLM engine/model is swappable later. Logging/tracing of query → interpreted intent → selected tool → parameters → tool result → final response, without logging unnecessary private photo content.

### Phase 9: AI-assisted photo organization
**Status:** Not Started

**Deliverable:** Requests like "Organize my photos," "Put photos from my Goa trip into an album." AI never modifies files directly. Flow: user request → AI analysis → Organization Plan → validation → user review → user confirmation → execution. Plan operations: `MOVE`, `COPY`, `RENAME`, `CREATE_FOLDER`, `CREATE_ALBUM`, each with source, destination, reason, confidence where applicable. Review UI: approve all, reject all, approve individual operations, modify an operation. Execution layer (`:fsops`) validates: source exists, destination valid, permissions, collisions, path traversal, duplicate destinations, unsupported operations. LLM never executes arbitrary shell commands.

### Phase 10: Undo / operation history
**Status:** Not Started

**Deliverable:** Every modifying operation generates a record: operation ID, timestamp, operation type, source, destination, previous state where required, result, failure reason, reversible/non-reversible status. "Undo last organization" and history inspection. Safe partial-failure handling (e.g. 20 requested moves, 2 fail → report 18 success / 2 failed, never claim full success; retain enough info to safely undo the successful ones). Transaction-like workflow despite filesystem operations not being inherently transactional.

### Phase 11: Privacy and security hardening
**Status:** Not Started

**Deliverable:** Full privacy audit. Verify: photos never leave the device, face embeddings never leave the device, LLM processing is local, logs contain no sensitive photo data, analytics/telemetry disabled by default, no unnecessary internet permission, permissions requested only when required, database contents protected appropriately, temporary image files cleaned up, cached thumbnails handled securely. Privacy section in Settings explaining exactly what stays on-device. Diagnostics screen: AI model status, local-processing status, indexed photo count, face count, people count, database size, model versions. Do not claim "fully offline" without having actually verified it.

### Phase 12: Performance optimization
**Status:** Not Started

**Deliverable:** Profile with a realistic photo library first, then optimize based on measurements (not blindly). Measure: initial indexing time, incremental indexing time, face-detection throughput, embedding generation time, memory usage, database size, search latency, LLM response latency, battery impact, storage consumption. Optimize: image decoding, bitmap memory, model loading, batch processing, database queries, vector search, thumbnail generation, background work. App must stay responsive during background AI processing.

### Phase 13: Final portfolio-grade review
**Status:** Not Started

**Deliverable:** Senior/staff-level engineering review covering: architecture, code quality, Android architecture, ML pipeline, face-recognition accuracy risks, vector search, database design, background processing, AI orchestration, tool-calling architecture, security, privacy, permission handling, error handling, performance, battery usage, memory usage, offline capability, observability, maintainability (test coverage dropped per Global Constraints). For every significant issue: explain why it matters, rate severity, propose a solution, implement the fix if safe to do so. Produce: architecture documentation, setup instructions, local model installation instructions, developer documentation, performance benchmark results, privacy documentation, known limitations, future roadmap. Finish with a portfolio-quality README — no unsupported claims about AI accuracy or offline operation.
