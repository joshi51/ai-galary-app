# Phase 9 — AI-Assisted Photo Organization: Design Spec

Companion spec for [the plan](../plans/2026-08-29-local-ai-photo-manager.md)'s Phase 9 entry and [ARCHITECTURE.md](../../ARCHITECTURE.md) (§2, §11, §15, §16, §19, §21). Produced 2026-09-01. Status: **Approved — ready for implementation planning.**

## 1. Scope

- Natural-language organization requests ("Organize my photos," "Organize my screenshots," "Put photos from my Goa trip into an album," "Find photos that should be archived") produce a reviewable **Organization Plan**, never an immediate filesystem change.
- Flow: user request → LLM tool call → deterministic plan generation → validation → user review → user confirmation → execution. Every stage before execution is inspectable and reversible by simply not confirming.
- Plan operations: `MOVE`, `COPY`, `RENAME`, `CREATE_FOLDER`, `CREATE_ALBUM` — matching ARCHITECTURE.md §16's already-locked `OrganizationOperation.opType` shape exactly, not extended with new types.
- A review UI supporting approve-all, reject-all, per-operation approve/reject, and editing an operation's destination/name (and, for `CREATE_ALBUM`, deselecting individual member photos).
- An execution layer (`:fsops`) that validates every operation immediately before running it — source existence, destination validity, permissions, collisions, path traversal, duplicate destinations, unsupported operation types — and never executes anything the LLM produces without going through this validation and the user's explicit confirmation.
- Out of scope for this phase (explicit, not oversights): undo/operation history beyond per-operation success/failure recording (Phase 10's job), real place-name geocoding, and any LLM-driven photo-by-photo reasoning (plan generation is deterministic `:domain` logic; the LLM only classifies the request into a category + hints).

## 2. Decisions locked by this spec

- **`CREATE_ALBUM` creates a virtual, in-app-only collection** — a new Room table (`Album`/`AlbumPhoto`), zero filesystem/MediaStore writes. `MOVE`/`COPY`/`RENAME`/`CREATE_FOLDER` remain real filesystem/MediaStore operations. This matches the app's standing principle (photos are referenced via MediaStore URI, never copied/moved without an explicit, separate user-visible reason) while still giving "put these into an album" a working, fully reversible implementation with no OS permission dance.
- **Trip identification is GPS+time clustering, not geocoding.** A place name in the request (e.g. "Goa") is used only as the resulting album's label — the app has no offline place-name-to-coordinates database and won't fabricate one. A deterministic clustering pass (structurally the same union-find approach as Phase 7's burst detection, but keyed on GPS proximity + a multi-day contiguous time window instead of perceptual-hash Hamming distance) finds candidate trip clusters; the LLM-extracted date/recency hint (or "most recent multi-day cluster" as the default) picks among them.
- **Plan generation is deterministic, LLM is a thin classifier.** The LLM's only job is producing one grammar-constrained `build_organization_plan` tool call with a `category` (`SCREENSHOTS` | `BY_DATE` | `TRIP` | `ARCHIVE`) and optional `dateHint`/`nameHint` strings. Which photos move where is decided entirely by named, testable `:domain` functions — never by the LLM reasoning over individual photos. This is the same "LLM classifies, deterministic code decides" split Phase 8 already established for search.
- **`CREATE_ALBUM` is one operation per album, not one per photo.** Its row carries the full member-photo-id set as plan-level detail. "Approve/reject/modify individual operations" means per logical action (the whole album-creation is one reviewable unit); the review UI still allows deselecting individual member photos from that set before confirming, without needing a sixth operation type or a schema change.
- **`CREATE_FOLDER` has no independent execution step.** Android's scoped storage has no primitive for creating a genuinely empty folder — a folder exists only as a byproduct of a file landing at that `RELATIVE_PATH`. `CREATE_FOLDER` operations are validated (the target path must be legal) but folded into the `MOVE` operation(s) that populate them at execution time; this is documented honestly rather than pretending an empty-folder creation API exists.
- **Archive criteria**: a screenshot older than a named, documented, untuned constant (same honest treatment as every prior phase's heuristic thresholds) **or** a non-representative member of an existing Phase 7 duplicate group. Not a blanket age cutoff — too aggressive and not what "archive" usually means to a user.
- **MOVE/RENAME on another app's MediaStore entries** use `MediaStore.createWriteRequest()` (API 30+, one system consent dialog per batch) then `ContentResolver.update()`, exactly mirroring Phase 7's `createDeleteRequest()` pattern; below API 30, a legacy `WRITE_EXTERNAL_STORAGE` + per-operation `RecoverableSecurityException` catch, same as Phase 7's fallback.
- **No new mechanism is needed to prevent arbitrary shell command execution** — it's structurally impossible already: the LLM only ever produces one grammar-constrained JSON tool call; there is no code-execution surface anywhere in `:llm:*`/`:tools`/`:fsops`. This spec states that explicitly as its answer to the requirement rather than adding defensive code against a capability that was never exposed.

## 3. Module additions

```
:fsops              (new)
  fsops.PlanValidator        — existence/collision/path-traversal/permission/unsupported-type checks
  fsops.PlanExecutor         — performs confirmed MOVE/COPY/RENAME/CREATE_ALBUM operations
  fsops.MediaStoreWriter     — createWriteRequest()/update() wrapper, RecoverableSecurityException fallback (API <30)
```

`:domain` additions:
```
domain.organization
  OrganizationCategory        — enum SCREENSHOTS | BY_DATE | TRIP | ARCHIVE
  OrganizationPlan / OrganizationOperation / OperationType (MOVE/COPY/RENAME/CREATE_FOLDER/CREATE_ALBUM)
  BuildOrganizationPlanUseCase — dispatches to one strategy function per category
  ScreenshotOrganizationStrategy / ByDateOrganizationStrategy / TripOrganizationStrategy / ArchiveOrganizationStrategy (pure functions)
  TripClusterer                — GPS+time union-find clustering (structurally mirrors Phase 7's PhotoGrouping.kt)
  OrganizationPlanRepository   — interface, implemented in :data:database
  ConfirmOrganizationPlanUseCase — applies user edits/exclusions, hands the confirmed subset to :fsops
  AlbumRepository              — interface, implemented in :data:database (Room only)
```

`:tools` additions: `BuildOrganizationPlanTool` (validates `category` against the enum, delegates to `BuildOrganizationPlanUseCase`).

`:llm:orchestration`: `GrammarBuilder` extended with a sixth alternative (`build-organization-plan-call`); `ToolOutcome` gains a `Plan(plan: OrganizationPlan, message: String)` variant.

## 4. Schema (Room migration 6 → 7)

```kotlin
// PhotoEntity addition
val relativePath: String? = null   // MediaStore RELATIVE_PATH, e.g. "DCIM/Camera/"

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

@Entity(
    tableName = "album_photos",
    primaryKeys = ["albumId", "photoId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = CASCADE),
    ],
    indices = [Index("albumId"), Index("photoId")],
)
data class AlbumPhotoEntity(val albumId: Long, val photoId: Long)

@Entity(tableName = "organization_plans")
data class OrganizationPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestText: String,
    val category: String,       // OrganizationCategory.name
    val createdAtMs: Long,
    val status: String,         // PROPOSED / PARTIALLY_APPROVED / EXECUTED
)

@Entity(
    tableName = "organization_operations",
    foreignKeys = [ForeignKey(entity = OrganizationPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = CASCADE)],
    indices = [Index("planId")],
)
data class OrganizationOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val opType: String,               // MOVE/COPY/RENAME/CREATE_FOLDER/CREATE_ALBUM
    val source: String?,              // a photo's current URI (single-photo ops); null for CREATE_FOLDER/CREATE_ALBUM
    val destination: String,          // target path (file ops) or album name (CREATE_ALBUM)
    val reason: String,
    val confidence: Float?,
    val memberPhotoIdsCsv: String?,   // CREATE_ALBUM only — comma-joined photo ids
    val reviewStatus: String,         // PENDING/APPROVED/REJECTED/EDITED
    val executionResult: String?,     // null until executed; SUCCESS/FAILURE
    val executionError: String?,
)
```

`memberPhotoIdsCsv` is deliberately a plain CSV column, not a join table, since it only exists between plan-creation and execution (at which point it becomes real `AlbumPhotoEntity` rows) — a join table would need its own review-status tracking for no benefit over a column that's write-once, read-a-few-times, then superseded.

## 5. Plan generation strategies (`:domain`, pure, unit-tested)

- **`ScreenshotOrganizationStrategy`**: `photo.filename.contains("Screenshot", ignoreCase = true)` and not already under a `Screenshots` relative path → one `CREATE_FOLDER("Pictures/Screenshots")` (if any match exists) + one `MOVE` per match, confidence `0.9`.
- **`ByDateOrganizationStrategy`**: photos whose `relativePath` is still the raw camera bucket (e.g. `DCIM/Camera/`) grouped by `yyyy-MM` of `dateTakenMs` (photos with a null `dateTakenMs` are skipped — an unknown date can't be filed by date) → one `CREATE_FOLDER("Pictures/<yyyy>/<yyyy-MM>")` per distinct month + one `MOVE` per photo, confidence `1.0`.
- **`TripClusterer`** + **`TripOrganizationStrategy`**: union-find over GPS-tagged photos, joining two photos into the same cluster when both their haversine distance is below `TRIP_DISTANCE_THRESHOLD_METERS` (named, documented, untuned constant) **and** their `dateTakenMs` gap is below `TRIP_TIME_GAP_MS` (e.g. 24h — photos more than a day apart don't chain into the same trip even if geographically close, so a recurring commute doesn't collapse into one giant "trip"). Clusters below `TRIP_MIN_PHOTOS` (e.g. 3) are discarded as noise, not a trip. The tool call's `dateHint` (parsed the same way as Phase 8's `ToolValidator.parseIsoDate`) picks the cluster overlapping that range; with no `dateHint`, the most recent cluster is picked. Result: one `CREATE_ALBUM` operation named from `nameHint` (falling back to `"Trip <start date>–<end date>"`), confidence = the fraction of cluster members within half the distance threshold of the cluster's centroid (a simple tightness proxy, not a claimed accuracy metric).
- **`ArchiveOrganizationStrategy`**: a screenshot (per the same filename heuristic) whose `dateTakenMs` is older than `ARCHIVE_SCREENSHOT_AGE_MS` (named, documented, untuned constant) → confidence `0.7`, reason `"Screenshot older than N months"`; or a photo that is a member of an existing Phase 7 `DuplicateGroupSummary` but not that group's first/representative photo → confidence `0.95`, reason `"Duplicate of another photo already in your library"`. Both funnel into `CREATE_FOLDER("Pictures/Archive")` + one `MOVE` per match.

Every strategy returns `List<OrganizationOperation>` (in-memory domain models, not yet persisted) plus enough data for `BuildOrganizationPlanUseCase` to wrap them in an `OrganizationPlan` and persist via `OrganizationPlanRepository` — persistence happens once, at plan-creation time, so the review screen reads a stable, already-built plan rather than recomputing it.

## 6. Validation (`:fsops.PlanValidator`)

Run once at plan-build time (informational, shown in the review UI as a per-operation warning) and **again, mandatorily, immediately before executing each operation** (state can change between review and confirmation — a photo could be deleted, a destination could collide from an unrelated concurrent write):

- **Source exists**: re-query `PhotoRepository.fetchById` for MOVE/COPY/RENAME sources; a `CREATE_ALBUM` operation's member ids are each re-checked the same way, with missing ones silently dropped from the album (not a hard failure — the album still gets created with whatever members remain valid) rather than failing the whole operation.
- **Destination validity / path traversal**: canonicalize the destination path and require it to resolve under an allow-list of roots (`Pictures/`, `DCIM/`) — any `..` segment or absolute-path escape is rejected outright, never "sanitized."
- **Collisions**: a destination (path + filename) that already exists in `PhotoEntity`, or is the destination of *another operation in the same plan*, invalidates the later operation (first-writer-wins within a plan, by plan order).
- **Permissions**: confirms write access is obtainable (API 30+: the write-request grant covers this URI; below 30: `WRITE_EXTERNAL_STORAGE` is held) before attempting the actual `ContentResolver` call, so a permission failure surfaces as a validation rejection, not a runtime crash mid-batch.
- **Unsupported operations**: an explicit `else -> ToolOutcome.Error`-style branch on `opType` — a value outside the five known types is rejected, never silently ignored or executed as a guess.

A failed check invalidates only that operation, per ARCHITECTURE.md §20 — the rest of the plan proceeds.

## 7. Execution (`:fsops.PlanExecutor`)

1. Filter to `reviewStatus == APPROVED` (or `EDITED`, using the user's edited destination/member set) operations only — `PENDING`/`REJECTED` never reach the executor.
2. Re-validate each (§6).
3. For `MOVE`/`RENAME` touching a photo not owned by this app: `MediaStoreWriter` requests one `MediaStore.createWriteRequest()` covering every such URI in the batch (API 30+) before any individual `ContentResolver.update()` call — one system dialog for the whole plan, not one per photo. Below API 30: attempt directly, catch `RecoverableSecurityException` per operation and surface its `IntentSender` the same way Phase 7's delete flow does.
4. `COPY` inserts a new MediaStore entry (`ContentResolver.insert`, no special consent) and streams the source's bytes into it.
5. `CREATE_ALBUM` only writes to Room (`AlbumRepository.createAlbum(name, memberPhotoIds)`) — no MediaStore/filesystem interaction.
6. Every operation's `executionResult`/`executionError` is recorded independently in `OrganizationOperationEntity`, whether it succeeded or failed — the summary shown to the user is always "N succeeded, M failed" with per-operation detail available, never a blanket success claim on partial failure (ARCHITECTURE.md §15's locked requirement).

## 8. Review UI (`:feature:photos`, new `OrganizationReviewScreen`)

Opened when a `build_organization_plan` tool call succeeds (from the Search screen's NL box, reusing Phase 8's entry point rather than adding a second one). Shows the plan's operations grouped by type, each with source/destination/reason/confidence and a checkbox; a text field to edit `destination` (MOVE/RENAME) or the album name (CREATE_ALBUM); for `CREATE_ALBUM`, a per-member-photo deselect chip row. "Approve all" / "Reject all" toolbar actions. An "Execute" action (enabled once ≥1 operation is approved) runs `:fsops.PlanExecutor` and shows the per-operation success/failure summary from §7.6.

## 9. Testing (business-logic only, per project convention)

- `ScreenshotOrganizationStrategyTest`, `ByDateOrganizationStrategyTest`, `TripClustererTest` (union-find correctness: photos within both thresholds cluster, either threshold alone doesn't, noise clusters below `TRIP_MIN_PHOTOS` are discarded), `ArchiveOrganizationStrategyTest` (age-based and duplicate-based paths, and that a group's representative is excluded).
- `PlanValidatorTest`: path-traversal rejection, same-plan destination-collision detection, missing-source rejection, unsupported-`opType` rejection.
- `BuildOrganizationPlanTool` parameter validation (unknown category string, malformed `dateHint`).
- No tests for the actual `ContentResolver`/`MediaStore` write mechanics (`MediaStoreWriter`, `PlanExecutor`'s Android-facing half) or the review UI — verified manually on-device, the same treatment Phase 7 gave its delete flow.

## 10. Known scope cuts (explicit, not oversights)

- No real place-name geocoding — trip identification is GPS+time clustering only; a place name in the request is a label, never a filter.
- No undo/operation history beyond per-operation success/failure — full undo is Phase 10.
- No LLM-driven photo-by-photo reasoning — every category's photo selection is deterministic, testable `:domain` logic.
- `CREATE_FOLDER` has no independent execution step (§2) — folded into the `MOVE`(s) that populate it, since Android's scoped storage has no empty-folder-creation primitive.
- Album membership editing in the review UI is per-plan (deselect before confirming) — editing an *already-created* album's membership later is not part of this phase.
