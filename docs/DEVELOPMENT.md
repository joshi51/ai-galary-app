# Developer Guide

For build/run/debug commands, see the [README](../README.md). This document is about the codebase
itself: how it's organized, the conventions it follows, and how to extend it safely.

## Module graph

```
:app                        Application/Activity, Hilt wiring, NavHost, theme — depends on everything

:core:common                Logger, AppResult/AppError, AppDispatchers — pure Kotlin, zero Android dep
:core:ui                    Shared Compose theme, components, navigation destinations

:domain                     Use cases, domain models, repository interfaces — pure Kotlin, zero Android dep
                             (verify this stays true: `grep -rn "import android\." domain/src/main` should be empty)

:data:preferences           DataStore-backed SettingsRepository impl
:data:database               Room: entities, DAOs, AppDatabase + migrations, repository impls
:data:media                  MediaStore access, WorkManager workers/schedulers, background-pipeline chaining

:ml:vision                   ML Kit face detection
:ml:embeddings                TFLite face-embedding (FaceNet) + similarity-embedding (MobileNetV3) generators,
                              model downloaders

:llm:orchestration            Plain Kotlin: ToolRegistry-adjacent grammar/parsing/retry-loop logic, no native code
:llm:runtime                  llama.cpp JNI bridge, Android-side model download/lifecycle

:tools                        Plain Kotlin: the five LLM-callable Tool implementations + validation —
                               the LLM's only interface to the rest of the app
:fsops                        The only module with real filesystem/MediaStore write access:
                               PlanValidator, PlanExecutor (also implements undo)

:feature:home / :photos / :people / :search / :settings
                               One module per top-level screen. :feature:search also owns the
                               organization-review and operation-history screens (reached from
                               Search's NL entry point, not a separate nav route).
```

Dependencies flow inward only: `:app` → `:feature:*` → `:domain` ← `:data:*`/`:ml:*`/`:llm:*`/`:tools`/`:fsops`.
`:domain` never depends on anything Android-specific; every other module implements a `:domain`
interface and gets wired in via Hilt `@Binds`/`@Provides` at the `:app` graph level, which is why
e.g. `:feature:settings` can inject `EmbeddingModelDownloader` (a `:domain` interface) without ever
depending on `:ml:embeddings` (the module that implements it).

## Conventions

- **Repository interfaces live in `:domain`, implementations in the data-owning module.** e.g.
  `PhotoRepository` (interface, `:domain`) / `PhotoRepositoryImpl` (`:data:media`). This is what
  keeps `:domain` Android-free and lets `:feature:*` modules depend only on `:domain`.
- **Use cases are plain classes, not `@Inject`-constructed unless a ViewModel injects them
  directly.** Most are wired via `@Provides` in a `RepositoryModule`-style Hilt module (see
  `data/database/.../RepositoryModule.kt`) rather than an `@Inject` constructor — check existing
  examples in the module you're touching before picking one style.
- **Batch-processing use cases use per-item try/catch, never let one bad item abort a run.** See
  `DetectFacesUseCase`/`GenerateFaceEmbeddingsUseCase`/`HashPhotosUseCase` for the pattern: catch,
  log (never with a photo URI/path — see [PRIVACY.md](PRIVACY.md)), persist the failure, continue.
- **A pipeline stage that can permanently fail an item must exclude that item from its own
  "needs processing" query**, not just from its own re-attempt logic — this is the exact pattern
  `getPhotosNeedingFaceDetection`/`fetchPhotosNeedingSimilarityEmbedding` follow correctly and
  `getPhotosNeedingHash` didn't (fixed in Phase 12 — see
  [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md)). When adding a new background stage, model this
  explicitly, don't rely on a nullable result column alone.
- **A repository method that performs more than one related Room write must be wrapped in
  `AppDatabase.withTransaction { }`.** Every multi-step write in `PersonRepositoryImpl`,
  `OrganizationRepositoryImpl`, and `AlbumRepositoryImpl` follows this since the Phase 13 review
  (finding 1) — match it for new multi-step writes rather than reintroducing the gap.
- **The LLM never touches the filesystem or database directly.** A new AI-driven feature adds a
  `Tool` in `:tools` backed by a `:domain` use case — never a direct database/ContentResolver call
  from `:llm:orchestration` or `:llm:runtime`.
- **A destructive/modifying filesystem operation always goes through `:fsops`'s
  validate-then-execute path**, and always requires either the OS's own consent dialog
  (`MediaStore.createWriteRequest()`/`createDeleteRequest()`, API 30+) or an explicit
  `RecoverableSecurityException` catch below API 30 — never a silent write.

## Adding a new Room migration

1. Add the new column/table to the relevant `@Entity` and bump `AppDatabase`'s `version`.
2. Write a new `val MIGRATION_N_(N+1) = object : Migration(N, N + 1) { ... }` in `AppDatabase.kt`,
   following the existing migrations' style (raw `execSQL`, no destructive fallback).
3. Register it in `DatabaseModule.provideAppDatabase`'s `.addMigrations(...)` call.
4. Verify manually on-device: install the previous version, seed some data, install the new
   version over it, and confirm `PRAGMA user_version` and the app's behavior are both correct with
   no data loss — this project has no automated migration test suite yet (see
   [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) finding 4).

## Adding a new LLM tool

1. Define the tool's parameter schema and add it to `ToolRegistry`/`GrammarBuilder` (`:llm:orchestration`).
2. Implement the `Tool` interface in `:tools`, backed by a `:domain` use case — the tool itself
   should be a thin adapter, with the actual logic (and its own unit tests) in `:domain`.
3. Add validation in `ToolValidator` for every parameter — the LLM's output is untrusted input.
4. Add a few-shot example to `ToolCallLoop`'s system prompt if the tool's selection is at all
   ambiguous against existing tools (Phase 8/9 both needed this after live testing showed
   misrouting).
5. Unit test the tool's validation/dispatch (`:tools`) and the backing use case (`:domain`) — see
   [TESTING.md](TESTING.md).

## Where to look for X

| Looking for... | Start here |
|---|---|
| Why a module/dependency direction exists | [ARCHITECTURE.md](ARCHITECTURE.md) |
| What was built/verified/limited in phase N | [the plan doc](superpowers/plans/2026-08-29-local-ai-photo-manager.md)'s Phase N section |
| A known architectural weakness and its severity | [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) |
| Real measured throughput/latency numbers | [PERFORMANCE.md](PERFORMANCE.md) |
| What data leaves the device (and what doesn't) | [PRIVACY.md](PRIVACY.md) |
| What's tested and why the rest isn't | [TESTING.md](TESTING.md) |
| How to install/run the app and its models | [README.md](../README.md) and [MODEL_SETUP.md](MODEL_SETUP.md) |
