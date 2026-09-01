# Phase 13 Engineering Review

A senior/staff-level pass over the whole codebase (Phases 1–12) after implementation, covering
architecture, code quality, ML pipeline design, database design, background processing, AI
orchestration, security, privacy, error handling, performance, test coverage, observability, and
maintainability. This is not a generic checklist — every finding below was verified by reading the
actual code (and, for three of them, by reproducing the bug live on-device) rather than assumed.

For each significant issue: why it matters, severity, the fix, and whether it was actually applied.
Severity follows a simple scale: **Critical** (data loss/corruption or a security hole),
**High** (a real, reachable bug or a systemic gap), **Medium** (a real but narrow/rare-path issue),
**Low** (a legitimate improvement, not a defect).

## Issues found and fixed this phase

### 1. Multi-step Room writes were not transactional (High)
**Where:** `PersonRepositoryImpl` (merge/split/mark-incorrect/cluster-assignment),
`OrganizationRepositoryImpl.savePlan`, `AlbumRepositoryImpl.createAlbum`.

**Why it matters:** every one of these methods performs 2–4 separate `suspend` DAO calls — a read,
then one or more writes — with no atomicity. Android can kill a process at any point (low memory,
user force-close, a crash in an unrelated coroutine), and a partial write between those calls is a
real, reachable failure mode, not a theoretical one: `mergePersons` could commit
`reassignAllFaces` (moving every face to the target person) and then die before updating the
target's `centroidSum`/`memberCount` or deleting the source person — leaving two person rows
where a face-count query and a centroid-similarity query would silently disagree, with no error
surfaced anywhere. `savePlan`/`createAlbum` had the mirror-image risk: a plan or album row with no
operations/members.

**Fix applied:** wrapped every one of these methods' bodies in `AppDatabase.withTransaction { }`
(Room KTX), so each is now atomic — Room rolls the whole thing back on any exception or process
death mid-transaction. No schema change, no behavior change on the happy path.

**Verified live, on-device**, not just by inspection: split (`splitFaceIntoNewPerson`) and merge
(`mergePersons`) were both exercised through the real People UI against the same 300-face test
person from Phase 12's profiling run, with the database pulled and inspected before/after each
(including the WAL file — a pull that omits `-wal` shows stale pre-commit state and looks like a
bug when there isn't one, a real gotcha hit and resolved during this verification). Split: person
went 300→299, a new 1-face person appeared, `person_faces` stayed at 300 throughout. Merge: the two
people correctly collapsed back into one 300-face person with no orphaned row. A full
"Organize my screenschots" NL-search round trip (27 proposed operations) also completed with no
`SQLiteConstraintException` through the now-transactional `savePlan`.

### 2. `PhotoDao.getPhotosNeedingHash()` reprocessed permanently-failed photos forever (High)
Found and fixed during Phase 12 — see [PERFORMANCE.md](PERFORMANCE.md) for the full write-up and
live before/after logs. Included here because it's exactly the kind of systemic-pattern bug this
review is meant to catch: every other pipeline stage (face detection, face embeddings, similarity
embeddings) correctly excludes a permanently-failed item from being re-selected on the next pass;
hashing was the one stage that didn't follow that pattern, quietly wasting battery/CPU on every
single incremental scan for the rest of the library's life.

### 3. Three ML pipeline classes logged photo-identifying URIs (Medium)
Found and fixed during Phase 11 — see [PRIVACY.md](PRIVACY.md). `MlKitFaceDetectorImpl`,
`FaceNetEmbeddingGenerator`, and `MobileNetV3EmbeddingGenerator` each logged a bitmap-decode
failure with the photo's `content://` URI interpolated into the message — a URI that resolves
directly back to that photo. Fixed by dropping the identifier from the log line.

## Issues found, not fixed (documented, with rationale)

### 4. Room migrations have no automated test coverage (Medium)
**Where:** `AppDatabase.kt` — 7 hand-written `Migration` objects (1→2 through 7→8), zero
`MigrationTestHelper`-based tests.

**Why it matters:** every migration has been manually verified exactly once, on one AVD, against
that session's own data — a real, working verification method, but one that leaves no regression
safety net. A future schema change that silently breaks an *earlier* migration (e.g. an ALTER TABLE
typo in `MIGRATION_3_4` that only matters for a device still on schema version 3) would not be
caught by anything currently in the repo.

**Why not fixed now:** adding `androidx.room:room-testing` and a `MigrationTestHelper` suite is a
real, non-trivial addition of test infrastructure — this project's standing testing preference
(see [CLAUDE.md](../CLAUDE.md)) is "basic-level testing only, scoped to business logic... no
elaborate test frameworks/harnesses beyond what's needed to unit-test plain Kotlin logic," and a
migration-test harness is exactly the kind of infrastructure that preference is scoped to exclude.
Flagged rather than added unilaterally — a reasonable follow-up if migration regressions ever
become a real incident, not before.

**Proposed solution (if taken up):** one `MigrationTestHelperTest` per migration pair, each
asserting the pre-migration schema (built by hand) migrates to the expected post-migration schema
without a `SQLiteException`, using Room's own exported-schema JSON files (`exportSchema` is
currently `false` — would need to flip to `true` first, see finding 6).

