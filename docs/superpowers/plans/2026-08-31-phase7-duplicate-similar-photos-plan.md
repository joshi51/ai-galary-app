# Phase 7 — Duplicate and Similar-Photo Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Deviation from the standard writing-plans template:** this project has a standing "basic-level testing only, scoped to business logic" preference. Only pure-Kotlin domain logic (hashing math, grouping algorithms, use-case orchestration against fakes) gets full TDD steps. Room DAOs, repository impls, TFLite wrappers, WorkManager workers/schedulers, ViewModels, and Compose UI are verified manually on-device, consistent with Phases 2–6.

**Goal:** Detect exact-duplicate and visually-similar photos (including bursts) entirely on-device, let the user inspect groups, and delete selected photos only with explicit confirmation.

**Architecture:** Extends the existing index→detect→embed→cluster WorkManager pipeline with a parallel branch: index → hash (SHA-256 + perceptual dHash) → [duplicate/near-dup/burst grouping via pure hash comparison] and → [MobileNetV3 image-embedding → visually-similar grouping via a generalized nearest-centroid clusterer shared with Phase 5's face clustering]. A new `DuplicatesScreen` in `:feature:photos` reviews all group kinds; deletion uses `MediaStore.createDeleteRequest()` (API 30+) or a `RecoverableSecurityException`-driven consent flow (API 29) or a `WRITE_EXTERNAL_STORAGE`-gated legacy delete (API 26–28).

**Tech Stack:** Kotlin, Room (migration 5→6), TensorFlow Lite (MobileNetV3-Small, bundled as an app asset — not downloaded, see Task 4), WorkManager, Jetpack Compose, Hilt.

**Spec:** [docs/superpowers/specs/2026-08-30-phase7-duplicate-similar-photos-design.md](../specs/2026-08-30-phase7-duplicate-similar-photos-design.md)

## Global Constraints

- No LLM anywhere in this phase — exact duplicates use SHA-256; similarity uses perceptual hashing + a local TFLite embedding model.
- Never auto-delete. Every deletion requires explicit user confirmation (the OS system dialog on API 29+, a custom in-app dialog + `WRITE_EXTERNAL_STORAGE` on API 26–28).
- Never commit to git unless explicitly asked in the current request — every task below ends with a `git add`/`git commit` step; skip that step if the calling context says not to commit.
- Basic-level testing only, scoped to business logic (see the deviation note above).
- Build incrementally: each task should compile (`./gradlew :app:assembleDebug`) before moving to the next.
- **Model sourcing decision (locked for this phase):** MobileNetV3-Small is bundled as an app asset, converted once from `tf.keras.applications` (Apache 2.0), not downloaded on first run — no cleanly-licensed, unauthenticated direct-download URL exists for it (verified during brainstorming: TF Hub's old URL 404s, Kaggle requires authenticated API access, community mirrors lack clear per-file licensing).

---

### Task 1: Schema — hash columns, duplicate/similar group tables, migration 5→6

**Files:**
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/DuplicateGroupEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/DuplicateGroupMemberEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/SimilarGroupEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/SimilarGroupMemberEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/DuplicateGroupDao.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/SimilarGroupDao.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`

**Interfaces:**
- Produces: `photos.contentHash`/`photos.perceptualHash`/`photos.hashError` columns; `DuplicateGroupDao`, `SimilarGroupDao` — consumed by Task 5's `PhotoGroupRepositoryImpl`.

No automated test — schema/DAO-only, verified manually per this plan's testing-scope note (Task 8 does the full on-device pass).

- [ ] **Step 1: Add hash columns to `PhotoEntity`**

In `PhotoEntity.kt`, add to the `indices` list (after the Phase 6 additions):

```kotlin
        Index("contentHash"),
```

and add these fields to the data class, after `faceDetectionError`:

```kotlin
    /** Null until hashing has run for this row; reset to null whenever the row is re-upserted. */
    val contentHash: String? = null,
    val perceptualHash: Long? = null,
    val hashError: String? = null,
```

- [ ] **Step 2: Create the duplicate-group entities**

`DuplicateGroupEntity.kt`:

```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A group of photos that share an identical [contentHash] (exact byte-for-byte duplicates). */
@Entity(tableName = "duplicate_groups")
data class DuplicateGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentHash: String,
)
```

`DuplicateGroupMemberEntity.kt`:

```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [photoId] is the primary key — a photo belongs to at most one duplicate group at a time,
 * the same single-ownership-via-primary-key simplification [com.localphotoai.photomanager.data.database.entity.PersonFaceEntity]
 * uses: re-grouping naturally supersedes stale membership rather than needing explicit cleanup.
 */
@Entity(
    tableName = "duplicate_group_members",
    foreignKeys = [
        ForeignKey(entity = DuplicateGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("groupId")],
)
data class DuplicateGroupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
)
```

- [ ] **Step 3: Create the similar-group entities**

`SimilarGroupEntity.kt`:

```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SimilarGroupKind { NEAR_DUPLICATE, BURST, VISUALLY_SIMILAR }

/**
 * A group of photos that are similar but not byte-identical. [kind] distinguishes near-duplicate
 * (perceptual-hash match), burst (near-duplicate + taken moments apart), and visually-similar
 * (broader, embedding-based) groups within one shape, per the design spec's decision to avoid a
 * third near-identical table pair.
 */
@Entity(tableName = "similar_groups")
data class SimilarGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SimilarGroupKind,
    val avgSimilarity: Float,
)
```

`SimilarGroupMemberEntity.kt`:

```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "similar_group_members",
    foreignKeys = [
        ForeignKey(entity = SimilarGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("groupId")],
)
data class SimilarGroupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
    val similarityToRepresentative: Float,
)
```

- [ ] **Step 4: Create `DuplicateGroupDao.kt`**

```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupEntity
import com.localphotoai.photomanager.data.database.entity.DuplicateGroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DuplicateGroupDao {

    @Query(
        """
        SELECT dgm.groupId AS groupId, dg.contentHash AS contentHash,
               GROUP_CONCAT(dgm.photoId) AS photoIdsCsv, SUM(p.sizeBytes) AS totalSizeBytes
        FROM duplicate_group_members dgm
        JOIN duplicate_groups dg ON dg.id = dgm.groupId
        JOIN photos p ON p.mediaStoreId = dgm.photoId
        GROUP BY dgm.groupId
        """,
    )
    fun observeGroups(): Flow<List<DuplicateGroupRow>>

    @Query("DELETE FROM duplicate_groups")
    suspend fun deleteAllGroups()

    @Insert
    suspend fun insertGroup(group: DuplicateGroupEntity): Long

    @Upsert
    suspend fun upsertMember(member: DuplicateGroupMemberEntity)

    @Query("DELETE FROM duplicate_group_members WHERE photoId = :photoId")
    suspend fun removeMember(photoId: Long)

    @Query("SELECT groupId FROM duplicate_group_members WHERE groupId = :groupId LIMIT 1")
    suspend fun anyMemberOfGroup(groupId: Long): Long?

    @Query("DELETE FROM duplicate_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    /** Replaces every duplicate group in one transaction — a full re-run supersedes prior groupings. */
    @Transaction
    suspend fun replaceAllGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
        deleteAllGroups()
        for ((hash, photoIds) in photoIdGroupsByHash) {
            if (photoIds.size < 2) continue
            val groupId = insertGroup(DuplicateGroupEntity(contentHash = hash))
            for (photoId in photoIds) upsertMember(DuplicateGroupMemberEntity(photoId, groupId))
        }
    }

    data class DuplicateGroupRow(
        val groupId: Long,
        val contentHash: String,
        val photoIdsCsv: String,
        val totalSizeBytes: Long,
    )
}
```

- [ ] **Step 5: Create `SimilarGroupDao.kt`**

```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.SimilarGroupEntity
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind
import com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SimilarGroupDao {

    @Query(
        """
        SELECT sgm.groupId AS groupId, sg.kind AS kind, sg.avgSimilarity AS avgSimilarity,
               GROUP_CONCAT(sgm.photoId) AS photoIdsCsv
        FROM similar_group_members sgm
        JOIN similar_groups sg ON sg.id = sgm.groupId
        WHERE sg.kind = :kind
        GROUP BY sgm.groupId
        """,
    )
    fun observeGroupsByKind(kind: SimilarGroupKind): Flow<List<SimilarGroupRow>>

    @Query("DELETE FROM similar_groups WHERE kind = :kind")
    suspend fun deleteGroupsByKind(kind: SimilarGroupKind)

    @Insert
    suspend fun insertGroup(group: SimilarGroupEntity): Long

    @Upsert
    suspend fun upsertMember(member: SimilarGroupMemberEntity)

    @Query("DELETE FROM similar_group_members WHERE photoId = :photoId")
    suspend fun removeMember(photoId: Long)

    @Query(
        "SELECT sg.id AS groupId, sg.kind AS kind FROM similar_group_members sgm " +
            "JOIN similar_groups sg ON sg.id = sgm.groupId WHERE sgm.photoId = :photoId",
    )
    suspend fun findGroupsForPhoto(photoId: Long): List<GroupIdAndKind>

    /** Replaces every group of [kind] in one transaction, keyed by an opaque cluster index (0, 1, 2, ...). */
    @Transaction
    suspend fun replaceGroupsOfKind(kind: SimilarGroupKind, groups: Map<Int, List<Pair<Long, Float>>>) {
        deleteGroupsByKind(kind)
        for ((_, members) in groups) {
            if (members.size < 2) continue
            val avg = members.map { it.second }.average().toFloat()
            val groupId = insertGroup(SimilarGroupEntity(kind = kind, avgSimilarity = avg))
            for ((photoId, similarity) in members) {
                upsertMember(SimilarGroupMemberEntity(photoId, groupId, similarity))
            }
        }
    }

    data class SimilarGroupRow(
        val groupId: Long,
        val kind: SimilarGroupKind,
        val avgSimilarity: Float,
        val photoIdsCsv: String,
    )

    data class GroupIdAndKind(val groupId: Long, val kind: SimilarGroupKind)
}
```

- [ ] **Step 6: Register everything on `AppDatabase` and bump the version**

In `AppDatabase.kt`, add imports for the four new entities and two new DAOs, add them to the `@Database(entities = [...])` list, bump `version = 5` to `version = 6`, add the four new `abstract fun ...Dao(): ...Dao` declarations, and append this migration after `MIGRATION_4_5`:

```kotlin

