# Phase 7 — Duplicate and Similar-Photo Detection: Design Spec

Companion spec for [the plan](../plans/2026-08-29-local-ai-photo-manager.md)'s Phase 7 entry and [ARCHITECTURE.md](../../ARCHITECTURE.md). Produced 2026-08-30. Status: **Approved — ready for implementation planning.**

## 1. Scope

Two distinct, deterministic/local-only detection concepts, per the plan's Phase 7 deliverable:

- **Exact duplicates**: identical file content, detected via SHA-256 content hash.
- **Visually similar photos**, itself split into two sub-kinds:
  - **Near-duplicates / bursts**: near-identical frames, typically taken moments apart (e.g. a burst of shots of the same scene).
  - **Broader visual similarity**: photos of a similar scene/subject that aren't near-identical pixel-for-pixel.
- A review UI to inspect every group (exact, near-duplicate/burst, similar).
- User-confirmed deletion of selected photos within a group — never automatic.
- Documented memory/performance implications of the new pipeline stages.

No LLM involvement anywhere in this phase, per the plan's explicit requirement.

## 2. Decisions locked by this spec

- **Two-tier similarity detection**: a cheap perceptual hash (dHash, 64-bit) tier for near-duplicates/bursts, and a heavier MobileNetV3-Small TFLite embedding tier for broader visual similarity — not embeddings-only (wasteful) and not hash-only (can't capture semantic similarity).
- **Model choice: MobileNetV3-Small, TFLite feature-vector variant**, published by Google on Kaggle Models, **Apache 2.0** license — verified, not assumed (see §4).
- **Schema**: two entity pairs — `DuplicateGroup`/`DuplicateGroupMember` (exact matches) and `SimilarGroup`/`SimilarGroupMember` (near-duplicate, burst, and visually-similar groups, distinguished by a `kind` discriminator) — matching `ARCHITECTURE.md` §16's original two-concept ER model, with burst folded into `SimilarGroup` as a `kind` rather than a third table pair.
- **UI entry point**: a "Find duplicates" action on the existing `PhotosScreen` (`:feature:photos`) opening a new review screen — no new bottom-nav tab.
- **Deletion mechanism**: `MediaStore.createDeleteRequest()` (API 30+) — the OS's own confirmation dialog satisfies "explicit user confirmation" without needing Phase 9/10's `:fsops` layer. Below API 30 (app `minSdk` 26), a custom in-app confirmation dialog gates a legacy `ContentResolver.delete()` call instead, since `createDeleteRequest` isn't available pre-30.
- **Grouping algorithms are pure, unit-tested `:domain` functions** — the actual DB/Android glue (workers, DAOs) is verified manually, consistent with Phases 2–6's testing scope.

## 3. Data model (`:data:database`, Room version 5 → 6)

```kotlin
// PhotoEntity additions — both reset to null on re-upsert, same pattern as facesDetectedAt
val contentHash: String? = null       // SHA-256 hex of the file's bytes
val perceptualHash: Long? = null      // 64-bit dHash

@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentHash: String,
)

@Entity(
    tableName = "duplicate_group_members",
    foreignKeys = [
        ForeignKey(entity = DuplicateGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = CASCADE),
    ],
    indices = [Index("groupId"), Index("photoId")],
)
data class DuplicateGroupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
)

enum class SimilarGroupKind { NEAR_DUPLICATE, BURST, VISUALLY_SIMILAR }

@Entity(tableName = "similar_groups")
data class SimilarGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SimilarGroupKind,
    val avgSimilarity: Float,
)

@Entity(
    tableName = "similar_group_members",
    foreignKeys = [
        ForeignKey(entity = SimilarGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = CASCADE),
    ],
    indices = [Index("groupId"), Index("photoId")],
)
data class SimilarGroupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
    val similarityToRepresentative: Float,
)
```

`DuplicateGroupMemberEntity`/`SimilarGroupMemberEntity` key on `photoId` (a photo belongs to at most one group of each kind at a time) — the same single-ownership-via-primary-key simplification `PersonFaceEntity` uses in Phase 5, for the same reason (it catches double-assignment bugs at the schema level, and re-grouping naturally supersedes stale membership).

New index: `Index("contentHash")` on `photos` (duplicate lookup by hash).

## 4. Model evaluation (formal record, same rigor as Phase 4 §33)

**Chosen: MobileNetV3-Small, TFLite feature-vector variant.**

- Published by Google on Kaggle Models (TensorFlow Hub's successor platform) — **Apache 2.0 License**, confirmed via web search of Google's own MobileNet releases (consistent with Google's standard licensing for its official TF Hub / Kaggle Models releases).
- Input: 224×224 RGB. Output: ~1024-dim feature vector (pre-classification-head embedding), L2-normalized in `:domain` using the existing `l2Normalize` function (same treatment as FaceNet's 128-dim output in Phase 4) so cosine similarity is a plain dot product.
- **Alternatives considered:**
  - **EfficientNet-Lite0** — also available as an official Google TFLite feature-vector model, similarly licensed, but larger and slower than MobileNetV3-Small for a marginal accuracy gain not needed for "visually similar" grouping (not a fine-grained classification task). Rejected as unnecessarily heavy for this use case, mirroring Phase 4's rejection of ArcFace for being heavier than needed.
  - **Perceptual-hash-only (no embedding model)** — rejected as insufficient: catches near-duplicates and bursts but not broader semantic similarity (different photos of a similar scene/subject), which the phase's "visually similar photos" requirement explicitly asks for.

**Perceptual hash (dHash):** a public-domain, no-license-question algorithm — downsample to a 9×8 grayscale thumbnail, compare each pixel to its right neighbor to produce a 64-bit hash; similarity is Hamming distance between two hashes. Implemented directly in Kotlin, no third-party library.

## 5. Pipeline placement

- **`:ml:embeddings`** gains `ImageSimilarityEmbeddingGenerator` — same shape as `FaceNetEmbeddingGenerator` (Phase 4): decode bounded to a sane resolution → resize to 224×224 → normalize → infer → recycle immediately, one bitmap/tensor alive at a time. Reuses the same GPU→NNAPI→CPU delegate ladder (with the run-time-failure-aware retry logic from Phase 4's real bug fix), since MobileNetV3 is a TFLite model subject to the same delegate failure modes.
- **`:domain`** gains: `PerceptualHashCalculator` (pure — dHash from a grayscale pixel array, plus Hamming distance), a generalized nearest-centroid clusterer extracted from `FaceClusterer`'s algorithm shape (operating on any `FloatArray` embedding, not a face-specific type, so both face clustering and image-similarity clustering share one tested implementation), `DetectDuplicatesUseCase`, `GenerateImageSimilarityEmbeddingsUseCase`, `GroupSimilarPhotosUseCase`.
- **`:data:media`** gains `ContentHasher` (SHA-256 over the file's `InputStream` via `MessageDigest`, no ML, no Android-specific dependency beyond `ContentResolver.openInputStream`), plus new workers: `HashWorker` (computes `contentHash` + `perceptualHash` together per photo, since both need the same decoded/streamed bytes; chained off `IndexWorker`, running independently of/parallel to `FaceDetectionWorker`), `DuplicateGroupingWorker` (pure DB grouping by `contentHash`, chained off `HashWorker`), `SimilarityEmbeddingWorker` (MobileNetV3 inference, chained off `HashWorker`), `SimilarGroupingWorker` (chained off `SimilarityEmbeddingWorker`, produces both near-duplicate/burst groups from perceptual hashes and visually-similar groups from embeddings in one pass).

This extends the existing index→detect→embed→cluster chain with a parallel hash→(duplicate-group | embed→similar-group) branch, using the same chunked/resumable/checkpointed WorkManager pattern as every prior pipeline stage (§17 of `ARCHITECTURE.md`).

## 6. Grouping algorithms (`:domain`, pure and unit-tested)

- **Exact duplicates**: `SELECT contentHash, GROUP_CONCAT(mediaStoreId) FROM photos WHERE contentHash IS NOT NULL GROUP BY contentHash HAVING COUNT(*) > 1` — no clustering algorithm needed, pure SQL grouping.
- **Near-duplicate/burst**: two photos join the same `SimilarGroup` (kind `NEAR_DUPLICATE`) if their dHash Hamming distance is below `NEAR_DUPLICATE_HAMMING_THRESHOLD` (a named, documented, untuned constant — same honest treatment as Phase 5's `0.6` cosine threshold). A `NEAR_DUPLICATE` group is upgraded to kind `BURST` if every member's `dateTakenMs` falls within `BURST_TIME_WINDOW_MS` (e.g. 2000ms) of at least one other member — burst is a temporal refinement of near-duplicate, not a separate detection pass.
- **Visually similar**: the generalized nearest-centroid clusterer (§5) applied to MobileNetV3 embeddings, same greedy single-pass behavior as `FaceClusterer` — a photo joins the best-matching existing/new-this-run cluster above a similarity threshold, or seeds a new one. Threshold is a separate named constant from the face-clustering one (different embedding space, no reason to assume the same numeric threshold transfers).

## 7. UI (`:feature:photos`)

- `PhotosScreen` gains a "Find duplicates" toolbar icon, opening a new `DuplicatesScreen` (new file in `:feature:photos`, not a new module — it's a photos-management view, same home as `PhotoDetailScreen`).
- `DuplicatesScreen` has three sections (tab row or segmented control): **Exact Duplicates**, **Bursts & Near-Duplicates**, **Visually Similar** — each a list of groups, each group a horizontal strip of thumbnails plus (for similar/near-duplicate groups only, not exact) a similarity score.
- Within a group, tapping a photo toggles its selection for deletion (multi-select). A summary line tracks "N selected, ~X MB" (computed from `PhotoEntity.sizeBytes`).
- "Delete selected" triggers `MediaStore.createDeleteRequest()` on API 30+ (system confirmation dialog) or a custom confirmation `AlertDialog` + `ContentResolver.delete()` below API 30. Either path removes the underlying `IntentSenderRequest`/legacy result via `registerForActivityResult`, standard Compose+Activity Result API integration.
- After a confirmed deletion, the deleted photo's rows are removed from `photos`, cascading to its group-membership rows; a group left with fewer than 2 members is deleted (an exact/near-dup/similar "group" of one photo isn't a group).

## 8. Testing (business-logic only, per project convention)

- `PerceptualHashCalculatorTest`: identical pixel arrays → Hamming distance 0; maximally different → distance 64; symmetric distance; a real-ish near-duplicate pair (small pixel perturbation) → small nonzero distance.
- Generalized nearest-centroid clusterer: reuse `FaceClustererTest`'s existing test shapes (empty input, same-direction vectors cluster together, dissimilar vectors split, threshold sensitivity) against the extracted, type-generalized implementation — a regression-safety net proving the extraction didn't change face-clustering behavior, verified by re-running the pre-existing `FaceClustererTest` suite unchanged against the new shared implementation.
- Burst-window logic: a pure function taking a set of (dHash, dateTakenMs) pairs and returning whether the group qualifies as a burst — tested directly.
- Duplicate-grouping-by-hash: pure grouping logic (given a list of (photoId, contentHash) pairs, produce groups) — tested directly, independent of Room.
- No tests for `HashWorker`/`SimilarityEmbeddingWorker`/`SimilarGroupingWorker`/DAOs/`DuplicatesScreen`/ViewModels — verified manually on-device, per this project's standing preference.

## 9. Performance/memory documentation (explicit phase deliverable)

- Benchmark SHA-256 + dHash throughput (photos/sec) and MobileNetV3 embedding throughput (photos/sec) separately, on real decodable test images where available, same "N in T seconds, therefore roughly X/sec" format as Phase 4's embedding benchmark.
- Explicitly document: hashing operates on *every indexed photo's full file bytes*, not just detected faces — a meaningfully larger I/O and CPU cost than the face pipeline (which only processes cropped face regions), worth calling out as a real trade-off rather than glossing over. Memory discipline (one bitmap/tensor alive at a time, bounded decode resolution, immediate recycle) matches Phases 3/4's established pattern and should be verified, not just asserted, during implementation.

## 10. Migration notes

Room version 5 → 6, `MIGRATION_5_6`, adding two new column pairs on `photos` plus four new tables and their indexes — no data changes to existing rows, consistent with every prior migration's low-risk, additive-only shape.

## 11. Open items deliberately deferred (not blocking this phase)

- Full `:fsops`-validated deletion (path-traversal checks, operation history, undo) — remains Phase 9/10's responsibility; this phase's `MediaStore.createDeleteRequest()` path is a legitimate, complete, user-confirmed deletion mechanism on its own, not a stopgap requiring later rework.
- Cross-referencing similarity groups with people/faces (e.g. "similar photos of the same person") — out of scope; this phase's similarity is whole-photo, not person-aware.
- Re-clustering similarity groups from scratch after a model-version bump — same deliberately-deferred posture as Phase 5's face-clustering re-cluster-from-scratch gap (§40 of `ARCHITECTURE.md`); `SimilarGroupEntity` doesn't track a model version yet since MobileNetV3's version isn't expected to change within this phase's scope, but this is a known gap consistent with the face-clustering precedent.