### 5. `exportSchema = false` on `AppDatabase` (Low)
**Where:** `AppDatabase.kt` `@Database(..., exportSchema = false)`.

**Why it matters:** Room can export each schema version as a JSON file for two things this project
doesn't currently get: a machine-checkable historical record of every schema version (useful for
auditing exactly what a migration changed), and it's a prerequisite for `MigrationTestHelper`-based
tests (finding 4). With it `false`, this only exists in the hand-written `Migration` SQL and this
review's/the plan doc's prose.

**Why not fixed now:** flipping it to `true` requires picking and creating a schema-export
directory, wiring it into the KSP arguments, and committing the generated JSON files — a build
config change adjacent to, but not required by, this review; bundled with finding 4 as one future
piece of work rather than done in isolation.

### 6. No automated check that logging never includes a photo identifier (Medium)
**Why it matters:** finding 3 above was a real leak that shipped for multiple phases before this
review caught it by manual grep. The same class of mistake (interpolating a `content://` URI,
`uri`, `photoUri`, or filename into a `logger.*` call) could be reintroduced by a future change with
nothing to catch it.

**Why not fixed now:** same testing-scope rationale as finding 4 — a lint rule or CI grep check is
infrastructure, not business logic. Documented as a concrete, cheap follow-up (a one-line
`grep -rn "logger\.\(info\|warn\|error\|debug\)(.*[Uu]ri" --include=*.kt` in CI, or an Android Lint
custom rule) rather than added here.

### 7. Face detection and embedding generation are strictly sequential (Low)
**Where:** `DetectFacesUseCase`, `GenerateFaceEmbeddingsUseCase` — both chunk work in batches of
10/20 purely for progress-checkpoint granularity, never running more than one photo/face through
ML Kit or TFLite concurrently.

**Why it matters:** Phase 12's real on-device measurement found face detection at ~10.5 photos/sec
on realistic-resolution images — the single largest per-item cost in the whole pipeline. Concurrent
dispatch (e.g. `Dispatchers.Default.limitedParallelism(2)` over a chunk) could plausibly improve
that on a multi-core device.

**Why not fixed now:** neither the ML Kit `FaceDetector` client nor the shared TFLite `Interpreter`
instance's thread-safety under concurrent `process()`/`run()` calls has been verified — introducing
concurrency without that evidence would be exactly the "optimize blindly" Phase 12 was explicitly
told not to do. A future phase with time to safely verify thread-safety (or to give each concurrent
worker its own interpreter instance, at the cost of more peak memory) is the right place for this.