/** Phase 7: adds duplicate/near-duplicate/burst/similar detection — new `photos` hash columns
 *  and four new group tables. No data changes to existing rows. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN contentHash TEXT")
        db.execSQL("ALTER TABLE photos ADD COLUMN perceptualHash INTEGER")
        db.execSQL("ALTER TABLE photos ADD COLUMN hashError TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_contentHash ON photos(contentHash)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS duplicate_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                contentHash TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS duplicate_group_members (
                photoId INTEGER PRIMARY KEY NOT NULL,
                groupId INTEGER NOT NULL,
                FOREIGN KEY(groupId) REFERENCES duplicate_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_group_members_groupId ON duplicate_group_members(groupId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similar_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL,
                avgSimilarity REAL NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS similar_group_members (
                photoId INTEGER PRIMARY KEY NOT NULL,
                groupId INTEGER NOT NULL,
                similarityToRepresentative REAL NOT NULL,
                FOREIGN KEY(groupId) REFERENCES similar_groups(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_similar_group_members_groupId ON similar_group_members(groupId)")
    }
}
```

- [ ] **Step 7: Wire the migration and DAOs into `DatabaseModule.kt`**

Add `MIGRATION_5_6` to the `.addMigrations(...)` call, and add `@Provides fun provideDuplicateGroupDao(database: AppDatabase): DuplicateGroupDao = database.duplicateGroupDao()` and the equivalent for `SimilarGroupDao`, matching the existing `provideSearchDao` pattern.

- [ ] **Step 8: Build**

Run: `./gradlew :data:database:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add data/database/src/main/kotlin/com/localphotoai/photomanager/data/database
git commit -m "feat(db): add duplicate/similar-photo schema, migration 5->6"
```

---

### Task 2: Domain — generalized clusterer extraction, perceptual hashing, pure grouping

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/clustering/NearestCentroidClusterer.kt`
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/person/FaceClusterer.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/PerceptualHashCalculator.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoGrouping.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/clustering/NearestCentroidClustererTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/PerceptualHashCalculatorTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoGroupingTest.kt`

**Interfaces:**
- Produces: `NearestCentroidClusterer.cluster(items: List<EmbeddingForClustering>, existingClusters: List<ExistingCentroid>, similarityThreshold: Float): NearestCentroidResult`; `PerceptualHashCalculator.dHash(grayscalePixels: IntArray): Long` and `.hammingDistance(a: Long, b: Long): Int`; `groupByExactHash(hashes: List<PhotoHashInput>): Map<String, List<Long>>`; `groupNearDuplicatesAndBursts(hashes: List<PhotoHashInput>, hammingThreshold: Int, burstWindowMs: Long): List<Pair<SimilarGroupKindResult, List<Long>>>` — all consumed by Task 3's use cases.
- Consumes: nothing new — `l2Normalize`/`addVector`/`cosineSimilarity` already exist in `:domain`.

- [ ] **Step 1: Write the failing tests**

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/clustering/NearestCentroidClustererTest.kt` — this is Phase 5's `FaceClustererTest` suite, re-pointed at the generalized implementation (proves the extraction preserves behavior exactly):

```kotlin
package com.localphotoai.photomanager.domain.clustering

import com.localphotoai.photomanager.domain.face.l2Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun unit(x: Float, y: Float) = l2Normalize(floatArrayOf(x, y))
private fun item(id: Long, x: Float, y: Float) = EmbeddingForClustering(id, unit(x, y))

class NearestCentroidClustererTest {

    @Test
    fun `empty input produces an empty result`() {
        val result = NearestCentroidClusterer.cluster(emptyList(), emptyList(), similarityThreshold = 0.6f)
        assertTrue(result.assignments.isEmpty())
        assertEquals(0, result.newClusterCount)
    }

    @Test
    fun `two items pointing the same direction form one new cluster together`() {
        val a = item(1L, 1f, 0f)
        val b = item(2L, 0.99f, 0.01f)
        val result = NearestCentroidClusterer.cluster(listOf(a, b), emptyList(), similarityThreshold = 0.6f)
        assertEquals(1, result.newClusterCount)
        val assignments = result.assignments.filterIsInstance<ClusterAssignment.ToNew>()
        assertEquals(2, assignments.size)
        assertEquals(assignments[0].newClusterIndex, assignments[1].newClusterIndex)
    }

    @Test
    fun `two items pointing in very different directions form separate new clusters`() {
        val a = item(1L, 1f, 0f)
        val b = item(2L, 0f, 1f)
        val result = NearestCentroidClusterer.cluster(listOf(a, b), emptyList(), similarityThreshold = 0.6f)
        assertEquals(2, result.newClusterCount)
        val assignments = result.assignments.filterIsInstance<ClusterAssignment.ToNew>()
        assertTrue(assignments[0].newClusterIndex != assignments[1].newClusterIndex)
    }

    @Test
    fun `an item matching an existing cluster is assigned to it, not a new cluster`() {
        val existing = ExistingCentroid(groupId = 42L, centroidSum = unit(1f, 0f))
        val matching = item(1L, 0.98f, 0.02f)
        val result = NearestCentroidClusterer.cluster(listOf(matching), listOf(existing), similarityThreshold = 0.6f)
        assertEquals(0, result.newClusterCount)
        val assignment = result.assignments.single() as ClusterAssignment.ToExisting
        assertEquals(42L, assignment.groupId)
        assertTrue(assignment.confidence >= 0.6f)
    }

    @Test
    fun `a stricter threshold requires closer similarity before assigning to an existing cluster`() {
        val existing = ExistingCentroid(groupId = 42L, centroidSum = unit(1f, 0f))
        val looselyMatching = item(1L, 0.9f, 0.44f)
        val lenient = NearestCentroidClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.6f)
        val strict = NearestCentroidClusterer.cluster(listOf(looselyMatching), listOf(existing), similarityThreshold = 0.99f)
        assertTrue(lenient.assignments.single() is ClusterAssignment.ToExisting)
        assertTrue(strict.assignments.single() is ClusterAssignment.ToNew)
    }
}
```

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/PerceptualHashCalculatorTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashCalculatorTest {

    @Test
    fun `identical pixel arrays produce the same hash and zero Hamming distance`() {
        val pixels = IntArray(72) { it * 3 }
        val a = PerceptualHashCalculator.dHash(pixels)
        val b = PerceptualHashCalculator.dHash(pixels)
        assertEquals(a, b)
        assertEquals(0, PerceptualHashCalculator.hammingDistance(a, b))
    }

    @Test
    fun `a strictly increasing gradient produces a hash of all-set bits`() {
        // dHash compares each pixel to its right neighbor; a strictly increasing row means
        // every comparison is "brighter than the left neighbor" -> every bit set to 1.
        val pixels = IntArray(72) { it }
        val hash = PerceptualHashCalculator.dHash(pixels)
        assertEquals(-1L, hash) // all 64 bits set
    }

    @Test
    fun `Hamming distance is symmetric`() {
        val a = PerceptualHashCalculator.dHash(IntArray(72) { it })
        val b = PerceptualHashCalculator.dHash(IntArray(72) { 71 - it })
        assertEquals(PerceptualHashCalculator.hammingDistance(a, b), PerceptualHashCalculator.hammingDistance(b, a))
    }

    @Test
    fun `maximally different hashes have Hamming distance 64`() {
        assertEquals(64, PerceptualHashCalculator.hammingDistance(0L, -1L))
    }
}
```

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoGroupingTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.similarity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGroupingTest {

    @Test
    fun `photos sharing a content hash are grouped, singletons are dropped`() {
        val hashes = listOf(
            PhotoHashInput(1L, "hashA", perceptualHash = 0L, dateTakenMs = null),
            PhotoHashInput(2L, "hashA", perceptualHash = 0L, dateTakenMs = null),
            PhotoHashInput(3L, "hashB", perceptualHash = 0L, dateTakenMs = null),
        )
        val groups = groupByExactHash(hashes)
        assertEquals(setOf(1L, 2L), groups.getValue("hashA").toSet())
        assertTrue(groups.containsKey("hashB").not())
    }

    @Test
    fun `near-identical hashes within the time window are grouped as BURST`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(1, groups.size)
        assertEquals(SimilarGroupKindResult.BURST, groups.single().first)
    }

    @Test
    fun `near-identical hashes outside the time window are grouped as NEAR_DUPLICATE, not BURST`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 100_000L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(1, groups.size)
        assertEquals(SimilarGroupKindResult.NEAR_DUPLICATE, groups.single().first)
    }

    @Test
    fun `dissimilar hashes are not grouped at all`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0L, dateTakenMs = 1_000L),
            PhotoHashInput(2L, "h2", perceptualHash = -1L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a photo with a null dateTakenMs can still be a near-duplicate but never a burst`() {
        val hashes = listOf(
            PhotoHashInput(1L, "h1", perceptualHash = 0b0000L, dateTakenMs = null),
            PhotoHashInput(2L, "h2", perceptualHash = 0b0001L, dateTakenMs = 1_500L),
        )
        val groups = groupNearDuplicatesAndBursts(hashes, hammingThreshold = 5, burstWindowMs = 2_000L)
        assertEquals(SimilarGroupKindResult.NEAR_DUPLICATE, groups.single().first)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.clustering.*" --tests "com.localphotoai.photomanager.domain.similarity.*"`
Expected: FAIL — none of `NearestCentroidClusterer`, `PerceptualHashCalculator`, `groupByExactHash`, `groupNearDuplicatesAndBursts`, `PhotoHashInput`, `SimilarGroupKindResult` exist yet.

- [ ] **Step 3: Create `NearestCentroidClusterer.kt`** (extracted from `FaceClusterer`'s algorithm)

```kotlin
package com.localphotoai.photomanager.domain.clustering

import com.localphotoai.photomanager.domain.face.l2Normalize
import com.localphotoai.photomanager.domain.person.addVector
import com.localphotoai.photomanager.domain.person.cosineSimilarity

/** An item awaiting clustering, with its (already L2-normalized) embedding vector. */
data class EmbeddingForClustering(val id: Long, val vector: FloatArray)

/** An existing cluster, identified by its running (unnormalized) centroid sum. */
data class ExistingCentroid(val groupId: Long, val centroidSum: FloatArray)

sealed class ClusterAssignment {
    abstract val id: Long
    abstract val confidence: Float

    data class ToExisting(override val id: Long, val groupId: Long, override val confidence: Float) : ClusterAssignment()
    data class ToNew(override val id: Long, val newClusterIndex: Int, override val confidence: Float) : ClusterAssignment()
}

data class NearestCentroidResult(val assignments: List<ClusterAssignment>, val newClusterCount: Int)

/**
 * Greedy nearest-centroid clustering, extracted from Phase 5's `FaceClusterer` so both face
 * clustering and Phase 7's image-similarity clustering share one tested implementation — the
 * algorithm itself has nothing face-specific about it (see `FaceClusterer`, which now delegates
 * here). Same greedy, single-pass, precision-over-recall behavior: an item joins the closest
 * current cluster (existing groups plus any new ones formed earlier in this run) above
 * [similarityThreshold], or seeds a new cluster otherwise.
 */
object NearestCentroidClusterer {

    fun cluster(
        items: List<EmbeddingForClustering>,
        existingClusters: List<ExistingCentroid>,
        similarityThreshold: Float,
    ): NearestCentroidResult {
        val working = existingClusters.map { WorkingCluster(groupId = it.groupId, sum = it.centroidSum.copyOf()) }
            .toMutableList()
        val assignments = mutableListOf<ClusterAssignment>()
        var nextNewClusterIndex = 0

        for (candidate in items) {
            var bestCluster: WorkingCluster? = null
            var bestSimilarity = Float.NEGATIVE_INFINITY
            for (cluster in working) {
                val similarity = cosineSimilarity(l2Normalize(cluster.sum), candidate.vector)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestCluster = cluster
                }
            }

            if (bestCluster != null && bestSimilarity >= similarityThreshold) {
                bestCluster.sum = addVector(bestCluster.sum, candidate.vector)
                assignments += if (bestCluster.groupId != null) {
                    ClusterAssignment.ToExisting(candidate.id, bestCluster.groupId, bestSimilarity)
                } else {
                    ClusterAssignment.ToNew(candidate.id, bestCluster.newClusterIndex!!, bestSimilarity)
                }
            } else {
                val index = nextNewClusterIndex++
                working += WorkingCluster(groupId = null, newClusterIndex = index, sum = candidate.vector.copyOf())
                assignments += ClusterAssignment.ToNew(candidate.id, index, confidence = 1f)
            }
        }

        return NearestCentroidResult(assignments, nextNewClusterIndex)
    }

    private class WorkingCluster(
        val groupId: Long?,
        val newClusterIndex: Int? = null,
        var sum: FloatArray,
    )
}
```

- [ ] **Step 4: Refactor `FaceClusterer` to delegate to it, preserving its exact public API**

Replace the body of `FaceClusterer.cluster()` in `FaceClusterer.kt` (keep `ALGORITHM_VERSION`, `DEFAULT_SIMILARITY_THRESHOLD`, and every existing public type — `FaceEmbeddingForClustering`, `ExistingClusterCentroid`, `ClusterOutcome`, `ClusteringResult` — unchanged):

```kotlin
    fun cluster(
        faces: List<FaceEmbeddingForClustering>,
        existingClusters: List<ExistingClusterCentroid>,
        similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    ): ClusteringResult {
        val result = com.localphotoai.photomanager.domain.clustering.NearestCentroidClusterer.cluster(
            items = faces.map { com.localphotoai.photomanager.domain.clustering.EmbeddingForClustering(it.faceId, it.vector) },
            existingClusters = existingClusters.map {
                com.localphotoai.photomanager.domain.clustering.ExistingCentroid(it.personId, it.centroidSum)
            },
            similarityThreshold = similarityThreshold,
        )
        val outcomes = result.assignments.map { assignment ->
            when (assignment) {
                is com.localphotoai.photomanager.domain.clustering.ClusterAssignment.ToExisting ->
                    ClusterOutcome.AssignedToExisting(assignment.id, assignment.groupId, assignment.confidence)
                is com.localphotoai.photomanager.domain.clustering.ClusterAssignment.ToNew ->
                    ClusterOutcome.AssignedToNewCluster(assignment.id, assignment.newClusterIndex, assignment.confidence)
            }
        }
        return ClusteringResult(outcomes, result.newClusterCount)
    }
```

Remove the now-unused `private class WorkingCluster` from `FaceClusterer.kt` (its logic moved into `NearestCentroidClusterer`).

- [ ] **Step 5: Run the existing `FaceClustererTest` suite unmodified — proves the extraction didn't change behavior**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.person.FaceClustererTest"`
Expected: PASS, all 8 pre-existing tests unchanged.

