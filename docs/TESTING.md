# Testing

## Philosophy

This project deliberately tests **business logic only** — plain Kotlin domain use cases,
clustering/matching/grouping algorithms, and `:tools`/`:fsops` parameter/path validation — and
nothing else. No UI, ViewModel, DI wiring, or Room DAO tests exist, and none are planned. This is a
standing project preference (see [CLAUDE.md](../CLAUDE.md)), not an oversight:

> Basic-level testing only, scoped to business logic (e.g. `:domain` use cases,
> clustering/matching logic, `:tools` parameter validation, `:fsops` operation validation). Do not
> write tests for UI/Compose screens, ViewModels, DI wiring, or Android framework glue... Keep test
> infrastructure minimal — no elaborate test frameworks/harnesses beyond what's needed to
> unit-test plain Kotlin logic.

The practical effect: every use case, every clustering/hashing/grouping algorithm, and every
tool/filesystem validation rule is exercised against fakes with zero Android/Room/Compose
dependency — fast, deterministic, no emulator needed. Everything else (screens, ViewModels, Room
queries, WorkManager scheduling, real MediaStore/ContentResolver behavior) is verified manually,
on-device, once per phase, with the results recorded in that phase's own section of
[the plan doc](superpowers/plans/2026-08-29-local-ai-photo-manager.md) — not by an automated test
that runs on every future change.

## What this trades away, honestly

[ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) finding 2 is a real, concrete cost of this scope:
`PhotoDao.getPhotosNeedingHash()` had a query bug (never excluding permanently-failed rows) that
shipped for several phases before Phase 12's live on-device profiling caught it — a Room DAO query
is exactly the kind of code this testing scope doesn't cover. The scope is a deliberate tradeoff,
not a claim that everything it excludes is risk-free.

## Current test coverage

166 unit tests, 0 failures, across four modules:

| Module | Tests | What's covered |
|---|---|---|
| `:domain` | ~150 | Every use case (indexing diff/apply, face detection, embedding generation, clustering, search filtering, duplicate/similarity detection/grouping, organization strategies, operation history/undo), plus pure algorithms (`FaceClusterer`, `CentroidMath`, `TripClusterer`, `NearestCentroidClusterer`, `PerceptualHashCalculator`, `LocationBoundingBoxCalculator`) — all against fakes or plain data, no Android dependency |
| `:tools` | 25 | Parameter validation and dispatch for all six LLM-callable tools |
| `:llm:orchestration` | 15 | GBNF grammar generation, tool-call JSON parsing, the retry/fallback orchestration loop — against fake engines/tools, no native/JNI code |
| `:fsops` | 5 | `PlanValidator`'s source-exists/path-traversal/collision/unsupported-type rejections |

Run everything:

```bash
./gradlew :domain:test :core:common:test :tools:test :llm:orchestration:test :fsops:test
```

Run a single module (fastest while iterating on one area):

```bash
./gradlew :domain:test
```

## What's intentionally not covered, and how it's verified instead

| Layer | Why not unit tested | How it's actually verified |
|---|---|---|
| Room DAOs / migrations | Data-layer/Android framework glue, out of this project's testing scope | Manual on-device verification each phase that introduces a migration (`PRAGMA user_version`, direct `sqlite3` queries against a pulled database) — see [ENGINEERING_REVIEW.md](ENGINEERING_REVIEW.md) finding 4 for the resulting gap |
| ViewModels | Android framework glue | Manual on-device UI walkthroughs, `adb screencap`/`uiautomator dump` |
| Compose screens | UI | Same — screenshots and interaction verified per-phase |
| ML Kit / TFLite / llama.cpp native code | No real face-photo dataset available in any session to test accuracy against; the native/JNI surface specifically also has no unit-test harness in this project | Pipeline *mechanics* (decode, chunking, error handling, resumability, throughput) verified via real on-device runs against real (if face-less, synthetic) photos each relevant phase |
| WorkManager scheduling | Android framework glue | Manual on-device verification of chaining, unique-work dedup, and backoff behavior |

## Testing philosophy for new work

When adding a use case, algorithm, or `:tools`/`:fsops` validation rule: write plain-Kotlin unit
tests against fakes, following the existing per-module test file naming (`XyzUseCaseTest.kt`) and
fake-object conventions already established in each module's `src/test`. When adding a
screen/ViewModel/DAO/migration: verify it manually on-device and record what you did and found in
that phase's plan-doc entry — don't add a new test framework or harness to cover it, per the
standing project preference above.