## Issues considered and found to be non-issues

- **Coil disk cache** — Phase 12 hypothesized Coil 3's `ImageLoader` lacked a thumbnail disk cache,
  implemented one, then verified on-device it had zero effect (Coil doesn't disk-cache local
  `content://` fetches at all) and reverted the change. Included here for completeness, not as an
  open issue — see [PERFORMANCE.md](PERFORMANCE.md).
- **SQL injection** — every dynamic `@Query` in the codebase uses Room's `:param` binding; the only
  hand-built strings are static SQL fragments (`DELETE FROM ... WHERE id NOT IN (...)`), never
  user- or LLM-derived text spliced into a query. No injection surface found.
- **Path traversal in `:fsops`** — `PlanValidator.canonicalizeOrNull` rejects any `..` segment or
  absolute path outright (never "sanitizes" one into something else), re-checked immediately before
  every execution, not just at plan-build time. Reviewed and confirmed correct.
- **TFLite interpreter lifecycle** — both embedding generators (`FaceNetEmbeddingGenerator`,
  `MobileNetV3EmbeddingGenerator`) correctly `close()` both the `Interpreter` and any GPU delegate
  on tier-downgrade/failure; no leak found.
- **WorkManager retry/backoff** — every one of the eight background-work schedulers configures
  `BackoffPolicy.EXPONENTIAL` with a 30s base and `setRequiresBatteryNotLow(true)`; a legitimate,
  correct, already-in-place design choice for battery-conscious background AI work.