- [ ] **Step 6: Create `PerceptualHashCalculator.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

/**
 * dHash (difference hash): given a 9x8 grayscale pixel array (row-major, 72 values), compares
 * each pixel to its right neighbor to produce a 64-bit hash. Similarity between two photos is
 * the Hamming distance between their hashes — 0 means identical, 64 means maximally different.
 * A public-domain algorithm, no license or model needed.
 */
object PerceptualHashCalculator {

    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8

    fun dHash(grayscalePixels: IntArray): Long {
        require(grayscalePixels.size == HASH_WIDTH * HASH_HEIGHT) {
            "Expected ${HASH_WIDTH * HASH_HEIGHT} pixels, got ${grayscalePixels.size}"
        }
        var hash = 0L
        var bitIndex = 0
        for (row in 0 until HASH_HEIGHT) {
            for (col in 0 until HASH_WIDTH - 1) {
                val left = grayscalePixels[row * HASH_WIDTH + col]
                val right = grayscalePixels[row * HASH_WIDTH + col + 1]
                if (left < right) hash = hash or (1L shl bitIndex)
                bitIndex++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
```

- [ ] **Step 7: Create `PhotoGrouping.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

/** One photo's stored hashes, as needed for grouping — independent of the Room entity shape. */
data class PhotoHashInput(
    val photoId: Long,
    val contentHash: String,
    val perceptualHash: Long,
    val dateTakenMs: Long?,
)

enum class SimilarGroupKindResult { NEAR_DUPLICATE, BURST }

/** Groups photos sharing an identical [PhotoHashInput.contentHash]. Singleton "groups" are dropped. */
fun groupByExactHash(hashes: List<PhotoHashInput>): Map<String, List<Long>> =
    hashes.groupBy { it.contentHash }
        .filterValues { it.size >= 2 }
        .mapValues { (_, group) -> group.map { it.photoId } }

/**
 * Groups photos whose perceptual hashes are within [hammingThreshold] of each other into
 * near-duplicate or burst groups (union-find over the pairwise-similar graph, since "A is near B"
 * and "B is near C" should join A/B/C into one group even if A and C aren't directly close). A
 * group is BURST if every member's [PhotoHashInput.dateTakenMs] is within [burstWindowMs] of at
 * least one other member's; otherwise NEAR_DUPLICATE. A null `dateTakenMs` never counts toward a
 * burst window (unknown timing can't prove temporal proximity), so such a group is NEAR_DUPLICATE
 * at most. [hammingThreshold]/[burstWindowMs] are named, documented, untuned heuristics — same
 * honest treatment as [com.localphotoai.photomanager.domain.person.FaceClusterer]'s threshold.
 */
fun groupNearDuplicatesAndBursts(
    hashes: List<PhotoHashInput>,
    hammingThreshold: Int,
    burstWindowMs: Long,
): List<Pair<SimilarGroupKindResult, List<Long>>> {
    val parent = hashes.associate { it.photoId to it.photoId }.toMutableMap()

    fun find(id: Long): Long {
        var root = id
        while (parent.getValue(root) != root) root = parent.getValue(root)
        return root
    }

    fun union(a: Long, b: Long) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) parent[rootA] = rootB
    }

    for (i in hashes.indices) {
        for (j in i + 1 until hashes.size) {
            val distance = PerceptualHashCalculator.hammingDistance(hashes[i].perceptualHash, hashes[j].perceptualHash)
            if (distance <= hammingThreshold) union(hashes[i].photoId, hashes[j].photoId)
        }
    }

    val byId = hashes.associateBy { it.photoId }
    val groups = hashes.map { it.photoId }.groupBy { find(it) }.values.filter { it.size >= 2 }

    return groups.map { photoIds ->
        val members = photoIds.map { byId.getValue(it) }
        val isBurst = members.all { m ->
            m.dateTakenMs != null && members.any { other ->
                other.photoId != m.photoId && other.dateTakenMs != null &&
                    kotlin.math.abs(other.dateTakenMs - m.dateTakenMs) <= burstWindowMs
            }
        }
        (if (isBurst) SimilarGroupKindResult.BURST else SimilarGroupKindResult.NEAR_DUPLICATE) to photoIds
    }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.clustering.*" --tests "com.localphotoai.photomanager.domain.similarity.*" --tests "com.localphotoai.photomanager.domain.person.FaceClustererTest"`
Expected: PASS — 5 `NearestCentroidClustererTest`, 4 `PerceptualHashCalculatorTest`, 5 `PhotoGroupingTest`, 8 `FaceClustererTest` (unchanged).

- [ ] **Step 9: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/clustering domain/src/main/kotlin/com/localphotoai/photomanager/domain/person/FaceClusterer.kt domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity domain/src/test/kotlin/com/localphotoai/photomanager/domain/clustering domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity
git commit -m "feat(domain): extract NearestCentroidClusterer, add perceptual hashing and grouping"
```

---

### Task 3: Domain — repository interface, generator interfaces, use cases

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoGroupModels.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoGroupRepository.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/PhotoHasher.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/ImageSimilarityEmbeddingGenerator.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/HashScheduler.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/SimilarityScheduler.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/HashPhotosUseCase.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/DetectDuplicatesUseCase.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/GroupNearDuplicatesAndBurstsUseCase.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/GenerateImageSimilarityEmbeddingsUseCase.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity/GroupVisuallySimilarPhotosUseCase.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/HashPhotosUseCaseTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/DetectDuplicatesUseCaseTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/GenerateImageSimilarityEmbeddingsUseCaseTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/GroupVisuallySimilarPhotosUseCaseTest.kt`

**Interfaces:**
- Consumes: `NearestCentroidClusterer`, `PerceptualHashCalculator`, `groupByExactHash`, `groupNearDuplicatesAndBursts` (Task 2); `AppResult`/`AppError` (`:core:common`); `IndexingProgress`/`IndexingState` (`domain.photo`, existing).
- Produces: `PhotoGroupRepository` interface, `PhotoHasher` interface, `ImageSimilarityEmbeddingGenerator` interface, `HashScheduler`/`SimilarityScheduler` interfaces, five use cases — consumed by Task 5 (repository impl), Task 4 (generator impl), Task 6 (workers/schedulers).

- [ ] **Step 1: Create `PhotoGroupModels.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.domain.clustering.ClusterAssignment
import com.localphotoai.photomanager.domain.clustering.NearestCentroidResult
import com.localphotoai.photomanager.domain.face.l2Normalize
import com.localphotoai.photomanager.domain.clustering.EmbeddingForClustering as ClusteringItem

data class PhotoForHashing(val photoId: Long, val uri: String)

data class PhotoForSimilarityEmbedding(
    val photoId: Long,
    val uri: String,
    val widthPx: Int,
    val heightPx: Int,
    val orientationDegrees: Int,
)

data class PhotoEmbeddingForSimilarity(val photoId: Long, val vector: FloatArray)

data class ExistingSimilarCentroid(val groupId: Long, val centroidSum: FloatArray)

data class DuplicateGroupSummary(val groupId: Long, val photoIds: List<Long>, val totalSizeBytes: Long)

data class SimilarGroupSummary(val groupId: Long, val avgSimilarity: Float, val photoIds: List<Long>)

enum class SimilarGroupKind { NEAR_DUPLICATE, BURST, VISUALLY_SIMILAR }

/** Pure helper: converts embeddings + existing centroids into a [NearestCentroidResult] for visual similarity. */
fun clusterBySimilarity(
    embeddings: List<PhotoEmbeddingForSimilarity>,
    existingClusters: List<ExistingSimilarCentroid>,
    similarityThreshold: Float,
): NearestCentroidResult {
    val items = embeddings.map { ClusteringItem(it.photoId, l2Normalize(it.vector)) }
    val centroids = existingClusters.map { com.localphotoai.photomanager.domain.clustering.ExistingCentroid(it.groupId, it.centroidSum) }
    return com.localphotoai.photomanager.domain.clustering.NearestCentroidClusterer.cluster(
        items, centroids, similarityThreshold,
    )
}
```

- [ ] **Step 2: Create `PhotoGroupRepository.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import kotlinx.coroutines.flow.Flow

/**
 * Access to hashing, duplicate/near-duplicate/burst/similar grouping, and their pipeline state.
 * Implemented in `:data:database` (Room only). Mirrors the shape of Phase 3-5's repositories
 * (fetch-pending / save / mark-failed / observe-progress) for each of Phase 7's stages.
 */
interface PhotoGroupRepository {

    // Hashing stage
    suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing>
    suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long)
    suspend fun markHashFailed(photoId: Long, error: String)
    fun observeHashProgress(): Flow<IndexingProgress>
    suspend fun updateHashProgress(progress: IndexingProgress)

    // Exact-duplicate grouping (pure grouping over stored hashes)
    suspend fun fetchAllHashes(): List<PhotoHashInput>
    suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>)
    fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>>

    // Near-duplicate/burst grouping (pure grouping over stored hashes + dates)
    suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>)
    fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>>

    // Similarity-embedding stage
    suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding>
    suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray)
    suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String)
    fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress>
    suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress)

    // Visually-similar grouping (embedding-based nearest-centroid)
    suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity>
    suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid>
    suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    )
    fun observeGroupingProgress(): Flow<IndexingProgress>
    suspend fun updateGroupingProgress(progress: IndexingProgress)

    /** Called after a confirmed deletion so group membership doesn't reference a gone photo. */
    suspend fun removePhotoFromAllGroups(photoId: Long)
}

/** [com.localphotoai.photomanager.domain.clustering.ClusterAssignment] is Room/Android-free but
 *  this DTO keeps the repository interface from depending on the clustering package's sealed
 *  type directly, so callers pass plain data instead of re-importing clustering internals. */
data class ClusterAssignmentDto(val photoId: Long, val groupId: Long?, val newClusterIndex: Int?, val confidence: Float)
```

- [ ] **Step 3: Create `PhotoHasher.kt` and `ImageSimilarityEmbeddingGenerator.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

/** Computes a photo's content (SHA-256) and perceptual (dHash) hashes. Implemented in `:data:media`. */
interface PhotoHasher {
    suspend fun hash(photoUri: String): PhotoHashResult
}

data class PhotoHashResult(val contentHash: String, val perceptualHash: Long)
```

```kotlin
package com.localphotoai.photomanager.domain.similarity

/**
 * Generates a normalized whole-photo embedding for visual-similarity grouping. Implemented in
 * `:ml:embeddings` on top of a bundled (not downloaded) TFLite MobileNetV3-Small model — see
 * ARCHITECTURE.md's Phase 7 notes for why this model is bundled rather than downloaded.
 */
interface ImageSimilarityEmbeddingGenerator {
    val modelVersion: Int
    suspend fun generateEmbedding(photoUri: String, widthPx: Int, heightPx: Int, orientationDegrees: Int): FloatArray
}
```

- [ ] **Step 4: Create `HashScheduler.kt` and `SimilarityScheduler.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

/** Schedules background hashing work. Implemented in `:data:media` on top of WorkManager. */
interface HashScheduler {
    fun scheduleImmediateHashing()
    fun scheduleIncrementalHashing()
}
```

```kotlin
package com.localphotoai.photomanager.domain.similarity

/** Schedules background hash-grouping (duplicate/near-dup/burst) work. */
interface HashGroupingScheduler {
    fun scheduleImmediateGrouping()
    fun scheduleIncrementalGrouping()
}

/** Schedules background similarity-embedding generation. */
interface SimilarityEmbeddingScheduler {
    fun scheduleImmediateEmbedding()
    fun scheduleIncrementalEmbedding()
}

/** Schedules background visually-similar grouping (chained off similarity embedding). */
interface VisuallySimilarGroupingScheduler {
    fun scheduleImmediateGrouping()
    fun scheduleIncrementalGrouping()
}
```

