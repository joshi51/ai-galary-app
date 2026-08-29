# Local AI Photo Intelligence & Manager — Design

> This is the canonical project spec, saved per the `superpowers:brainstorming` skill's convention: `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.

## Overview

An Android application for privacy-first, AI-powered photo management, where **all** photo analysis and AI processing happens locally on-device.

## Core principle

- The app MUST NOT depend on paid/mandatory cloud AI APIs — no OpenAI, Anthropic, Gemini, AWS AI services, Firebase AI, or similar.
- The app must be able to operate with no internet connection once required models/dependencies are installed.

## Primary capabilities (eventual, full scope)

- Scan photos on the device
- Detect faces in photos
- Generate face embeddings locally
- Group similar faces into people (clustering)
- Let the user name people
- Search photos by person, by multiple people, by date/metadata, and via natural language
- Detect visually similar photos and exact duplicates
- Analyze image metadata
- Categorize photos and generate organization suggestions
- Let the user review/approve organization actions before anything happens
- Move/copy/rename photos only after explicit user confirmation
- Maintain an operation history with undo support for reversible operations
- Remain a fully local/private experience throughout

## Technology direction (proposed, to be evaluated — not blindly adopted)

- Kotlin, Android, Jetpack Compose
- Android Jetpack architecture, Coroutines, Flow/StateFlow
- Room (where appropriate)
- Android MediaStore for photo access
- WorkManager for background processing
- ML Kit where appropriate for on-device computer vision
- TensorFlow Lite and/or ONNX Runtime for local ML inference
- A local embedding/vector-search solution suited to Android
- A local LLM runtime for natural-language functionality

Before implementation, available options must be evaluated and trade-offs explained — no library choice is to be made blindly.

## Architecture principles

- Clean Architecture where it adds value
- MVVM or another well-justified presentation architecture
- Clear separation between UI, domain logic, data access, ML inference, and filesystem operations
- Dependency injection
- Testable business logic (structure for testability even though this project is not writing tests — see note below)
- Repository pattern where appropriate
- Background processing for expensive operations
- Incremental indexing rather than repeatedly rescanning the whole gallery

## Critical security principle

The LLM must **never** receive unrestricted filesystem access. All filesystem operations go through controlled application tools/services:

`AI proposes an action → application validates the action → user confirms destructive/modifying operations → execution layer performs the operation`

## AI principle — use the right tool for the job

Do not use an LLM for anything deterministic software can do more reliably:

| Task | Approach |
|---|---|
| File size calculations | normal code |
| Date filtering | database query |
| Duplicate detection | deterministic hashing / perceptual hashing |
| Face detection | computer vision model |
| Face similarity | embeddings / vector similarity |
| Natural language interpretation | LLM |

The LLM's job is natural-language understanding, reasoning, and orchestration — not deterministic computation.

## Development philosophy

Build incrementally, one phase at a time. For every phase: inspect existing code → explain the proposed implementation → implement the smallest production-quality increment → build/run it → fix issues → document important architectural decisions. Do not move to the next phase until the current one is stable.

## Code quality expectations

Write production-quality code. Avoid: unnecessary abstractions, premature optimization, giant classes, hardcoded paths, magic numbers, global mutable state, unnecessary dependencies, cloud dependencies, TODO-driven incomplete implementations.

## Project-specific preferences (this repo)

- Never commit anything unless explicitly asked (see [CLAUDE.md](../../../CLAUDE.md)).
- Basic-level testing only, scoped to business logic — plain Kotlin use cases/domain rules (e.g. `:domain` use cases, clustering/matching logic, `:tools` parameter validation, `:fsops` operation validation). No tests for UI/Compose, ViewModels, DI wiring, or other Android framework glue unless explicitly asked. Minimal test infrastructure — no elaborate frameworks beyond what plain-Kotlin unit tests need. This is a lighter-weight deviation from the `superpowers:test-driven-development` skill's default full-coverage TDD workflow.

## Architecture

The full architecture proposal (module structure, risk table, ML/AI stack decisions, DB schema, privacy/permissions, background scanning) is in [ARCHITECTURE.md](../../ARCHITECTURE.md).

## Plan

Implementation is broken into phases in [docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md](../plans/2026-08-29-local-ai-photo-manager.md).