- **Google Play services dependency** — ML Kit's on-device face detector requires the Google Play
  Services APK to be present at runtime (it is *not* a self-contained model bundled in the app);
  this is normal Play-Store-distribution Android behavior, not a violation of the "no mandatory
  cloud AI API" principle (nothing is called over the network), but is worth naming explicitly:
  a Play-Services-free (AOSP/GrapheneOS-style) device would need a different face-detector backend.
  Documented in [Known Limitations](#known-limitations) below rather than silently assumed away.

## Review by dimension

**Architecture** — Clean Architecture with real module boundaries (`:domain` has zero Android
dependency, verified by its `build.gradle.kts`), a genuinely swappable LLM engine behind
`LlmEngine`, and a hard boundary between the LLM (`:tools`, plain Kotlin, no filesystem access) and
the one module with real write access (`:fsops`). This is the project's strongest area — the
tool-calling/validation/execution split holds up under this review's scrutiny.

**Code quality** — consistently documented with *why*-comments (not *what*-comments), no dead code
or TODOs found in a full-repo grep, no `printStackTrace` anywhere. The one systemic gap (missing
transactions, finding 1) has now been closed.

**Android architecture** — MVVM with Hilt throughout, correct use of `viewModelScope`,
`StateFlow`/`Flow` for reactive UI state, Compose Navigation. `hiltViewModel()`'s deprecation
warning (pointing at a not-yet-stable `androidx.hilt.lifecycle.viewmodel.compose` replacement) is
cosmetic, tracked since Phase 1, still not actionable without an unstable dependency bump.

**ML pipeline** — resolution-bounded decode + immediate `recycle()` throughout, a documented
GPU→NNAPI→CPU fallback ladder (verified working live in Phase 4), lazy-singleton model clients
reused across calls. The one honest, load-bearing gap, repeated in every ML phase's own notes and
not resolved by this review either: **no real human face photo has ever been available in any
session that built this project**, so face-detection/embedding/clustering *accuracy* — as opposed
to pipeline *mechanics*, which are thoroughly verified — remains unvalidated against real faces.

**Face recognition accuracy risks** — the clustering threshold (`0.6` cosine similarity) and every
similar per-phase heuristic constant are explicitly named, documented, and untuned against a real
labeled dataset, by design (favoring precision over recall — an unmerged false-negative is a safe,
user-correctable state; a false-merge is not). This is a real accuracy risk for any real deployment,
honestly disclosed rather than hidden behind a confident-sounding default.

**Vector search** — brute-force cosine similarity in Kotlin/Room, explicitly documented as the
Phase 0 decision with `sqlite-vec` named as the upgrade path once library sizes make that necessary.
At the scale tested (up to 10,000 photos, Phase 6), this has not yet become a bottleneck.

**Database design** — 8 schema versions, consistent FK/cascade usage, indexes added exactly where
Phase 6's own `EXPLAIN QUERY PLAN` evidence showed they mattered. Finding 1 (transactions) was this
dimension's one real gap; findings 4/5 (migration tests, schema export) are the remaining, deferred
ones.

**Background processing** — WorkManager throughout, correct unique-work `KEEP` policies preventing
duplicate concurrent runs, exponential backoff, battery-aware constraints, and a chained
index→detect→embed→cluster (plus hash→group, similarity→group) pipeline that Phase 12 verified live
keeps the UI thread responsive (zero ANRs, zero dropped-frame warnings) even under a deliberate
rapid-interaction stress test during a fresh pipeline run.

**AI orchestration / tool-calling architecture** — a GBNF-grammar-constrained local LLM restricted
to five tool schemas, validated by `:tools` before any effect, with a documented
retry-once-then-fallback loop verified against real (not just unit-tested) model output in Phase 8.
The model's own tool-selection reliability for novel phrasings remains an open, honestly-tracked
limitation (Phase 8/9), not a claim of solved AI accuracy.

**Security** — reviewed for injection (none found — all bound params), path traversal (correctly
rejected), and destructive-action gating (every filesystem write requires either the OS's own
`MediaStore.createWriteRequest()` consent dialog or, below API 30, a caught
`RecoverableSecurityException` — never a silent write). `MainActivity` is `exported="true"` only
because it must be for the `LAUNCHER` intent-filter to work — standard, not a finding.

**Privacy** — see [PRIVACY.md](PRIVACY.md) for the full Phase 11 audit; nothing new found this
pass beyond confirming those findings still hold.

**Permission handling** — permissions requested only on first use of the feature that needs them
(Photos tab → media read; Settings → explicit download tap → `INTERNET`), never at launch; no
location permission exists at all (GPS search uses EXIF metadata already on indexed photos).

**Error handling** — a consistent `AppResult<T>`/`AppError` typed-result pattern in `:domain`
instead of exceptions crossing use-case boundaries; every batch-processing use case (indexing,
detection, embedding, hashing) uses a per-item try/catch so one bad photo never aborts a run.
Finding 1 was this dimension's real gap (partial-write safety); it's now closed.

**Performance** — see [PERFORMANCE.md](PERFORMANCE.md) for full Phase 12 measurements. Real,
on-device numbers exist for every metric except battery (unmeasurable on this environment's
emulator — see that doc).

**Battery usage** — every background worker requires `setRequiresBatteryNotLow(true)`; beyond that,
unmeasured (no physical device or emulator power model available in any session this project has
run in — an honest gap, not a false claim of measurement).

**Memory usage** — 199MB PSS / 313MB RSS measured live at the end of a full pipeline run over
3,322 photos with zero `OutOfMemoryError`s across every phase's on-device testing.

**Offline capability** — verified, not assumed: `INTERNET` is declared only for the two explicit
"Download" buttons; every other feature was exercised on-device with those downloads already
complete and no other network call anywhere in the dependency graph (confirmed by grep, not just
by reading the manifest). The one caveat: ML Kit's face detector itself depends on Google Play
Services being installed on the device (see the Play-services note above) — offline *operation* is
verified, offline *installability on every possible Android device* is not claimed.

**Test coverage** — 166 unit tests across `:domain`, `:tools`, `:llm:orchestration`, `:fsops`,
0 failures, covering business logic exactly where this project's stated testing scope says it
should (clustering/matching, tool/fsops validation, use-case orchestration against fakes) and
nowhere else (no UI/ViewModel/DI/Room-DAO tests, by design). See
[TESTING.md](TESTING.md) for the full breakdown and the philosophy behind the split. The
Phase 12 hashing bug (finding 2) shipped in exactly the kind of code this scope doesn't cover — a
concrete, honest illustration of that scope's real cost, not just its intended benefit.

**Observability** — structured `Logger`/logcat throughout (tagged, leveled), an `LlmTrace` channel
tracing every NL-search stage. No crash-reporting/analytics SDK exists at all (verified by grepping
every `build.gradle.kts`) — a deliberate consequence of the privacy-first design, not an oversight,
but worth naming as a real limitation for anyone trying to operate this app in production: a crash
in the field currently has no telemetry path back to a developer at all.

**Maintainability** — consistent per-phase documentation discipline (every phase's plan-doc entry
records what was built, what was verified, and what wasn't, including negative findings like the
Coil revert) is this project's standout maintainability asset — a future engineer can reconstruct
*why* almost any non-obvious decision was made without spelunking git blame.

## Known Limitations

- Face/embedding/clustering **accuracy** has never been validated against a real human face photo,
  in any session across all 13 phases — only pipeline *mechanics* (decode, throughput, error
  handling, resumability) are verified. This is the single most important caveat for anyone
  evaluating this project's AI claims.
- The `0.6` face-clustering similarity threshold and every per-phase grouping/organization heuristic
  (near-duplicate Hamming distance, burst time window, trip distance/time, screenshot age) are
  named, documented, and untuned against real data.
- Tool-selection reliability for **novel** NL-search/organize phrasings (beyond the specific example
  phrasings tuned against in Phase 8/9) is unverified; the retry→fallback safety mechanism has been
  verified to prevent an incorrect result from ever reaching the user, but doesn't make the model
  more accurate.
- Battery impact is genuinely unmeasured (no physical device available in any session).
- No automated Room migration tests exist (finding 4); no CI check against logging leaks (finding
  6); face/embedding generation is strictly sequential, not batched-concurrent (finding 7).
- ML Kit's face detector requires Google Play Services on the target device.
- No crash-reporting/telemetry path exists at all — by design, but worth knowing before relying on
  this app in a context where field crash visibility matters.
- Vector search is brute-force (Room/SQLite, not an ANN index) — fine at the library sizes tested
  (≤10,000 photos), with `sqlite-vec` named as the documented upgrade path if that changes.

## Future Roadmap

Roughly in the order a next engineer would plausibly tackle them:

1. **Validate against real photos** — the single highest-value next step: a real (consented) photo
   library to finally measure face-detection/embedding/clustering *accuracy*, not just mechanics,
   and recalibrate every untuned threshold against real precision/recall numbers.
2. **Room migration test suite** (findings 4/5) — flip `exportSchema` on, add
   `MigrationTestHelper` coverage for all 7 migrations.
3. **A logging-leak CI check** (finding 6) — cheap, high-leverage given finding 3 already happened
   once.
4. **Concurrent face detection/embedding** (finding 7) — after verifying ML Kit/TFLite
   thread-safety, or by giving each concurrent worker its own interpreter.
5. **`sqlite-vec` migration** — once a real library size makes brute-force vector search a measured
   (not hypothetical) bottleneck.
6. **A non-Play-Services face-detection backend** — if supporting GrapheneOS/AOSP-only devices
   becomes a goal.
7. **Selective/older-batch undo** — Phase 10's `operation_records` table already has everything a
   "browse and undo any past batch" UI would need; only the UI is missing.
8. **Opt-in, privacy-preserving crash reporting** — if field reliability visibility becomes a
   priority, something that keeps the zero-PII bar this project has held throughout (e.g. a local
   crash log the user can choose to export, never automatic upload).