- [ ] **Step 5: Create `HashPhotosUseCase.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState

private const val TAG = "HashPhotosUseCase"
private const val CHUNK_SIZE = 20

/**
 * Runs one hashing pass over every photo missing a content hash. Mirrors
 * [com.localphotoai.photomanager.domain.face.DetectFacesUseCase]'s per-item try/catch shape: a
 * corrupted/unreadable photo is flagged and skipped, never aborting the batch.
 */
class HashPhotosUseCase(
    private val repository: PhotoGroupRepository,
    private val hasher: PhotoHasher,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<IndexingProgress> = try {
        val startedAt = System.currentTimeMillis()
        val pending = repository.fetchPhotosNeedingHash()

        if (pending.isEmpty()) {
            val progress = IndexingProgress(IndexingState.COMPLETE, 0, 0, startedAt, null)
            repository.updateHashProgress(progress)
            AppResult.Success(progress)
        } else {
            repository.updateHashProgress(IndexingProgress(IndexingState.RUNNING, 0, pending.size, startedAt, null))
            var processed = 0
            for (chunk in pending.chunked(CHUNK_SIZE)) {
                for (photo in chunk) {
                    try {
                        val result = hasher.hash(photo.uri)
                        repository.saveHash(photo.photoId, result.contentHash, result.perceptualHash)
                    } catch (t: Throwable) {
                        val message = t.message ?: t::class.simpleName ?: "Unknown hashing error"
                        logger.warn(TAG, "Hashing failed for photo ${photo.photoId}", t)
                        repository.markHashFailed(photo.photoId, message)
                    }
                    processed++
                }
                repository.updateHashProgress(IndexingProgress(IndexingState.RUNNING, processed, pending.size, startedAt, null))
            }
            val finalProgress = IndexingProgress(IndexingState.COMPLETE, processed, pending.size, startedAt, null)
            repository.updateHashProgress(finalProgress)
            logger.info(TAG, "Hashing complete: $processed photo(s) processed")
            AppResult.Success(finalProgress)
        }
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown hashing error"
        logger.error(TAG, "Hashing run failed", t)
        repository.updateHashProgress(IndexingProgress(IndexingState.ERROR, 0, 0, System.currentTimeMillis(), message))
        AppResult.Failure(AppError.Io(message = "Hashing failed: $message", cause = t))
    }
}
```

- [ ] **Step 6: Create `DetectDuplicatesUseCase.kt` and `GroupNearDuplicatesAndBurstsUseCase.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger

private const val TAG = "DetectDuplicatesUseCase"

/** Re-groups every photo's stored content hash into exact-duplicate groups, from scratch each run. */
class DetectDuplicatesUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val hashes = repository.fetchAllHashes()
        val groups = groupByExactHash(hashes)
        repository.replaceDuplicateGroups(groups)
        logger.info(TAG, "Duplicate grouping complete: ${groups.size} group(s)")
        AppResult.Success(groups.size)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Duplicate grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Duplicate grouping failed: $message", cause = t))
    }
}
```

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger

private const val TAG = "GroupNearDuplicatesAndBurstsUseCase"

/** Named, documented, untuned heuristics — see [groupNearDuplicatesAndBursts]'s doc comment. */
const val NEAR_DUPLICATE_HAMMING_THRESHOLD = 8
const val BURST_TIME_WINDOW_MS = 2_000L

/** Re-groups every photo's stored perceptual hash + date into near-duplicate/burst groups, from scratch each run. */
class GroupNearDuplicatesAndBurstsUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val hashes = repository.fetchAllHashes()
        val groups = groupNearDuplicatesAndBursts(hashes, NEAR_DUPLICATE_HAMMING_THRESHOLD, BURST_TIME_WINDOW_MS)
        repository.replaceNearDuplicateAndBurstGroups(groups)
        logger.info(TAG, "Near-duplicate/burst grouping complete: ${groups.size} group(s)")
        AppResult.Success(groups.size)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Near-duplicate/burst grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Near-duplicate/burst grouping failed: $message", cause = t))
    }
}
```

- [ ] **Step 7: Create `GenerateImageSimilarityEmbeddingsUseCase.kt`** (mirrors `GenerateFaceEmbeddingsUseCase` exactly, minus the "model not downloaded" branch since the model is bundled)

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.face.l2Normalize
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState

private const val TAG = "GenerateImageSimilarityEmbeddingsUseCase"
private const val CHUNK_SIZE = 20

class GenerateImageSimilarityEmbeddingsUseCase(
    private val repository: PhotoGroupRepository,
    private val generator: ImageSimilarityEmbeddingGenerator,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<IndexingProgress> = try {
        val startedAt = System.currentTimeMillis()
        val pending = repository.fetchPhotosNeedingSimilarityEmbedding(generator.modelVersion)

        if (pending.isEmpty()) {
            val progress = IndexingProgress(IndexingState.COMPLETE, 0, 0, startedAt, null)
            repository.updateSimilarityEmbeddingProgress(progress)
            AppResult.Success(progress)
        } else {
            repository.updateSimilarityEmbeddingProgress(IndexingProgress(IndexingState.RUNNING, 0, pending.size, startedAt, null))
            var processed = 0
            for (chunk in pending.chunked(CHUNK_SIZE)) {
                for (photo in chunk) {
                    try {
                        val raw = generator.generateEmbedding(photo.uri, photo.widthPx, photo.heightPx, photo.orientationDegrees)
                        repository.saveSimilarityEmbedding(photo.photoId, generator.modelVersion, l2Normalize(raw))
                    } catch (t: Throwable) {
                        val message = t.message ?: t::class.simpleName ?: "Unknown error"
                        logger.warn(TAG, "Similarity embedding failed for photo ${photo.photoId}", t)
                        repository.markSimilarityEmbeddingFailed(photo.photoId, generator.modelVersion, message)
                    }
                    processed++
                }
                repository.updateSimilarityEmbeddingProgress(
                    IndexingProgress(IndexingState.RUNNING, processed, pending.size, startedAt, null),
                )
            }
            val finalProgress = IndexingProgress(IndexingState.COMPLETE, processed, pending.size, startedAt, null)
            repository.updateSimilarityEmbeddingProgress(finalProgress)
            logger.info(TAG, "Similarity embedding generation complete: $processed photo(s) processed")
            AppResult.Success(finalProgress)
        }
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown embedding error"
        logger.error(TAG, "Similarity embedding generation run failed", t)
        repository.updateSimilarityEmbeddingProgress(
            IndexingProgress(IndexingState.ERROR, 0, 0, System.currentTimeMillis(), message),
        )
        AppResult.Failure(AppError.Io(message = "Similarity embedding generation failed: $message", cause = t))
    }
}
```

- [ ] **Step 8: Create `GroupVisuallySimilarPhotosUseCase.kt`**

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.clustering.ClusterAssignment

private const val TAG = "GroupVisuallySimilarPhotosUseCase"

/** Untuned heuristic, separate constant from face clustering's — different embedding space, no
 *  reason to assume the same numeric threshold transfers. See ARCHITECTURE.md's Phase 7 notes. */
const val VISUALLY_SIMILAR_THRESHOLD = 0.75f

class GroupVisuallySimilarPhotosUseCase(
    private val repository: PhotoGroupRepository,
    private val logger: Logger,
) {
    suspend operator fun invoke(): AppResult<Int> = try {
        val embeddings = repository.fetchAllSimilarityEmbeddings()
        val existing = repository.fetchExistingSimilarClusters()
        val result = clusterBySimilarity(embeddings, existing, VISUALLY_SIMILAR_THRESHOLD)
        val dtos = result.assignments.map { assignment ->
            when (assignment) {
                is ClusterAssignment.ToExisting -> ClusterAssignmentDto(assignment.id, assignment.groupId, null, assignment.confidence)
                is ClusterAssignment.ToNew -> ClusterAssignmentDto(assignment.id, null, assignment.newClusterIndex, assignment.confidence)
            }
        }
        repository.applyVisuallySimilarGroupingResult(embeddings, dtos, result.newClusterCount)
        logger.info(TAG, "Visually-similar grouping complete: ${result.newClusterCount} new group(s)")
        AppResult.Success(result.newClusterCount)
    } catch (t: Throwable) {
        val message = t.message ?: t::class.simpleName ?: "Unknown grouping error"
        logger.error(TAG, "Visually-similar grouping failed", t)
        AppResult.Failure(AppError.Io(message = "Visually-similar grouping failed: $message", cause = t))
    }
}
```

- [ ] **Step 9: Write the use-case tests** (fakes mirroring `GenerateFaceEmbeddingsUseCaseTest`'s shape)

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/HashPhotosUseCaseTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRepository(private val pending: List<PhotoForHashing>) : NoOpPhotoGroupRepository() {
    val saved = LinkedHashMap<Long, PhotoHashResult>()
    val failed = LinkedHashMap<Long, String>()
    val progressUpdates = mutableListOf<IndexingProgress>()
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = pending
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {
        saved[photoId] = PhotoHashResult(contentHash, perceptualHash)
    }
    override suspend fun markHashFailed(photoId: Long, error: String) { failed[photoId] = error }
    override suspend fun updateHashProgress(progress: IndexingProgress) { progressUpdates += progress }
}

private class FakeHasher(private val failingUris: Set<String> = emptySet()) : PhotoHasher {
    override suspend fun hash(photoUri: String): PhotoHashResult {
        if (photoUri in failingUris) error("simulated decode failure")
        return PhotoHashResult(contentHash = "hash-$photoUri", perceptualHash = 0L)
    }
}

class HashPhotosUseCaseTest {

    @Test
    fun `no pending photos completes immediately`() = runBlocking {
        val repository = FakeRepository(emptyList())
        val result = HashPhotosUseCase(repository, FakeHasher(), NoOpLogger())()
        assertEquals(IndexingState.COMPLETE, (result as AppResult.Success).value.state)
        assertEquals(0, result.value.itemsTotal)
    }

    @Test
    fun `a failed hash is flagged but does not abort the batch`() = runBlocking {
        val bad = PhotoForHashing(1L, "content://bad")
        val good = PhotoForHashing(2L, "content://good")
        val repository = FakeRepository(listOf(bad, good))
        val result = HashPhotosUseCase(repository, FakeHasher(failingUris = setOf("content://bad")), NoOpLogger())()
        assertTrue(result is AppResult.Success)
        assertTrue(repository.failed.containsKey(1L))
        assertTrue(repository.saved.containsKey(2L))
        assertEquals(2, (result as AppResult.Success).value.itemsProcessed)
    }

    @Test
    fun `progress reports RUNNING before COMPLETE`() = runBlocking {
        val photos = (1L..25L).map { PhotoForHashing(it, "content://$it") }
        val repository = FakeRepository(photos)
        HashPhotosUseCase(repository, FakeHasher(), NoOpLogger())()
        assertTrue(repository.progressUpdates.any { it.state == IndexingState.RUNNING })
        assertEquals(IndexingState.COMPLETE, repository.progressUpdates.last().state)
    }
}

/** A `PhotoGroupRepository` fake base that errors on any unimplemented method — each test
 *  overrides only what it exercises, keeping fakes short and explicit about what they use. */
private abstract class NoOpPhotoGroupRepository : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = error("not stubbed")
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) = error("not stubbed")
    override suspend fun markHashFailed(photoId: Long, error: String) = error("not stubbed")
    override fun observeHashProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateHashProgress(progress: IndexingProgress) = error("not stubbed")
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = error("not stubbed")
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) = error("not stubbed")
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = error("not stubbed")
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) = error("not stubbed")
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = error("not stubbed")
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> = error("not stubbed")
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) = error("not stubbed")
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) = error("not stubbed")
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) = error("not stubbed")
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = error("not stubbed")
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = error("not stubbed")
    override suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    ) = error("not stubbed")
    override fun observeGroupingProgress(): Flow<IndexingProgress> = MutableStateFlow(IndexingProgress.IDLE)
    override suspend fun updateGroupingProgress(progress: IndexingProgress) = error("not stubbed")
    override suspend fun removePhotoFromAllGroups(photoId: Long) = error("not stubbed")
}
```

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/DetectDuplicatesUseCaseTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.similarity

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.testutil.NoOpLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectDuplicatesUseCaseTest {

    @Test
    fun `groups photos sharing a content hash and persists via the repository`() = runBlocking {
        val hashes = listOf(
            PhotoHashInput(1L, "same", 0L, null),
            PhotoHashInput(2L, "same", 0L, null),
            PhotoHashInput(3L, "unique", 0L, null),
        )
        var saved: Map<String, List<Long>>? = null
        val repository = object : NoOpPhotoGroupRepositoryForTests() {
            override suspend fun fetchAllHashes(): List<PhotoHashInput> = hashes
            override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
                saved = photoIdGroupsByHash
            }
        }

        val result = DetectDuplicatesUseCase(repository, NoOpLogger())()

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).value)
        assertEquals(setOf(1L, 2L), saved?.getValue("same")?.toSet())
    }
}
```

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity/GenerateImageSimilarityEmbeddingsUseCaseTest.kt` and `GroupVisuallySimilarPhotosUseCaseTest.kt` following the exact same fake-repository-subclass pattern as `HashPhotosUseCaseTest`/`DetectDuplicatesUseCaseTest` above — each test file defines its own minimal `NoOpPhotoGroupRepositoryForTests` overriding only the methods its use case calls (copy the `NoOpPhotoGroupRepository` shape from `HashPhotosUseCaseTest.kt`, renamed per file to avoid a shared test-only production dependency). Cover: no-pending completes immediately; a failing photo is flagged without aborting the batch; a successful embedding is L2-normalized before saving (assert vector magnitude ≈ 1); grouping delegates to `NearestCentroidClusterer` correctly (two similar embeddings end up in the same new-cluster index).

- [ ] **Step 10: Run all new domain tests**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.similarity.*"`
Expected: PASS, all new tests green, no regressions in the 57 pre-existing tests (`./gradlew :domain:test` full run).

- [ ] **Step 11: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/similarity domain/src/test/kotlin/com/localphotoai/photomanager/domain/similarity
git commit -m "feat(domain): add PhotoGroupRepository, hasher/embedding interfaces, Phase 7 use cases"
```

---

### Task 4: MobileNetV3 model asset and `:ml:embeddings` generator

**Files:**
- Create: `ml/embeddings/src/main/assets/mobilenet_v3_small_feature_vector.tflite` (binary, generated — see Step 1)
- Create: `ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/MobileNetV3ModelSpec.kt`
- Create: `ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/MobileNetV3EmbeddingGenerator.kt`
- Modify: `ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/EmbeddingsModule.kt`

**Interfaces:**
- Produces: `MobileNetV3EmbeddingGenerator` bound to `ImageSimilarityEmbeddingGenerator` (Task 3) via Hilt.

No automated test — TFLite wrapper, verified manually on-device (Task 8), matching Phase 4's precedent for `FaceNetEmbeddingGenerator`.

- [ ] **Step 1: Convert MobileNetV3-Small to a bundled TFLite asset**

This is a one-time, real conversion — run it, don't skip it:

```bash
pip install tensorflow
python3 - <<'PY'
import tensorflow as tf

model = tf.keras.applications.MobileNetV3Small(
    input_shape=(224, 224, 3),
    include_top=False,
    weights="imagenet",
    pooling="avg",
    include_preprocessing=True,
)
print("Output shape:", model.output_shape)  # record this — it's OUTPUT_SIZE below

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

with open("mobilenet_v3_small_feature_vector.tflite", "wb") as f:
    f.write(tflite_model)
print("Wrote", len(tflite_model), "bytes")
PY
```

Record the printed output shape's last dimension (e.g. `(None, 576)` → `OUTPUT_SIZE = 576`) and the file's byte size — both are needed in Step 2, and must be the *actual* printed values, not assumed. `include_preprocessing=True` means the model itself applies MobileNetV3's expected input scaling, so the Kotlin side only needs to feed raw `[0,255]` RGB pixel values (see Step 3) — verify this assumption too: if `include_preprocessing` isn't available in the installed TF version, set it `False` and apply `(pixel / 127.5) - 1.0` normalization manually in `bitmapToInputBuffer` (Step 3) instead.

Move the generated file to `ml/embeddings/src/main/assets/mobilenet_v3_small_feature_vector.tflite` (create the `assets` directory if it doesn't exist).

- [ ] **Step 2: Create `MobileNetV3ModelSpec.kt`**

```kotlin
package com.localphotoai.photomanager.ml.embeddings

/**
 * The bundled (not downloaded — see ARCHITECTURE.md's Phase 7 notes) image-similarity model's
 * identity. Converted from `tf.keras.applications.MobileNetV3Small` (Apache 2.0, official
 * TensorFlow/Keras team), `include_top=False, pooling="avg"` — see Task 4 Step 1 of the Phase 7
 * plan for the exact conversion command. OUTPUT_SIZE must match the shape printed during that
 * conversion, not be assumed.
 */
object MobileNetV3ModelSpec {
    const val MODEL_VERSION = 1
    const val ASSET_FILENAME = "mobilenet_v3_small_feature_vector.tflite"
    const val INPUT_SIZE = 224
    const val OUTPUT_SIZE = 576 // verify against Step 1's printed output shape; update if different
}
```

- [ ] **Step 3: Create `MobileNetV3EmbeddingGenerator.kt`** (same delegate-tier/decode/recycle discipline as `FaceNetEmbeddingGenerator`, simpler since there's no face crop — whole photo, decoded and resized directly)

```kotlin
package com.localphotoai.photomanager.ml.embeddings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.similarity.ImageSimilarityEmbeddingGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

private const val TAG = "MobileNetV3EmbeddingGenerator"
private const val MAX_SOURCE_DIMENSION_PX = 1024

@Singleton
class MobileNetV3EmbeddingGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : ImageSimilarityEmbeddingGenerator {

    override val modelVersion: Int = MobileNetV3ModelSpec.MODEL_VERSION

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentTier = DelegateTier.GPU

    private fun runInference(input: ByteBuffer, output: Array<FloatArray>) {
        try {
            interpreter().run(input, output)
        } catch (t: Throwable) {
            val nextTier = currentTier.next() ?: throw t
            logger.warn(TAG, "Delegate tier $currentTier failed at inference time, downgrading to $nextTier", t)
            closeInterpreter()
            currentTier = nextTier
            interpreter().run(input, output)
        }
    }

    override suspend fun generateEmbedding(
        photoUri: String,
        widthPx: Int,
        heightPx: Int,
        orientationDegrees: Int,
    ): FloatArray {
        val source = decodeSourceBitmap(photoUri, widthPx, heightPx) ?: error("Unable to decode bitmap for $photoUri")
        try {
            val resized = Bitmap.createScaledBitmap(source, MobileNetV3ModelSpec.INPUT_SIZE, MobileNetV3ModelSpec.INPUT_SIZE, true)
            try {
                val input = bitmapToInputBuffer(resized)
                val output = Array(1) { FloatArray(MobileNetV3ModelSpec.OUTPUT_SIZE) }
                runInference(input, output)
                return output[0]
            } finally {
                if (resized !== source) resized.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /** include_preprocessing=True in the conversion (Task 4 Step 1) means the model expects raw
     *  [0,255] pixel values — if that flag was False during conversion instead, replace the
     *  `.toFloat()` lines below with `((... ) - 127.5f) / 127.5f`. */
    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val size = MobileNetV3ModelSpec.INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
            buffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
            buffer.putFloat((pixel and 0xFF).toFloat())
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeSourceBitmap(photoUri: String, sourceWidthPx: Int, sourceHeightPx: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(sourceWidthPx, sourceHeightPx, MAX_SOURCE_DIMENSION_PX)
        }
        return try {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (t: Throwable) {
            logger.warn(TAG, "Failed to decode bitmap for $photoUri", t)
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = max(width, height)
        while (longestSide / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        return sampleSize
    }

    private fun interpreter(): Interpreter {
        interpreter?.let { return it }
        while (true) {
            try {
                val created = buildInterpreter(currentTier)
                interpreter = created
                logger.info(TAG, "Using delegate tier $currentTier for similarity-embedding inference")
                return created
            } catch (t: Throwable) {
                val nextTier = currentTier.next() ?: throw t
                logger.warn(TAG, "Delegate tier $currentTier failed to initialize, trying $nextTier", t)
                currentTier = nextTier
            }
        }
    }

    private fun buildInterpreter(tier: DelegateTier): Interpreter {
        val modelBuffer = loadAssetModel()
        return when (tier) {
            DelegateTier.GPU -> {
                if (!isGpuDelegateSupported()) error("GPU delegate not supported on this device")
                val delegate = GpuDelegate()
                gpuDelegate = delegate
                Interpreter(modelBuffer, Interpreter.Options().addDelegate(delegate))
            }
            DelegateTier.NNAPI -> Interpreter(modelBuffer, Interpreter.Options().setUseNNAPI(true))
            DelegateTier.CPU -> Interpreter(modelBuffer, Interpreter.Options().setUseNNAPI(false))
        }
    }

    private fun isGpuDelegateSupported(): Boolean = try {
        CompatibilityList().isDelegateSupportedOnThisDevice
    } catch (t: Throwable) {
        false
    }

    private fun closeInterpreter() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    private fun loadAssetModel(): ByteBuffer {
        context.assets.openFd(MobileNetV3ModelSpec.ASSET_FILENAME).use { fd ->
            val buffer = ByteBuffer.allocateDirect(fd.length.toInt()).order(ByteOrder.nativeOrder())
            fd.createInputStream().use { input ->
                val bytes = input.readBytes()
                buffer.put(bytes)
            }
            buffer.rewind()
            return buffer
        }
    }

    private enum class DelegateTier {
        GPU, NNAPI, CPU;

        fun next(): DelegateTier? = when (this) {
            GPU -> NNAPI
            NNAPI -> CPU
            CPU -> null
        }
    }
}
```

- [ ] **Step 4: Bind it in `EmbeddingsModule.kt`**

Add the import and this method to the existing `EmbeddingsModule` class:

```kotlin
    @Binds
    @Singleton
    abstract fun bindImageSimilarityEmbeddingGenerator(
        impl: MobileNetV3EmbeddingGenerator,
    ): ImageSimilarityEmbeddingGenerator
```

- [ ] **Step 5: Build**

Run: `./gradlew :ml:embeddings:assembleDebug`
Expected: BUILD SUCCESSFUL. If Step 1's `include_preprocessing` assumption was wrong (TF version too old for that parameter), fix `bitmapToInputBuffer` per the comment in Step 3 before proceeding.

- [ ] **Step 6: Commit**

```bash
git add ml/embeddings/src/main/assets ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/MobileNetV3ModelSpec.kt ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/MobileNetV3EmbeddingGenerator.kt ml/embeddings/src/main/kotlin/com/localphotoai/photomanager/ml/embeddings/EmbeddingsModule.kt
git commit -m "feat(ml): bundle MobileNetV3-Small and add image-similarity embedding generator"
```

---

### Task 5: `:data:media` hashing + `:data:database` repository implementation

**Files:**
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/PhotoHasherImpl.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/PhotoGroupMappers.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/PhotoGroupRepositoryImpl.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/MediaModule.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/RepositoryModule.kt`
- Modify: `data/media/build.gradle.kts` (needs `:domain`'s new `similarity` package — already a dependency, no build-file change actually needed; verify in Step 4)

**Interfaces:**
- Consumes: `PhotoGroupRepository`, `PhotoHasher`, `DuplicateGroupDao`, `SimilarGroupDao`, `PhotoDao` (Tasks 1, 3).
- Produces: bound implementations, consumed by Task 3's use cases via Task 6's workers.

No automated test — Room/Android glue, verified manually (Task 8).

- [ ] **Step 1: Add hash query/update methods to `PhotoDao`**

In `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/PhotoDao.kt`, add:

```kotlin
    @Query("SELECT mediaStoreId, uri FROM photos WHERE contentHash IS NULL")
    suspend fun getPhotosNeedingHash(): List<HashPendingRow>

    @Query("UPDATE photos SET contentHash = :contentHash, perceptualHash = :perceptualHash, hashError = NULL WHERE mediaStoreId = :photoId")
    suspend fun updateHashes(photoId: Long, contentHash: String, perceptualHash: Long)

    @Query("UPDATE photos SET hashError = :error WHERE mediaStoreId = :photoId")
    suspend fun markHashFailed(photoId: Long, error: String)

    @Query("SELECT mediaStoreId, contentHash, perceptualHash, dateTakenMs FROM photos WHERE contentHash IS NOT NULL")
    suspend fun getAllHashes(): List<PhotoHashRow>

    @Query(
        "SELECT mediaStoreId, uri, width AS widthPx, height AS heightPx, orientationDegrees FROM photos " +
            "WHERE contentHash IS NOT NULL",
    )
    suspend fun getPhotosReadyForSimilarityEmbedding(): List<PhotoForEmbeddingRow>

    data class HashPendingRow(val mediaStoreId: Long, val uri: String)
    data class PhotoHashRow(val mediaStoreId: Long, val contentHash: String, val perceptualHash: Long, val dateTakenMs: Long?)
    data class PhotoForEmbeddingRow(val mediaStoreId: Long, val uri: String, val widthPx: Int, val heightPx: Int, val orientationDegrees: Int)
```

Note: `getPhotosReadyForSimilarityEmbedding` intentionally gates on `contentHash IS NOT NULL` (i.e. hashing has completed) rather than filtering by `embeddingVersion` here — the model-version filter happens in Step 3's `similarSimilarityEmbeddingDao` query below, which also needs a `photos.similarityEmbeddingVersion` column. Add that column now too: in `PhotoEntity.kt`, add `val similarityEmbeddingVersion: Int? = null` and `val similarityEmbeddingError: String? = null` after `hashError`, and add the corresponding two `ALTER TABLE photos ADD COLUMN` lines to Task 1's `MIGRATION_5_6` (go back and add `db.execSQL("ALTER TABLE photos ADD COLUMN similarityEmbeddingVersion INTEGER")` and `db.execSQL("ALTER TABLE photos ADD COLUMN similarityEmbeddingError TEXT")` there, then re-run Task 1 Step 8's build to confirm it still compiles with these two extra columns).

Replace `getPhotosReadyForSimilarityEmbedding`'s query with the version-aware form:

```kotlin
    @Query(
        "SELECT mediaStoreId, uri, width AS widthPx, height AS heightPx, orientationDegrees FROM photos " +
            "WHERE contentHash IS NOT NULL AND (similarityEmbeddingVersion IS NULL OR similarityEmbeddingVersion != :currentModelVersion)",
    )
    suspend fun getPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForEmbeddingRow>

    @Query("UPDATE photos SET similarityEmbeddingVersion = :modelVersion, similarityEmbeddingError = NULL WHERE mediaStoreId = :photoId")
    suspend fun markSimilarityEmbeddingComplete(photoId: Long, modelVersion: Int)

    @Query("UPDATE photos SET similarityEmbeddingVersion = :modelVersion, similarityEmbeddingError = :error WHERE mediaStoreId = :photoId")
    suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String)
```

(remove the earlier non-version-aware `getPhotosReadyForSimilarityEmbedding` — this replaces it).

- [ ] **Step 2: Create `PhotoHasherImpl.kt`** (SHA-256 over the raw stream + dHash over a decoded 9×8 grayscale thumbnail, one pass, one small bitmap)

```kotlin
package com.localphotoai.photomanager.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.localphotoai.photomanager.domain.similarity.PerceptualHashCalculator
import com.localphotoai.photomanager.domain.similarity.PhotoHashResult
import com.localphotoai.photomanager.domain.similarity.PhotoHasher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Computes both hashes per photo from two independent decode passes: a raw byte stream for
 * SHA-256 (must see every byte, can't downsample) and a tiny 9x8 decode for dHash (deliberately
 * downsampled at decode time via `inSampleSize`, never a full-resolution bitmap). Two decodes
 * cost more I/O than one, but SHA-256 needs the untouched bytes while dHash wants them tiny — no
 * single decode serves both.
 */
class PhotoHasherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoHasher {

    override suspend fun hash(photoUri: String): PhotoHashResult {
        val uri = Uri.parse(photoUri)
        val contentHash = computeContentHash(uri)
        val perceptualHash = computePerceptualHash(uri)
        return PhotoHashResult(contentHash, perceptualHash)
    }

    private fun computeContentHash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        } ?: error("Unable to open stream for $uri")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computePerceptualHash(uri: Uri): Long {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8 // any large downsample is fine — the final scaled-down read below is what matters
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Unable to decode bitmap for $uri")
        try {
            val small = android.graphics.Bitmap.createScaledBitmap(
                bitmap, PerceptualHashCalculator.HASH_WIDTH, PerceptualHashCalculator.HASH_HEIGHT, true,
            )
            try {
                val pixels = IntArray(PerceptualHashCalculator.HASH_WIDTH * PerceptualHashCalculator.HASH_HEIGHT)
                small.getPixels(
                    pixels, 0, PerceptualHashCalculator.HASH_WIDTH, 0, 0,
                    PerceptualHashCalculator.HASH_WIDTH, PerceptualHashCalculator.HASH_HEIGHT,
                )
                val grayscale = IntArray(pixels.size) { i ->
                    val p = pixels[i]
                    (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                }
                return PerceptualHashCalculator.dHash(grayscale)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
```

- [ ] **Step 3: Create `PhotoGroupMappers.kt`** (Room row → domain mapping, in `:data:database`)

```kotlin
package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoForHashing
import com.localphotoai.photomanager.domain.similarity.PhotoForSimilarityEmbedding
import com.localphotoai.photomanager.domain.similarity.PhotoHashInput
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary

internal fun PhotoDao.HashPendingRow.toDomain() = PhotoForHashing(mediaStoreId, uri)

internal fun PhotoDao.PhotoHashRow.toDomain() = PhotoHashInput(mediaStoreId, contentHash, perceptualHash, dateTakenMs)

internal fun PhotoDao.PhotoForEmbeddingRow.toDomain() =
    PhotoForSimilarityEmbedding(mediaStoreId, uri, widthPx, heightPx, orientationDegrees)

internal fun DuplicateGroupDao.DuplicateGroupRow.toDomain() =
    DuplicateGroupSummary(groupId, photoIdsCsv.split(",").map { it.toLong() }, totalSizeBytes)

internal fun SimilarGroupDao.SimilarGroupRow.toDomain() =
    SimilarGroupSummary(groupId, avgSimilarity, photoIdsCsv.split(",").map { it.toLong() })

internal fun com.localphotoai.photomanager.domain.similarity.SimilarGroupKind.toEntity(): SimilarGroupKind = when (this) {
    com.localphotoai.photomanager.domain.similarity.SimilarGroupKind.NEAR_DUPLICATE -> SimilarGroupKind.NEAR_DUPLICATE
    com.localphotoai.photomanager.domain.similarity.SimilarGroupKind.BURST -> SimilarGroupKind.BURST
    com.localphotoai.photomanager.domain.similarity.SimilarGroupKind.VISUALLY_SIMILAR -> SimilarGroupKind.VISUALLY_SIMILAR
}

internal fun com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult.toEntity(): SimilarGroupKind = when (this) {
    com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult.NEAR_DUPLICATE -> SimilarGroupKind.NEAR_DUPLICATE
    com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult.BURST -> SimilarGroupKind.BURST
}
```

- [ ] **Step 4: Create `PhotoGroupRepositoryImpl.kt`**

```kotlin
package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.DuplicateGroupDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.dao.SimilarGroupDao
import com.localphotoai.photomanager.data.database.entity.SimilarGroupKind as EntityKind
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.similarity.ClusterAssignmentDto
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.ExistingSimilarCentroid
import com.localphotoai.photomanager.domain.similarity.PhotoEmbeddingForSimilarity
import com.localphotoai.photomanager.domain.similarity.PhotoForHashing
import com.localphotoai.photomanager.domain.similarity.PhotoForSimilarityEmbedding
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.PhotoHashInput
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class PhotoGroupRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    private val duplicateGroupDao: DuplicateGroupDao,
    private val similarGroupDao: SimilarGroupDao,
) : PhotoGroupRepository {

    // In-memory progress state — mirrors the durable-status-table pattern used elsewhere in this
    // project (IndexingStatus, FaceDetectionStatus, ...) would be the fuller version; Phase 7
    // keeps it in-memory since no existing Phase 7 status table was scoped in Task 1 and the
    // grouping stages complete quickly enough that cross-process durability isn't load-bearing
    // the way multi-minute face detection was. Revisit with a durable table if profiling in
    // Task 8 shows this matters.
    private val hashProgress = MutableStateFlow(IndexingProgress.IDLE)
    private val similarityEmbeddingProgress = MutableStateFlow(IndexingProgress.IDLE)
    private val groupingProgress = MutableStateFlow(IndexingProgress.IDLE)

    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> =
        photoDao.getPhotosNeedingHash().map { it.toDomain() }

    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {
        photoDao.updateHashes(photoId, contentHash, perceptualHash)
    }

    override suspend fun markHashFailed(photoId: Long, error: String) {
        photoDao.markHashFailed(photoId, error)
    }

    override fun observeHashProgress(): Flow<IndexingProgress> = hashProgress

    override suspend fun updateHashProgress(progress: IndexingProgress) {
        hashProgress.value = progress
    }

    override suspend fun fetchAllHashes(): List<PhotoHashInput> = photoDao.getAllHashes().map { it.toDomain() }

    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {
        duplicateGroupDao.replaceAllGroups(photoIdGroupsByHash)
    }

    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> =
        duplicateGroupDao.observeGroups().map { rows -> rows.map { it.toDomain() } }

    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) {
        val byKind = groups.groupBy { it.first }
        for (kind in SimilarGroupKindResult.entries) {
            val groupsOfKind = (byKind[kind] ?: emptyList())
                .mapIndexed { index, (_, photoIds) -> index to photoIds.map { it to 1f } }
                .toMap()
            similarGroupDao.replaceGroupsOfKind(kind.toEntity(), groupsOfKind)
        }
    }

    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> =
        similarGroupDao.observeGroupsByKind(kind.toEntity()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> =
        photoDao.getPhotosNeedingSimilarityEmbedding(currentModelVersion).map { it.toDomain() }

    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {
        embeddingsForSimilarity[photoId] = vector
        photoDao.markSimilarityEmbeddingComplete(photoId, modelVersion)
    }

    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {
        photoDao.markSimilarityEmbeddingFailed(photoId, modelVersion, error)
    }

    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = similarityEmbeddingProgress

    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) {
        similarityEmbeddingProgress.value = progress
    }

    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> =
        embeddingsForSimilarity.map { (photoId, vector) -> PhotoEmbeddingForSimilarity(photoId, vector) }

    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> {
        // Existing VISUALLY_SIMILAR groups' centroids are recomputed from their current members'
        // stored vectors each run (rather than persisting a separate centroid column) — simpler,
        // and cheap at this phase's expected group sizes.
        val groups = similarGroupDao.observeGroupsByKind(EntityKind.VISUALLY_SIMILAR)
        val rows = groups.let { flow -> kotlinx.coroutines.flow.first(flow) }
        return rows.map { row ->
            val photoIds = row.photoIdsCsv.split(",").map { it.toLong() }
            var sum = FloatArray(0)
            for (id in photoIds) {
                val vector = embeddingsForSimilarity[id] ?: continue
                sum = if (sum.isEmpty()) vector.copyOf() else FloatArray(sum.size) { i -> sum[i] + vector[i] }
            }
            ExistingSimilarCentroid(row.groupId, sum)
        }.filter { it.centroidSum.isNotEmpty() }
    }

    override suspend fun applyVisuallySimilarGroupingResult(
        embeddings: List<PhotoEmbeddingForSimilarity>,
        assignments: List<ClusterAssignmentDto>,
        newClusterCount: Int,
    ) {
        val vectorByPhotoId = embeddings.associate { it.photoId to it.vector }
        val newClusterMembers = mutableMapOf<Int, MutableList<Pair<Long, Float>>>()
        for (assignment in assignments) {
            if (assignment.groupId != null) {
                similarGroupDao.upsertMember(
                    com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity(
                        assignment.photoId, assignment.groupId, assignment.confidence,
                    ),
                )
            } else if (assignment.newClusterIndex != null) {
                newClusterMembers.getOrPut(assignment.newClusterIndex) { mutableListOf() }
                    .add(assignment.photoId to assignment.confidence)
            }
        }
        for ((_, members) in newClusterMembers) {
            if (members.size < 2) continue
            val avg = members.map { it.second }.average().toFloat()
            val groupId = similarGroupDao.insertGroup(
                com.localphotoai.photomanager.data.database.entity.SimilarGroupEntity(
                    kind = EntityKind.VISUALLY_SIMILAR, avgSimilarity = avg,
                ),
            )
            for ((photoId, similarity) in members) {
                similarGroupDao.upsertMember(
                    com.localphotoai.photomanager.data.database.entity.SimilarGroupMemberEntity(photoId, groupId, similarity),
                )
            }
        }
    }

    override fun observeGroupingProgress(): Flow<IndexingProgress> = groupingProgress

    override suspend fun updateGroupingProgress(progress: IndexingProgress) {
        groupingProgress.value = progress
    }

    override suspend fun removePhotoFromAllGroups(photoId: Long) {
        duplicateGroupDao.removeMember(photoId)
        similarGroupDao.removeMember(photoId)
        embeddingsForSimilarity.remove(photoId)
    }

    companion object {
        // Similarity-embedding vectors are kept in memory, not a Room BLOB column, mirroring the
        // scale/complexity trade-off Phase 5 made for embeddings before persisting them properly —
        // revisit if Task 8's profiling shows re-embedding across process restarts is a real cost.
        // NOTE: this is a real, load-bearing limitation — see Task 8's known-limitations writeup.
        private val embeddingsForSimilarity = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()
    }
}
```

**This step has a real design gap, flagged inline in the code's comments — resolve it before proceeding:** storing similarity embeddings in an in-memory `ConcurrentHashMap` (not a Room table) means they're lost on process death, unlike every other embedding/vector in this app (`EmbeddingEntity` for faces persists to Room). This was a shortcut taken to keep Task 1's schema simpler, and it undermines the whole "avoid processing the same photo repeatedly" pipeline discipline every other stage in this project follows. **Before Step 5, add a `SimilarityEmbeddingEntity(photoId PK, modelVersion, vector BLOB)` table** (same shape as the existing `EmbeddingEntity` for faces) to Task 1's schema (go back and add it: a new entity file, register it on `AppDatabase`, add its `CREATE TABLE` to `MIGRATION_5_6`, add a DAO — either a new `SimilarityEmbeddingDao` or a few extra `@Query` methods on `SimilarGroupDao`), and rewrite `saveSimilarityEmbedding`/`fetchAllSimilarityEmbeddings`/`fetchExistingSimilarClusters` above to read/write that table instead of the static in-memory map, using `floatArrayToBytes`/`bytesToFloatArray` from `EmbeddingMappers.kt` exactly as `PersonRepositoryImpl` does for face centroids.

- [ ] **Step 5: Wire Hilt bindings**

In `MediaModule.kt`, add:

```kotlin
    @Binds
    @Singleton
    abstract fun bindPhotoHasher(impl: PhotoHasherImpl): PhotoHasher
```

(import `com.localphotoai.photomanager.domain.similarity.PhotoHasher` and `PhotoHasherImpl`).

In `RepositoryModule.kt`, add:

```kotlin
    @Binds
    @Singleton
    abstract fun bindPhotoGroupRepository(impl: PhotoGroupRepositoryImpl): PhotoGroupRepository

    companion object {
        // ... existing provideSearchPhotosUseCase stays here ...

        @Provides
        fun provideHashPhotosUseCase(repository: PhotoGroupRepository, hasher: PhotoHasher, logger: Logger): HashPhotosUseCase =
            HashPhotosUseCase(repository, hasher, logger)

        @Provides
        fun provideDetectDuplicatesUseCase(repository: PhotoGroupRepository, logger: Logger): DetectDuplicatesUseCase =
            DetectDuplicatesUseCase(repository, logger)

        @Provides
        fun provideGroupNearDuplicatesAndBurstsUseCase(repository: PhotoGroupRepository, logger: Logger): GroupNearDuplicatesAndBurstsUseCase =
            GroupNearDuplicatesAndBurstsUseCase(repository, logger)

        @Provides
        fun provideGenerateImageSimilarityEmbeddingsUseCase(
            repository: PhotoGroupRepository,
            generator: ImageSimilarityEmbeddingGenerator,
            logger: Logger,
        ): GenerateImageSimilarityEmbeddingsUseCase = GenerateImageSimilarityEmbeddingsUseCase(repository, generator, logger)

        @Provides
        fun provideGroupVisuallySimilarPhotosUseCase(repository: PhotoGroupRepository, logger: Logger): GroupVisuallySimilarPhotosUseCase =
            GroupVisuallySimilarPhotosUseCase(repository, logger)
    }
```

(add the corresponding imports for `PhotoHasher`, `Logger`, `HashPhotosUseCase`, `DetectDuplicatesUseCase`, `GroupNearDuplicatesAndBurstsUseCase`, `GenerateImageSimilarityEmbeddingsUseCase`, `GroupVisuallySimilarPhotosUseCase`, `ImageSimilarityEmbeddingGenerator`, and `Provides`/`Logger` if not already present — `RepositoryModule` currently has no `companion object`; adding one alongside the existing `@Binds` methods matches `MediaModule`'s established structure).

Note: `PhotoHasherImpl` and `MobileNetV3EmbeddingGenerator`/`ImageSimilarityEmbeddingGenerator` live in different modules (`:data:media` and `:ml:embeddings`) from `PhotoGroupRepository`/the use cases (`:domain`, bound in `:data:database`'s `RepositoryModule`) — but the use-case `@Provides` methods above need `PhotoHasher` and `ImageSimilarityEmbeddingGenerator` as constructor args, which are bound in *different* Hilt modules (`MediaModule` in `:data:media`, `EmbeddingsModule` in `:ml:embeddings`). This is fine — Hilt's `SingletonComponent` graph is assembled from every installed module across all modules the `:app` module depends on, regardless of which Gradle module declares which `@Binds`/`@Provides`, exactly like `MediaModule`'s existing `provideDetectFacesUseCase` already depends on `FaceDetector` (bound in `:ml:vision`) and `FaceRepository` (bound in `:data:database`) today. Confirm `:data:database`'s `build.gradle.kts` has line-of-sight to these types only through the `:domain` interfaces (it does — it already depends on `:domain`), not a direct Gradle dependency on `:data:media`/`:ml:embeddings` (it doesn't need one, since it only references the domain interfaces, not the concrete impls).

- [ ] **Step 6: Build**

Run: `./gradlew :data:database:assembleDebug :data:media:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/PhotoHasherImpl.kt data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/MediaModule.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/PhotoGroupMappers.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/PhotoGroupRepositoryImpl.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/RepositoryModule.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/PhotoDao.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt
git commit -m "feat(data): implement PhotoGroupRepository and photo hashing, persist similarity embeddings"
```

---

### Task 6: WorkManager pipeline — workers and schedulers

**Files:**
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/HashWorker.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/HashSchedulerImpl.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/HashGroupingWorker.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/HashGroupingSchedulerImpl.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/SimilarityEmbeddingWorker.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/SimilarityEmbeddingSchedulerImpl.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/VisuallySimilarGroupingWorker.kt`
- Create: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/VisuallySimilarGroupingSchedulerImpl.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/IndexWorker.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/MediaModule.kt`
- Modify: `feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos/PhotosViewModel.kt`

**Interfaces:**
- Consumes: `HashPhotosUseCase`, `DetectDuplicatesUseCase`, `GroupNearDuplicatesAndBurstsUseCase`, `GenerateImageSimilarityEmbeddingsUseCase`, `GroupVisuallySimilarPhotosUseCase` (Task 3, provided via Task 5's `RepositoryModule`), `HashScheduler`/`HashGroupingScheduler`/`SimilarityEmbeddingScheduler`/`VisuallySimilarGroupingScheduler` (Task 3).
- Produces: four schedulers wired into `PhotosViewModel.onPhotoAccessGranted()`, consumed by Task 7's UI (indirectly — the UI observes group Flows that these workers populate).

No automated test — WorkManager glue, verified manually (Task 8).

- [ ] **Step 1: Create `HashWorker.kt` and `HashSchedulerImpl.kt`**

```kotlin
package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler
import com.localphotoai.photomanager.domain.similarity.HashPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.SimilarityEmbeddingScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one hashing pass. Chained off [IndexWorker], parallel to [FaceDetectionWorker] (hashing
 * doesn't depend on face detection). On success, triggers both downstream branches: hash-based
 * grouping (duplicate/near-dup/burst) and similarity-embedding generation.
 */
@HiltWorker
class HashWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val hashPhotosUseCase: HashPhotosUseCase,
    private val hashGroupingScheduler: HashGroupingScheduler,
    private val similarityEmbeddingScheduler: SimilarityEmbeddingScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = hashPhotosUseCase()
        return when (result) {
            is AppResult.Success -> {
                hashGroupingScheduler.scheduleImmediateGrouping()
                similarityEmbeddingScheduler.scheduleImmediateEmbedding()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "hash_immediate"
        const val WORK_NAME_PERIODIC = "hash_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
```

```kotlin
package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localphotoai.photomanager.domain.similarity.HashScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HashSchedulerImpl @Inject constructor(@ApplicationContext private val context: Context) : HashScheduler {

    override fun scheduleImmediateHashing() {
        val request = OneTimeWorkRequestBuilder<HashWorker>()
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(HashWorker.WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleIncrementalHashing() {
        val request = PeriodicWorkRequestBuilder<HashWorker>(6, TimeUnit.HOURS)
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HashWorker.WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
```

- [ ] **Step 2: Create `HashGroupingWorker.kt` and `HashGroupingSchedulerImpl.kt`**

```kotlin
package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.DetectDuplicatesUseCase
import com.localphotoai.photomanager.domain.similarity.GroupNearDuplicatesAndBurstsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs both hash-based grouping passes (exact duplicates, then near-duplicate/burst) — combined
 * into one worker since both are cheap, DB-only, pure-grouping passes triggered by the same
 * event (hashing completion), with no meaningful reason to run them as separate WorkManager jobs.
 * Chained off [HashWorker]. Terminal — no further stage depends on this one.
 */
@HiltWorker
class HashGroupingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val detectDuplicatesUseCase: DetectDuplicatesUseCase,
    private val groupNearDuplicatesAndBurstsUseCase: GroupNearDuplicatesAndBurstsUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val duplicateResult = detectDuplicatesUseCase()
        val nearDuplicateResult = groupNearDuplicatesAndBurstsUseCase()
        val failed = duplicateResult is AppResult.Failure || nearDuplicateResult is AppResult.Failure
        return if (!failed) {
            Result.success()
        } else if (runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "hash_grouping_immediate"
        const val WORK_NAME_PERIODIC = "hash_grouping_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
```

```kotlin
package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HashGroupingSchedulerImpl @Inject constructor(@ApplicationContext private val context: Context) : HashGroupingScheduler {

    override fun scheduleImmediateGrouping() {
        val request = OneTimeWorkRequestBuilder<HashGroupingWorker>()
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(HashGroupingWorker.WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleIncrementalGrouping() {
        val request = PeriodicWorkRequestBuilder<HashGroupingWorker>(6, TimeUnit.HOURS)
            .setConstraints(NO_NETWORK_REQUIRED)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HashGroupingWorker.WORK_NAME_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
```

- [ ] **Step 3: Create `SimilarityEmbeddingWorker.kt`/`SimilarityEmbeddingSchedulerImpl.kt` and `VisuallySimilarGroupingWorker.kt`/`VisuallySimilarGroupingSchedulerImpl.kt`**

Follow `EmbeddingWorker`/`EmbeddingSchedulerImpl` and `ClusteringWorker`/`ClusteringSchedulerImpl` exactly (already read in full during planning), substituting: `SimilarityEmbeddingWorker` runs `GenerateImageSimilarityEmbeddingsUseCase` and on success calls `visuallySimilarGroupingScheduler.scheduleImmediateGrouping()`; `VisuallySimilarGroupingWorker` runs `GroupVisuallySimilarPhotosUseCase` and is terminal (no further trigger, same as `ClusteringWorker`). Work names: `"similarity_embedding_immediate"`/`"similarity_embedding_periodic"` and `"visually_similar_grouping_immediate"`/`"visually_similar_grouping_periodic"`. Constructor/scheduler shape identical to Steps 1-2 above — write both worker+scheduler pairs following that exact template.

- [ ] **Step 4: Chain `HashWorker` off `IndexWorker`**

In `IndexWorker.kt`, add a constructor parameter and call:

```kotlin
    private val faceDetectionScheduler: FaceDetectionScheduler,
    private val hashScheduler: com.localphotoai.photomanager.domain.similarity.HashScheduler,
```

and in `doWork()`'s success branch:

```kotlin
            is AppResult.Success -> {
                faceDetectionScheduler.scheduleImmediateDetection()
                hashScheduler.scheduleImmediateHashing()
                Result.success()
            }
```

- [ ] **Step 5: Bind everything in `MediaModule.kt`**

Add four `@Binds` methods (mirroring the existing four) for `HashScheduler`→`HashSchedulerImpl`, `HashGroupingScheduler`→`HashGroupingSchedulerImpl`, `SimilarityEmbeddingScheduler`→`SimilarityEmbeddingSchedulerImpl`, `VisuallySimilarGroupingScheduler`→`VisuallySimilarGroupingSchedulerImpl`, with matching imports.

- [ ] **Step 6: Trigger the new pipeline from `PhotosViewModel`**

In `PhotosViewModel.kt`, add constructor params for the four new schedulers and call their `scheduleIncremental*()` methods in `onPhotoAccessGranted()`, mirroring the existing four calls exactly:

```kotlin
    private val hashScheduler: com.localphotoai.photomanager.domain.similarity.HashScheduler,
    private val hashGroupingScheduler: com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler,
    private val similarityEmbeddingScheduler: com.localphotoai.photomanager.domain.similarity.SimilarityEmbeddingScheduler,
    private val visuallySimilarGroupingScheduler: com.localphotoai.photomanager.domain.similarity.VisuallySimilarGroupingScheduler,
```

```kotlin
        hashScheduler.scheduleIncrementalHashing()
        hashGroupingScheduler.scheduleIncrementalGrouping()
        similarityEmbeddingScheduler.scheduleIncrementalEmbedding()
        visuallySimilarGroupingScheduler.scheduleIncrementalGrouping()
```

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add data/media/src/main/kotlin/com/localphotoai/photomanager/data/media feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos/PhotosViewModel.kt
git commit -m "feat(pipeline): chain hash -> [duplicate/near-dup/burst grouping] and [similarity embed -> visually-similar grouping]"
```

---

### Task 7: `:feature:photos` — DuplicatesScreen and deletion flow

**Files:**
- Create: `feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos/DuplicatesViewModel.kt`
- Create: `feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos/DuplicatesScreen.kt`
- Modify: `feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos/PhotosScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `PhotoGroupRepository.observeDuplicateGroups()`/`observeSimilarGroups(kind)`/`removePhotoFromAllGroups(photoId)` (Tasks 3, 5).

No automated test — ViewModel/Compose, verified manually (Task 8).

- [ ] **Step 1: Add `WRITE_EXTERNAL_STORAGE` for pre-scoped-storage devices**

In `AndroidManifest.xml`, add (near the existing `READ_EXTERNAL_STORAGE` line):

```xml
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

(matches the existing conditional pattern already used for `READ_EXTERNAL_STORAGE` — check that line's exact `android:maxSdkVersion` value and mirror it).

- [ ] **Step 2: Create `DuplicatesViewModel.kt`**

```kotlin
package com.localphotoai.photomanager.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: PhotoGroupRepository,
) : ViewModel() {

    val exactDuplicates: StateFlow<List<DuplicateGroupSummary>> = repository.observeDuplicateGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bursts: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.BURST)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nearDuplicates: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.NEAR_DUPLICATE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visuallySimilar: StateFlow<List<SimilarGroupSummary>> = repository.observeSimilarGroups(SimilarGroupKind.VISUALLY_SIMILAR)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called after a deletion is confirmed and actually performed by the system/legacy delete path. */
    fun onPhotoDeleted(photoId: Long) {
        viewModelScope.launch { repository.removePhotoFromAllGroups(photoId) }
    }
}
```

- [ ] **Step 3: Create `DuplicatesScreen.kt`**

```kotlin
package com.localphotoai.photomanager.feature.photos

import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import kotlinx.coroutines.launch

private enum class DuplicatesTab { EXACT, BURSTS, SIMILAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DuplicatesTab.EXACT) }
    var pendingDeletePhotoId by remember { mutableStateOf<Long?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val photoId = pendingDeletePhotoId
        pendingDeletePhotoId = null
        if (result.resultCode == android.app.Activity.RESULT_OK && photoId != null) {
            viewModel.onPhotoDeleted(photoId)
        }
    }

    fun deletePhoto(photoId: Long) {
        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId.toString())
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                pendingDeletePhotoId = photoId
                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
            else -> scope.launch {
                try {
                    context.contentResolver.delete(uri, null, null)
                    viewModel.onPhotoDeleted(photoId)
                } catch (e: RecoverableSecurityException) {
                    pendingDeletePhotoId = photoId
                    val intentSender: IntentSender = e.userAction.actionIntent.intentSender
                    deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            }
        }
    }

    val exactDuplicates by viewModel.exactDuplicates.collectAsState()
    val bursts by viewModel.bursts.collectAsState()
    val nearDuplicates by viewModel.nearDuplicates.collectAsState()
    val visuallySimilar by viewModel.visuallySimilar.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Duplicates & Similar Photos") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                DuplicatesTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, DuplicatesTab.entries.size),
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                    ) {
                        Text(
                            when (tab) {
                                DuplicatesTab.EXACT -> "Exact"
                                DuplicatesTab.BURSTS -> "Bursts & Near-dupes"
                                DuplicatesTab.SIMILAR -> "Similar"
                            },
                        )
                    }
                }
            }

            when (selectedTab) {
                DuplicatesTab.EXACT -> ExactDuplicateGroupList(exactDuplicates, onDeletePhotos = { it.forEach(::deletePhoto) })
                DuplicatesTab.BURSTS -> SimilarGroupList(bursts + nearDuplicates, onDeletePhotos = { it.forEach(::deletePhoto) })
                DuplicatesTab.SIMILAR -> SimilarGroupList(visuallySimilar, onDeletePhotos = { it.forEach(::deletePhoto) })
            }
        }
    }
}

@Composable
private fun ExactDuplicateGroupList(groups: List<DuplicateGroupSummary>, onDeletePhotos: (List<Long>) -> Unit) {
    if (groups.isEmpty()) {
        EmptyGroupsMessage("No exact duplicates found.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(groups, key = { it.groupId }) { group ->
            GroupCard(photoIds = group.photoIds, similarityLabel = null, onDeletePhotos = onDeletePhotos)
        }
    }
}

@Composable
private fun SimilarGroupList(groups: List<SimilarGroupSummary>, onDeletePhotos: (List<Long>) -> Unit) {
    if (groups.isEmpty()) {
        EmptyGroupsMessage("No groups found.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(groups, key = { it.groupId }) { group ->
            GroupCard(
                photoIds = group.photoIds,
                similarityLabel = "${(group.avgSimilarity * 100).toInt()}% similar",
                onDeletePhotos = onDeletePhotos,
            )
        }
    }
}

@Composable
private fun GroupCard(photoIds: List<Long>, similarityLabel: String?, onDeletePhotos: (List<Long>) -> Unit) {
    var selected by remember(photoIds) { mutableStateOf<Set<Long>>(emptySet()) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (similarityLabel != null) {
                Text(similarityLabel, style = MaterialTheme.typography.labelMedium)
            }
            LazyRow {
                items(photoIds) { photoId ->
                    val isSelected = photoId in selected
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .size(80.dp)
                            .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary)
                            .clickable {
                                selected = if (isSelected) selected - photoId else selected + photoId
                            },
                    ) {
                        AsyncImage(
                            model = Uri.withAppendedPath(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                photoId.toString(),
                            ),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selected.size} selected", style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onDeletePhotos(selected.toList())
                        selected = emptySet()
                    },
                ) {
                    Text("Delete selected")
                }
            }
        }
    }
}

@Composable
private fun EmptyGroupsMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 4: Wire the entry point into `PhotosScreen.kt`**

Add a `Icons.Filled.ContentCopy` (or similar) `IconButton` to `PhotosScreenContent`'s `TopAppBar` `actions`, alongside the existing refresh button, and a `showDuplicates` boolean local state in `PhotosScreen` (same pattern as `selectedPhoto`) that, when true, renders `DuplicatesScreen(onBack = { showDuplicates = false })` instead of `PhotosScreenContent` — mirroring exactly how `PhotoDetailScreen` is already shown as a local Compose state swap, not a new nav route.

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add feature/photos/src/main/kotlin/com/localphotoai/photomanager/feature/photos app/src/main/AndroidManifest.xml
git commit -m "feat(photos): add DuplicatesScreen with confirmed-delete flow"
```

---

### Task 8: Manual verification, performance/memory documentation

**Files:**
- Modify: `docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md` (Phase 7 entry)

No source changes — manual on-device verification, mirroring Phase 6's Task 7 approach (synthetic data via `sqlite3` against a pulled/pushed database copy, `adb`-driven UI checks, `dumpsys gfxinfo` for responsiveness).

- [ ] **Step 1: Migration + pipeline smoke test on existing on-device data**

Install the built APK over the existing Phase 6 on-device database. Confirm via `adb logcat` that migration 5→6 applies without a crash, and that the hash→grouping and hash→embed→group chains run to completion (watch for `WM-WorkerWrapper: Worker result SUCCESS` log lines for `HashWorker`, `HashGroupingWorker`, `SimilarityEmbeddingWorker`, `VisuallySimilarGroupingWorker`).

- [ ] **Step 2: Correctness spot-check via direct SQL**

Pull the on-device database (`adb exec-out run-as com.localphotoai.photomanager cat databases/photo-manager.db`). Confirm: every photo has a non-null `contentHash` (or a `hashError`); `duplicate_groups`/`duplicate_group_members` contain only groups of size ≥2; manually insert two rows with an identical `contentHash` (via `sqlite3`, pushed back per Phase 6's push/pull technique) and confirm a re-run of the app groups them together.

- [ ] **Step 3: UI verification on-device**

Using `adb shell input tap`/`adb exec-out screencap`, open the Duplicates entry point from Photos, confirm all three tabs render (even if empty on real device data), select photos within a group, tap "Delete selected," confirm the system consent dialog appears (API 30+ emulator) and that confirming it actually removes the photo from MediaStore and from the group list afterward.

- [ ] **Step 4: Performance/memory benchmark, per this phase's explicit deliverable**

Using the same "insert synthetic rows directly into a pulled DB copy, push back" technique as Phase 6's Task 7: generate ~2,000 synthetic photos with real, distinct file bytes (not fake URIs — hashing needs real, readable files, unlike Phase 6's query-only synthetic data) by pushing small distinct JPEGs into `DCIM/Camera` via `adb push` (reuse Phase 2's synthetic-JPEG-generation approach), including a deliberate handful of exact-byte-duplicate files and near-duplicate (slightly recompressed) files to exercise every group kind. Run the full pipeline and record: hash throughput (photos/sec, separating SHA-256 time from dHash time if easily separable via logging), MobileNetV3 embedding throughput (photos/sec), and peak memory (via `adb shell dumpsys meminfo com.localphotoai.photomanager` during the run). Document explicitly whether hashing every photo's full file bytes (not just faces) is a meaningfully larger cost than the face pipeline, per the spec's §9 requirement — with real numbers, not an assumption.

- [ ] **Step 5: Record results in the plan doc**

Open `docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md`, find `### Phase 7: Duplicate and similar-photo detection`, and replace its `**Verification gate:**` line with `**Status:** Done` plus `**What was built:**`/`**Verification performed:**`/`**Known limitations:**` sections in the same style as the Phase 6 entry — including, honestly, the in-memory-vs-Room limitation resolved in Task 5 (confirm it was actually resolved there, or document it as a known limitation if it wasn't), the year/scope decisions from the design spec (bundled model, no folder filtering equivalent here since this phase doesn't touch search), and the real measured throughput/memory numbers from Step 4.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md
git commit -m "docs: record Phase 7 duplicate/similar-photo detection verification results"
```

---

## Self-Review Notes

**Spec coverage:** §2 two-tier detection → Tasks 2-3 (hash tier) + Task 4 (embedding tier). §2 model choice → Task 4 (revised from "download" to "bundle" after real-time verification during brainstorming found no clean download URL — documented as a locked deviation in Global Constraints). §3 schema → Task 1 (+ the similarity-embedding table added mid-Task-5 to close a real persistence gap found while writing that task). §4 model evaluation → Task 4 Step 1's real conversion command, license already verified (Apache 2.0, `tf.keras.applications`). §5 pipeline placement → Tasks 4-6. §6 grouping algorithms → Task 2 (clusterer extraction, perceptual hash, pure grouping functions). §7 UI → Task 7. §8 testing scope → reflected throughout (Task 2/3 TDD, Tasks 1/4/5/6/7 manual). §9 performance/memory → Task 8. §10 migration → Task 1. §11 deferred items → correctly absent from every task (no `:fsops` integration, no cross-referencing with people, no re-cluster-from-scratch).

**Placeholder scan:** no TBD/TODO; the one open item (in-memory embedding storage) is flagged inline in Task 5 with an explicit, concrete fix to apply before moving on — not deferred silently.

**Type consistency:** `PhotoGroupRepository`'s method signatures (Task 3) match their call sites in the use cases (Task 3) and the implementation (Task 5) exactly — `PhotoHashInput`, `PhotoForHashing`, `PhotoForSimilarityEmbedding`, `PhotoEmbeddingForSimilarity`, `ExistingSimilarCentroid`, `ClusterAssignmentDto`, `DuplicateGroupSummary`, `SimilarGroupSummary` are defined once (Task 3 Step 1) and reused verbatim in Tasks 5-7. `NearestCentroidClusterer`'s `cluster()` signature (Task 2) matches its two call sites (`FaceClusterer`'s delegation in Task 2, `clusterBySimilarity` in Task 3).
