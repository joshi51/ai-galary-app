# Phase 9 — AI-Assisted Photo Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. **Deviation from the standard writing-plans format**, per this project's standing "basic-level testing only, scoped to business logic" preference: pure-Kotlin business-logic tasks (organization strategies, plan validation, tool/grammar/parser code) use full TDD red/green/commit steps. Tasks touching Room schema wiring, Android `ContentResolver`/`MediaStore` mechanics, or Compose UI use an implement-then-manually-verify structure instead — consistent with how Phases 7/8's native/Android-mechanics work was actually executed in this project.

**Goal:** Let a user ask "Organize my photos," "Organize my screenshots," "Put photos from my Goa trip into an album," or "Find photos that should be archived," and get a reviewable Organization Plan (never an immediate file change) that they can approve, reject, or edit before anything on disk moves.

**Architecture:** Extends Phase 8's tool-calling loop with one new tool (`build_organization_plan`) whose only job is classifying the request into a category + hints — a small, testable set of pure `:domain` strategy functions do the actual photo selection deterministically. A new `:fsops` module is the only place with real filesystem/MediaStore write access, reachable solely through a confirmed, user-reviewed plan.

**Tech Stack:** Kotlin, Room (new migration), `MediaStore.createWriteRequest`/`RecoverableSecurityException` (mirrors this project's existing Phase 7 delete flow), Jetpack Compose.

**Spec:** [docs/superpowers/specs/2026-09-01-phase9-ai-organization-design.md](../specs/2026-09-01-phase9-ai-organization-design.md)

## Global Constraints

- The AI never modifies files directly — every operation flows through Plan → Validation → Review → Confirmation → Execution.
- Operation types are exactly `MOVE`, `COPY`, `RENAME`, `CREATE_FOLDER`, `CREATE_ALBUM` — no others, matching ARCHITECTURE.md §16's locked schema.
- `CREATE_ALBUM` is a virtual, in-app-only Room collection — zero filesystem/MediaStore writes.
- Plan generation is deterministic `:domain` logic; the LLM only classifies the request into a category + optional date/name hints via one grammar-constrained tool call.
- `:fsops` is the only module with real filesystem write access, reachable only through the confirmed-plan execution path, never from `:llm:*` directly.
- The execution layer validates every operation immediately before running it — source exists, destination valid (no path traversal), no collisions, permissions obtainable, no unsupported operation types.
- Partial-failure handling: every operation's result is recorded independently; the summary is always "N succeeded, M failed," never a blanket success claim.
- Never write a test for UI/Compose/ViewModel/DI wiring or Android `ContentResolver`/`MediaStore` mechanics — those are verified manually on-device, per this project's standing testing preference.

---

## File structure overview

```
domain/.../domain/photo/Photo.kt                        — MODIFY (+relativePath)
domain/.../domain/photo/PhotoMetadata.kt                 — MODIFY (+relativePath)
domain/.../domain/organization/OrganizationModels.kt     — NEW (category, operation, plan models)
domain/.../domain/organization/TripClusterer.kt          — NEW (pure GPS+time union-find)
domain/.../domain/organization/ScreenshotOrganizationStrategy.kt — NEW
domain/.../domain/organization/ByDateOrganizationStrategy.kt     — NEW
domain/.../domain/organization/TripOrganizationStrategy.kt       — NEW
domain/.../domain/organization/ArchiveOrganizationStrategy.kt    — NEW
domain/.../domain/organization/BuildOrganizationPlanUseCase.kt   — NEW
domain/.../domain/organization/ConfirmOrganizationPlanUseCase.kt — NEW
domain/.../domain/organization/OrganizationPlanRepository.kt     — NEW (interface)
domain/.../domain/organization/AlbumRepository.kt                — NEW (interface)
domain/.../domain/tool/ToolModels.kt                     — MODIFY (+category/dateHint/nameHint, +ToolOutcome.Plan)

data/database/.../entity/PhotoEntity.kt                  — MODIFY (+relativePath)
data/database/.../entity/AlbumEntity.kt                  — NEW
data/database/.../entity/AlbumPhotoEntity.kt             — NEW
data/database/.../entity/OrganizationPlanEntity.kt       — NEW
data/database/.../entity/OrganizationOperationEntity.kt  — NEW
data/database/.../dao/AlbumDao.kt                        — NEW
data/database/.../dao/OrganizationDao.kt                 — NEW
data/database/.../AppDatabase.kt                         — MODIFY (version 6→7, MIGRATION_6_7)
data/database/.../DatabaseModule.kt                      — MODIFY (+2 DAO providers)
data/database/.../RepositoryModule.kt                    — MODIFY (+2 repository bindings, +2 use-case providers)
data/database/.../PhotoEntityMappers.kt                  — MODIFY (+relativePath)
data/database/.../OrganizationRepositoryImpl.kt          — NEW
data/database/.../AlbumRepositoryImpl.kt                 — NEW

data/media/.../MediaStoreDataSource.kt                   — MODIFY (query RELATIVE_PATH)
data/media/.../PhotoMappers.kt                           — MODIFY (+relativePath)

fsops/build.gradle.kts                                   — NEW module (android.library + hilt)
fsops/src/main/AndroidManifest.xml                       — NEW
fsops/.../fsops/PlanValidator.kt                         — NEW
fsops/.../fsops/MediaStoreWriter.kt                      — NEW
fsops/.../fsops/PlanExecutor.kt                          — NEW
fsops/.../fsops/FsopsModule.kt                           — NEW (Hilt wiring)

tools/.../tools/BuildOrganizationPlanTool.kt              — NEW
tools/.../tools/ToolValidator.kt                          — MODIFY (+category parsing)

llm/orchestration/.../GrammarBuilder.kt                   — MODIFY (+6th alternative)
llm/orchestration/.../ToolCallParser.kt                   — MODIFY (+category/dateHint/nameHint)
llm/orchestration/.../ToolCallLoop.kt                     — MODIFY (prompt + Plan-outcome handling)

llm/runtime/.../RuntimeModule.kt                          — MODIFY (+tool/use-case providers)

feature/search/build.gradle.kts                           — MODIFY (+:fsops dependency)
feature/search/.../SearchViewModel.kt                     — MODIFY (+Plan ui state)
feature/search/.../SearchScreen.kt                        — MODIFY (swap to review screen)
feature/search/.../OrganizationReviewScreen.kt            — NEW
feature/search/.../OrganizationReviewViewModel.kt         — NEW

app/build.gradle.kts                                      — MODIFY (+:fsops dependency)
```

---

### Task 1: Schema — `relativePath`, album tables, organization-plan tables (migration 6→7)

**Files:**
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo/Photo.kt`
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo/PhotoMetadata.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/AlbumEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/AlbumPhotoEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/OrganizationPlanEntity.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/OrganizationOperationEntity.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/PhotoEntityMappers.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/MediaStoreDataSource.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/PhotoMappers.kt`

**Interfaces:**
- Produces: `Photo.relativePath: String?`, `PhotoMetadata.relativePath: String?` — every later task's strategy functions read `Photo.relativePath`. `AlbumEntity`, `AlbumPhotoEntity`, `OrganizationPlanEntity`, `OrganizationOperationEntity` — Task 5's repositories read/write these.
- Consumes: nothing new (this is the base schema task).

This is manual-verification work (Room schema + MediaStore query), not unit-testable — no TDD steps.

- [ ] **Step 1: Add `relativePath` to the domain/metadata models**

In `Photo.kt`, add one field after `indexError`:
```kotlin
    val indexError: String?,
    val relativePath: String? = null,
```
(keep `facesDetectedAt`/`faceDetectionError` after it, unchanged). In `PhotoMetadata.kt`, add the same field after `indexError`:
```kotlin
    val indexError: String? = null,
    val relativePath: String? = null,
```

- [ ] **Step 2: Query `RELATIVE_PATH` in `MediaStoreDataSource.queryFullMetadata`**

Add `MediaStore.Images.Media.RELATIVE_PATH` to the `projection` array (after `DATE_TAKEN`), read it in the cursor loop:
```kotlin
            val relativePathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
```
and pass `relativePath = cursor.getStringOrNull(relativePathCol)` into the `PhotoMetadata(...)` constructor call.

- [ ] **Step 3: Update the entity/domain mappers**

In `data/media/.../PhotoMappers.kt`'s `PhotoMetadata.toEntity()`, add `relativePath = relativePath,`. In `data/database/.../PhotoEntityMappers.kt`'s `PhotoEntity.toDomain()`, add `relativePath = relativePath,`.

- [ ] **Step 4: Add the column and new entities**

In `PhotoEntity.kt`, add after `hashError`:
```kotlin
    val relativePath: String? = null,
```

`AlbumEntity.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)
```

`AlbumPhotoEntity.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index

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
```

`OrganizationPlanEntity.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "organization_plans")
data class OrganizationPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestText: String,
    val category: String,
    val createdAtMs: Long,
    val status: String,
)
```

`OrganizationOperationEntity.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "organization_operations",
    foreignKeys = [
        ForeignKey(entity = OrganizationPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = CASCADE),
    ],
    indices = [Index("planId")],
)
data class OrganizationOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val opType: String,
    val source: String?,
    val destination: String,
    val reason: String,
    val confidence: Float?,
    val memberPhotoIdsCsv: String?,
    val reviewStatus: String,
    val executionResult: String?,
    val executionError: String?,
)
```

- [ ] **Step 5: Register the migration and new entities/DAOs in `AppDatabase.kt`**

Add the four new entity classes to the `entities = [...]` list, bump `version = 7`. Add a new migration:
```kotlin
/** Phase 9: relativePath column, album tables, organization-plan tables. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE photos ADD COLUMN relativePath TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS albums (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL
            )
            """,
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS album_photos (
                albumId INTEGER NOT NULL,
                photoId INTEGER NOT NULL,
                PRIMARY KEY(albumId, photoId),
                FOREIGN KEY(albumId) REFERENCES albums(id) ON DELETE CASCADE,
                FOREIGN KEY(photoId) REFERENCES photos(mediaStoreId) ON DELETE CASCADE
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_album_photos_albumId ON album_photos(albumId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_album_photos_photoId ON album_photos(photoId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS organization_plans (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                requestText TEXT NOT NULL,
                category TEXT NOT NULL,
                createdAtMs INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """,
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS organization_operations (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                planId INTEGER NOT NULL,
                opType TEXT NOT NULL,
                source TEXT,
                destination TEXT NOT NULL,
                reason TEXT NOT NULL,
                confidence REAL,
                memberPhotoIdsCsv TEXT,
                reviewStatus TEXT NOT NULL,
                executionResult TEXT,
                executionError TEXT,
                FOREIGN KEY(planId) REFERENCES organization_plans(id) ON DELETE CASCADE
            )
            """,
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_organization_operations_planId ON organization_operations(planId)")
    }
}
```
Add `MIGRATION_6_7` to `DatabaseModule.kt`'s `.addMigrations(...)` call.

- [ ] **Step 6: Manually verify**

Run: `./gradlew :domain:test :app:assembleDebug`
Expected: BUILD SUCCESSFUL, Room's schema validation passes (a missing migration step throws at this point, not silently). No test changes needed yet — `Photo`'s new field has a default, so no existing test constructor call breaks.

---

### Task 2: Organization domain models

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/OrganizationModels.kt`

**Interfaces:**
- Produces: `enum class OrganizationCategory { SCREENSHOTS, BY_DATE, TRIP, ARCHIVE }`, `enum class OperationType { MOVE, COPY, RENAME, CREATE_FOLDER, CREATE_ALBUM }`, `enum class ReviewStatus { PENDING, APPROVED, REJECTED, EDITED }`, `data class OrganizationOperation(id: Long = 0, opType: OperationType, source: String?, destination: String, reason: String, confidence: Float?, memberPhotoIds: List<Long> = emptyList(), reviewStatus: ReviewStatus = ReviewStatus.PENDING, executionResult: Boolean? = null, executionError: String? = null)`, `data class OrganizationPlan(id: Long = 0, requestText: String, category: OrganizationCategory, createdAtMs: Long, operations: List<OrganizationOperation>)`, `interface OrganizationPlanRepository`, `interface AlbumRepository` — every later task in this plan consumes these exact types.
- Consumes: nothing new.

No TDD steps — this is a models-and-interfaces-only file with no behavior to test (matches how `PhotoSearchFilter`/`ToolModels.kt` were added in Phases 6/8 without their own test file).

- [ ] **Step 1: Write the file**

```kotlin
package com.localphotoai.photomanager.domain.organization

import kotlinx.coroutines.flow.Flow

enum class OrganizationCategory {
    SCREENSHOTS, BY_DATE, TRIP, ARCHIVE
}

enum class OperationType {
    MOVE, COPY, RENAME, CREATE_FOLDER, CREATE_ALBUM
}

enum class ReviewStatus {
    PENDING, APPROVED, REJECTED, EDITED
}

/**
 * One proposed filesystem/album action. [source] is a single photo's current URI for
 * MOVE/COPY/RENAME, null for CREATE_FOLDER/CREATE_ALBUM. [destination] is a target path for
 * file operations, or the album name for CREATE_ALBUM. [memberPhotoIds] is populated only for
 * CREATE_ALBUM — one CREATE_ALBUM operation covers every member photo as plan-level detail,
 * not one operation per photo (see the Phase 9 design spec §2).
 */
data class OrganizationOperation(
    val id: Long = 0,
    val opType: OperationType,
    val source: String?,
    val destination: String,
    val reason: String,
    val confidence: Float?,
    val memberPhotoIds: List<Long> = emptyList(),
    val reviewStatus: ReviewStatus = ReviewStatus.PENDING,
    val executionResult: Boolean? = null,
    val executionError: String? = null,
)

data class OrganizationPlan(
    val id: Long = 0,
    val requestText: String,
    val category: OrganizationCategory,
    val createdAtMs: Long,
    val operations: List<OrganizationOperation>,
)

/** Access to persisted organization plans. Implemented in `:data:database` (Room only). */
interface OrganizationPlanRepository {
    suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan
    suspend fun fetchPlan(planId: Long): OrganizationPlan?
    fun observePlan(planId: Long): Flow<OrganizationPlan?>
    suspend fun updateOperation(operation: OrganizationOperation)
}

/** Access to the virtual, in-app-only album collection. Implemented in `:data:database` (Room only). */
interface AlbumRepository {
    suspend fun createAlbum(name: String, photoIds: List<Long>): Long
}
```

- [ ] **Step 2: Manually verify**

Run: `./gradlew :domain:test`
Expected: BUILD SUCCESSFUL (compiles; nothing to test yet).

---

### Task 3: `TripClusterer` — GPS+time union-find clustering (pure, TDD)

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/TripClusterer.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/TripClustererTest.kt`

**Interfaces:**
- Produces: `data class GpsTaggedPhoto(photoId: Long, latitude: Double, longitude: Double, dateTakenMs: Long)`, `data class TripCluster(photoIds: List<Long>, startDateMs: Long, endDateMs: Long, tightness: Float)`, `object TripClusterer { const val DISTANCE_THRESHOLD_METERS = 50_000.0; const val TIME_GAP_MS = 86_400_000L; const val MIN_PHOTOS = 3; fun cluster(photos: List<GpsTaggedPhoto>): List<TripCluster> }` — Task 4's `TripOrganizationStrategy` consumes this.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.localphotoai.photomanager.domain.organization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L

// Roughly 1 degree of latitude ~= 111km, well outside the 50km threshold — used to build
// "far apart" fixtures without needing a real haversine calculation in the test itself.
private fun photo(id: Long, lat: Double, lon: Double, dayOffset: Long) =
    GpsTaggedPhoto(photoId = id, latitude = lat, longitude = lon, dateTakenMs = dayOffset * DAY_MS)

class TripClustererTest {

    @Test
    fun `photos close in both distance and time join one cluster`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.3000, 74.1250, dayOffset = 100),
            photo(3, 15.2990, 74.1245, dayOffset = 101),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(1, clusters.size)
        assertEquals(setOf(1L, 2L, 3L), clusters[0].photoIds.toSet())
    }

    @Test
    fun `photos far apart in distance do not join even if taken on the same day`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
            photo(3, 15.2991, 74.1241, dayOffset = 100),
            photo(4, 28.6139, 77.2090, dayOffset = 100),
            photo(5, 28.6140, 77.2091, dayOffset = 100),
            photo(6, 28.6141, 77.2089, dayOffset = 100),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(2, clusters.size)
        assertTrue(clusters.none { it.photoIds.contains(1L) && it.photoIds.contains(4L) })
    }

    @Test
    fun `photos close in distance but far apart in time do not join`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
            photo(3, 15.2991, 74.1241, dayOffset = 100),
            photo(4, 15.2993, 74.1240, dayOffset = 400),
            photo(5, 15.2995, 74.1242, dayOffset = 400),
            photo(6, 15.2991, 74.1241, dayOffset = 400),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(2, clusters.size)
    }

    @Test
    fun `a cluster smaller than MIN_PHOTOS is discarded as noise`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 100),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(0, clusters.size)
    }

    @Test
    fun `cluster date range spans its earliest to latest member`() {
        val photos = listOf(
            photo(1, 15.2993, 74.1240, dayOffset = 100),
            photo(2, 15.2995, 74.1242, dayOffset = 101),
            photo(3, 15.2991, 74.1241, dayOffset = 102),
        )

        val clusters = TripClusterer.cluster(photos)

        assertEquals(1, clusters.size)
        assertEquals(100 * DAY_MS, clusters[0].startDateMs)
        assertEquals(102 * DAY_MS, clusters[0].endDateMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.TripClustererTest"`
Expected: FAIL to compile — `GpsTaggedPhoto`/`TripCluster`/`TripClusterer` don't exist.

- [ ] **Step 3: Implement**

```kotlin
package com.localphotoai.photomanager.domain.organization

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsTaggedPhoto(
    val photoId: Long,
    val latitude: Double,
    val longitude: Double,
    val dateTakenMs: Long,
)

data class TripCluster(
    val photoIds: List<Long>,
    val startDateMs: Long,
    val endDateMs: Long,
    /** Fraction of members within half [TripClusterer.DISTANCE_THRESHOLD_METERS] of the
     * cluster's centroid — a simple tightness proxy, not a claimed accuracy metric. */
    val tightness: Float,
)

/**
 * Groups GPS-tagged photos into candidate "trip" clusters via union-find: two photos join the
 * same cluster when both their distance and time gap clear the named thresholds below — both
 * conditions, not either alone, so a recurring nearby commute doesn't collapse into one endless
 * "trip" just because it repeats daily, and a same-day long-distance flight doesn't merge two
 * unrelated locations into one trip either. Named, documented, untuned constants — same honest
 * treatment as every prior phase's heuristic thresholds (no labeled ground-truth trip dataset
 * was available to calibrate against).
 */
object TripClusterer {
    const val DISTANCE_THRESHOLD_METERS = 50_000.0
    const val TIME_GAP_MS = 86_400_000L // 24 hours
    const val MIN_PHOTOS = 3

    fun cluster(photos: List<GpsTaggedPhoto>): List<TripCluster> {
        val parent = photos.associate { it.photoId to it.photoId }.toMutableMap()

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

        for (i in photos.indices) {
            for (j in i + 1 until photos.size) {
                val a = photos[i]
                val b = photos[j]
                val distance = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                val timeGap = kotlin.math.abs(a.dateTakenMs - b.dateTakenMs)
                if (distance <= DISTANCE_THRESHOLD_METERS && timeGap <= TIME_GAP_MS) {
                    union(a.photoId, b.photoId)
                }
            }
        }

        val byId = photos.associateBy { it.photoId }
        return photos.map { it.photoId }.groupBy { find(it) }.values
            .filter { it.size >= MIN_PHOTOS }
            .map { ids ->
                val members = ids.map { byId.getValue(it) }
                val centroidLat = members.sumOf { it.latitude } / members.size
                val centroidLon = members.sumOf { it.longitude } / members.size
                val halfThreshold = DISTANCE_THRESHOLD_METERS / 2
                val tightCount = members.count {
                    haversineMeters(it.latitude, it.longitude, centroidLat, centroidLon) <= halfThreshold
                }
                TripCluster(
                    photoIds = ids,
                    startDateMs = members.minOf { it.dateTakenMs },
                    endDateMs = members.maxOf { it.dateTakenMs },
                    tightness = tightCount.toFloat() / members.size,
                )
            }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.TripClustererTest"`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit** (only if the user has asked for commits in this session)

---

### Task 4: The four organization strategies (pure, TDD)

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/ScreenshotDetection.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/ScreenshotOrganizationStrategy.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/ByDateOrganizationStrategy.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/TripOrganizationStrategy.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/ArchiveOrganizationStrategy.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/ScreenshotOrganizationStrategyTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/ByDateOrganizationStrategyTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/TripOrganizationStrategyTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/ArchiveOrganizationStrategyTest.kt`

**Interfaces:**
- Produces: `fun isScreenshot(photo: Photo): Boolean`, `object ScreenshotOrganizationStrategy { fun build(photos: List<Photo>): List<OrganizationOperation> }`, `object ByDateOrganizationStrategy { fun build(photos: List<Photo>): List<OrganizationOperation> }`, `object TripOrganizationStrategy { fun build(photos: List<Photo>, dateHint: String?, nameHint: String?): List<OrganizationOperation> }`, `object ArchiveOrganizationStrategy { const val SCREENSHOT_AGE_MS: Long; fun build(photos: List<Photo>, duplicateGroups: List<DuplicateGroupSummary>, nowMs: Long): List<OrganizationOperation> }` — Task 5's `BuildOrganizationPlanUseCase` calls all four.
- Consumes: `Photo` (Task 1, with `relativePath`), `OrganizationOperation`/`OperationType` (Task 2), `TripClusterer`/`GpsTaggedPhoto` (Task 3), `DuplicateGroupSummary` (pre-existing, Phase 7 — `domain.similarity.DuplicateGroupSummary(groupId: Long, photoIds: List<Long>, totalSizeBytes: Long)`).

Check `Photo`'s real constructor (Task 1 added `relativePath` after `indexError`) before writing test fixtures below.

- [ ] **Step 1: Write the failing tests**

`ScreenshotOrganizationStrategyTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, filename: String, relativePath: String? = "DCIM/Camera/") = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1_000L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = relativePath,
)

class ScreenshotOrganizationStrategyTest {

    @Test
    fun `matches filenames containing Screenshot case-insensitively`() {
        val photos = listOf(
            testPhoto(1, "Screenshot_20250101.png"),
            testPhoto(2, "screenshot-2.png"),
            testPhoto(3, "IMG_1234.jpg"),
        )

        val ops = ScreenshotOrganizationStrategy.build(photos)

        val moves = ops.filter { it.opType == OperationType.MOVE }
        assertEquals(setOf(1L, 2L), moves.map { it.source }.map { it!!.substringAfterLast("//") }.toSet())
    }

    @Test
    fun `photos already in the Screenshots folder are skipped`() {
        val photos = listOf(testPhoto(1, "Screenshot_1.png", relativePath = "Pictures/Screenshots/"))

        val ops = ScreenshotOrganizationStrategy.build(photos)

        assertTrue(ops.none { it.opType == OperationType.MOVE })
    }

    @Test
    fun `includes exactly one CREATE_FOLDER when there is at least one match`() {
        val photos = listOf(testPhoto(1, "Screenshot_1.png"))

        val ops = ScreenshotOrganizationStrategy.build(photos)

        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
    }

    @Test
    fun `no matches produces no operations at all`() {
        val ops = ScreenshotOrganizationStrategy.build(listOf(testPhoto(1, "IMG_1.jpg")))
        assertEquals(0, ops.size)
    }
}
```

`ByDateOrganizationStrategyTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, dateTakenMs: Long?, relativePath: String? = "DCIM/Camera/") = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dateTakenMs, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = relativePath,
)

class ByDateOrganizationStrategyTest {

    @Test
    fun `groups photos in the raw camera folder by year-month`() {
        val photos = listOf(
            testPhoto(1, dateTakenMs = 1_735_689_600_000L), // 2025-01-01
            testPhoto(2, dateTakenMs = 1_738_368_000_000L), // 2025-02-01
        )

        val ops = ByDateOrganizationStrategy.build(photos)

        assertEquals(2, ops.count { it.opType == OperationType.CREATE_FOLDER })
        assertEquals(2, ops.count { it.opType == OperationType.MOVE })
    }

    @Test
    fun `photos with a null dateTakenMs are skipped`() {
        val ops = ByDateOrganizationStrategy.build(listOf(testPhoto(1, dateTakenMs = null)))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `photos already outside the raw camera folder are skipped`() {
        val ops = ByDateOrganizationStrategy.build(
            listOf(testPhoto(1, dateTakenMs = 1_735_689_600_000L, relativePath = "Pictures/2025/2025-01/")),
        )
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `same year-month photos share one CREATE_FOLDER operation`() {
        val photos = listOf(
            testPhoto(1, dateTakenMs = 1_735_689_600_000L),
            testPhoto(2, dateTakenMs = 1_735_776_000_000L),
        )

        val ops = ByDateOrganizationStrategy.build(photos)

        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
        assertEquals(2, ops.count { it.opType == OperationType.MOVE })
    }
}
```

`TripOrganizationStrategyTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L

private fun tripPhoto(id: Long, lat: Double, lon: Double, dayOffset: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dayOffset * DAY_MS, orientationDegrees = 0, latitude = lat, longitude = lon,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

class TripOrganizationStrategyTest {

    private val goaCluster = listOf(
        tripPhoto(1, 15.2993, 74.1240, 100),
        tripPhoto(2, 15.2995, 74.1242, 100),
        tripPhoto(3, 15.2991, 74.1241, 101),
    )
    private val delhiCluster = listOf(
        tripPhoto(4, 28.6139, 77.2090, 200),
        tripPhoto(5, 28.6140, 77.2091, 200),
        tripPhoto(6, 28.6141, 77.2089, 201),
    )

    @Test
    fun `with no dateHint, picks the most recent cluster`() {
        val ops = TripOrganizationStrategy.build(goaCluster + delhiCluster, dateHint = null, nameHint = "My Trip")

        val createAlbum = ops.single { it.opType == OperationType.CREATE_ALBUM }
        assertEquals(setOf(4L, 5L, 6L), createAlbum.memberPhotoIds.toSet())
    }

    @Test
    fun `a dateHint inside a cluster's range picks that cluster`() {
        val hintDate = java.text.SimpleDateFormat("yyyy-MM-dd").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(100 * DAY_MS))

        val ops = TripOrganizationStrategy.build(goaCluster + delhiCluster, dateHint = hintDate, nameHint = null)

        val createAlbum = ops.single { it.opType == OperationType.CREATE_ALBUM }
        assertEquals(setOf(1L, 2L, 3L), createAlbum.memberPhotoIds.toSet())
    }

    @Test
    fun `nameHint becomes the album name, falling back to a date-range name`() {
        val named = TripOrganizationStrategy.build(goaCluster, dateHint = null, nameHint = "Goa Trip")
        assertEquals("Goa Trip", named.single { it.opType == OperationType.CREATE_ALBUM }.destination)

        val unnamed = TripOrganizationStrategy.build(goaCluster, dateHint = null, nameHint = null)
        assertTrue(unnamed.single { it.opType == OperationType.CREATE_ALBUM }.destination.startsWith("Trip "))
    }

    @Test
    fun `no clusters found produces no operations`() {
        val ops = TripOrganizationStrategy.build(emptyList(), dateHint = null, nameHint = null)
        assertTrue(ops.isEmpty())
    }
}
```

`ArchiveOrganizationStrategyTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY_MS = 86_400_000L
private const val NOW_MS = 2_000_000_000_000L

private fun testPhoto(id: Long, filename: String, dateTakenMs: Long?) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = dateTakenMs, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

class ArchiveOrganizationStrategyTest {

    @Test
    fun `an old screenshot is flagged for archiving`() {
        val oldScreenshot = testPhoto(1, "Screenshot_old.png", NOW_MS - (400L * DAY_MS))
        val ops = ArchiveOrganizationStrategy.build(listOf(oldScreenshot), emptyList(), NOW_MS)
        assertEquals(1, ops.count { it.opType == OperationType.MOVE })
    }

    @Test
    fun `a recent screenshot is not flagged`() {
        val recentScreenshot = testPhoto(1, "Screenshot_new.png", NOW_MS - DAY_MS)
        val ops = ArchiveOrganizationStrategy.build(listOf(recentScreenshot), emptyList(), NOW_MS)
        assertTrue(ops.none { it.opType == OperationType.MOVE })
    }

    @Test
    fun `a non-representative duplicate group member is flagged, the representative is not`() {
        val photos = listOf(
            testPhoto(1, "IMG_1.jpg", NOW_MS - DAY_MS),
            testPhoto(2, "IMG_2.jpg", NOW_MS - DAY_MS),
        )
        val groups = listOf(DuplicateGroupSummary(groupId = 1L, photoIds = listOf(1L, 2L), totalSizeBytes = 200L))

        val ops = ArchiveOrganizationStrategy.build(photos, groups, NOW_MS)

        val movedSources = ops.filter { it.opType == OperationType.MOVE }.map { it.source }
        assertTrue(movedSources.any { it?.contains("2") == true })
        assertTrue(movedSources.none { it?.contains("content://1") == true })
    }

    @Test
    fun `includes a CREATE_FOLDER when anything matches`() {
        val ops = ArchiveOrganizationStrategy.build(
            listOf(testPhoto(1, "Screenshot_old.png", NOW_MS - (400L * DAY_MS))),
            emptyList(),
            NOW_MS,
        )
        assertEquals(1, ops.count { it.opType == OperationType.CREATE_FOLDER })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.*"`
Expected: FAIL to compile — none of the strategy objects exist yet.

- [ ] **Step 3: Implement**

`ScreenshotDetection.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo

/** Filename-pattern heuristic matching Android's own screenshot naming convention. Shared by
 * [ScreenshotOrganizationStrategy] and [ArchiveOrganizationStrategy] so both agree on what
 * counts as a screenshot. */
fun isScreenshot(photo: Photo): Boolean = photo.filename.contains("screenshot", ignoreCase = true)
```

`ScreenshotOrganizationStrategy.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo

private const val SCREENSHOTS_FOLDER = "Pictures/Screenshots"

object ScreenshotOrganizationStrategy {
    fun build(photos: List<Photo>): List<OrganizationOperation> {
        val matches = photos.filter { isScreenshot(it) && it.relativePath?.startsWith("Pictures/Screenshots") != true }
        if (matches.isEmpty()) return emptyList()

        val createFolder = OrganizationOperation(
            opType = OperationType.CREATE_FOLDER,
            source = null,
            destination = SCREENSHOTS_FOLDER,
            reason = "Destination folder for detected screenshots",
            confidence = 1.0f,
        )
        val moves = matches.map { photo ->
            OrganizationOperation(
                opType = OperationType.MOVE,
                source = photo.uri,
                destination = "$SCREENSHOTS_FOLDER/${photo.filename}",
                reason = "Filename matches screenshot pattern",
                confidence = 0.9f,
            )
        }
        return listOf(createFolder) + moves
    }
}
```

`ByDateOrganizationStrategy.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val RAW_CAMERA_FOLDER = "DCIM/Camera"

private fun yearMonthFormat() = SimpleDateFormat("yyyy/yyyy-MM", Locale.US).apply {
    timeZone = TimeZone.getDefault()
}

object ByDateOrganizationStrategy {
    fun build(photos: List<Photo>): List<OrganizationOperation> {
        val eligible = photos.filter {
            it.dateTakenMs != null && it.relativePath?.startsWith(RAW_CAMERA_FOLDER) == true
        }
        if (eligible.isEmpty()) return emptyList()

        val format = yearMonthFormat()
        val byMonth = eligible.groupBy { format.format(Date(it.dateTakenMs!!)) }

        return byMonth.flatMap { (monthFolder, monthPhotos) ->
            val destinationFolder = "Pictures/$monthFolder"
            val createFolder = OrganizationOperation(
                opType = OperationType.CREATE_FOLDER,
                source = null,
                destination = destinationFolder,
                reason = "Grouping by capture date",
                confidence = 1.0f,
            )
            val moves = monthPhotos.map { photo ->
                OrganizationOperation(
                    opType = OperationType.MOVE,
                    source = photo.uri,
                    destination = "$destinationFolder/${photo.filename}",
                    reason = "Grouping by capture date",
                    confidence = 1.0f,
                )
            }
            listOf(createFolder) + moves
        }
    }
}
```

`TripOrganizationStrategy.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TripOrganizationStrategy {
    fun build(photos: List<Photo>, dateHint: String?, nameHint: String?): List<OrganizationOperation> {
        val gpsPhotos = photos.mapNotNull { photo ->
            val lat = photo.latitude
            val lon = photo.longitude
            val taken = photo.dateTakenMs
            if (lat != null && lon != null && taken != null) {
                GpsTaggedPhoto(photo.mediaStoreId, lat, lon, taken)
            } else {
                null
            }
        }
        val clusters = TripClusterer.cluster(gpsPhotos)
        if (clusters.isEmpty()) return emptyList()

        val hintMs = dateHint?.let { parseIsoDateOrNull(it) }
        val chosen = if (hintMs != null) {
            clusters.filter { hintMs in it.startDateMs..it.endDateMs }
                .minByOrNull { kotlin.math.abs(it.startDateMs - hintMs) }
                ?: clusters.minBy { kotlin.math.abs(it.startDateMs - hintMs) }
        } else {
            clusters.maxBy { it.endDateMs }
        }

        val albumName = nameHint?.takeIf { it.isNotBlank() } ?: run {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            "Trip ${format.format(java.util.Date(chosen.startDateMs))}–${format.format(java.util.Date(chosen.endDateMs))}"
        }

        return listOf(
            OrganizationOperation(
                opType = OperationType.CREATE_ALBUM,
                source = null,
                destination = albumName,
                reason = "Photos clustered by location and date proximity",
                confidence = chosen.tightness,
                memberPhotoIds = chosen.photoIds,
            ),
        )
    }

    private fun parseIsoDateOrNull(value: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(value)?.time
    } catch (e: Exception) {
        null
    }
}
```

`ArchiveOrganizationStrategy.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary

private const val ARCHIVE_FOLDER = "Pictures/Archive"

object ArchiveOrganizationStrategy {
    /** Named, documented, untuned heuristic — same honest treatment as every prior phase's
     * thresholds. ~12 months. */
    const val SCREENSHOT_AGE_MS = 365L * 24 * 60 * 60 * 1000

    fun build(photos: List<Photo>, duplicateGroups: List<DuplicateGroupSummary>, nowMs: Long): List<OrganizationOperation> {
        val byId = photos.associateBy { it.mediaStoreId }

        val oldScreenshots = photos.filter { photo ->
            isScreenshot(photo) && photo.dateTakenMs != null && (nowMs - photo.dateTakenMs) > SCREENSHOT_AGE_MS
        }.map { it to "Screenshot older than 12 months" to 0.7f }

        val nonRepresentativeDuplicates = duplicateGroups.flatMap { group ->
            val representativeId = group.photoIds.min()
            group.photoIds.filter { it != representativeId }
        }.mapNotNull { byId[it] }.map { it to "Duplicate of another photo already in your library" to 0.95f }

        val matches = (oldScreenshots + nonRepresentativeDuplicates).distinctBy { (photo, _) -> photo.mediaStoreId }
        if (matches.isEmpty()) return emptyList()

        val createFolder = OrganizationOperation(
            opType = OperationType.CREATE_FOLDER,
            source = null,
            destination = ARCHIVE_FOLDER,
            reason = "Destination folder for archive candidates",
            confidence = 1.0f,
        )
        val moves = matches.map { (photo, reasonAndConfidence) ->
            val (reason, confidence) = reasonAndConfidence
            OrganizationOperation(
                opType = OperationType.MOVE,
                source = photo.uri,
                destination = "$ARCHIVE_FOLDER/${photo.filename}",
                reason = reason,
                confidence = confidence,
            )
        }
        return listOf(createFolder) + moves
    }
}
```

Note the slightly awkward `Pair<Pair<Photo, String>, Float>` shape from `.map { it to "..." to 0.7f }` (Kotlin's `to` is left-associative) — destructure as `(photo, reasonAndConfidence)` where `reasonAndConfidence: Pair<String, Float>`, exactly as written above; if this reads unclearly during implementation, an equally correct alternative is a small local `data class ArchiveMatch(val photo: Photo, val reason: String, val confidence: Float)` instead of nested `Pair`s — either compiles and passes the tests above, use whichever reads more clearly.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.*"`
Expected: PASS, all cases green (17 new test cases across the four files).

- [ ] **Step 5: Commit** (only if the user has asked for commits in this session)

---

### Task 5: `BuildOrganizationPlanUseCase`, `ConfirmOrganizationPlanUseCase`, Room repositories

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/BuildOrganizationPlanUseCase.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/organization/ConfirmOrganizationPlanUseCase.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/BuildOrganizationPlanUseCaseTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/organization/ConfirmOrganizationPlanUseCaseTest.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/OrganizationDao.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/AlbumDao.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/OrganizationRepositoryImpl.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AlbumRepositoryImpl.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/RepositoryModule.kt`

**Interfaces:**
- Produces: `class BuildOrganizationPlanUseCase(photoRepository: PhotoRepository, photoGroupRepository: PhotoGroupRepository, organizationPlanRepository: OrganizationPlanRepository) { suspend operator fun invoke(requestText: String, category: OrganizationCategory, dateHint: String?, nameHint: String?): AppResult<OrganizationPlan> }`, `class ConfirmOrganizationPlanUseCase(organizationPlanRepository: OrganizationPlanRepository, albumRepository: AlbumRepository) { suspend fun confirm(planId: Long, decisions: List<OperationDecision>): AppResult<OrganizationPlan> }`, `data class OperationDecision(operationId: Long, status: ReviewStatus, editedDestination: String? = null, editedMemberPhotoIds: List<Long>? = null)` — Task 9's `BuildOrganizationPlanTool` and Task 12's `OrganizationReviewViewModel` consume these.
- Consumes: `OrganizationPlan`/`OrganizationOperation`/`OrganizationPlanRepository`/`AlbumRepository` (Task 2), the four strategies (Task 4), `PhotoRepository`/`PhotoGroupRepository` (pre-existing).

- [ ] **Step 1: Write the failing tests**

`BuildOrganizationPlanUseCaseTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.similarity.PhotoForHashing
import com.localphotoai.photomanager.domain.similarity.PhotoHashInput
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKindResult
import com.localphotoai.photomanager.domain.similarity.SimilarGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoForSimilarityEmbedding
import com.localphotoai.photomanager.domain.similarity.PhotoEmbeddingForSimilarity
import com.localphotoai.photomanager.domain.similarity.ExistingSimilarCentroid
import com.localphotoai.photomanager.domain.similarity.ClusterAssignmentDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long, filename: String) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = filename, mimeType = "image/png",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1_000L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

private class BuildPlanFakePhotoRepository(private val photos: List<Photo>) : PhotoRepository {
    override fun observePhotos(): Flow<List<Photo>> = flowOf(photos)
    override fun observeIndexingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun fetchGeneration(): Long? = null
    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> = emptyList()
    override suspend fun upsert(photos: List<PhotoMetadata>) {}
    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) {}
    override suspend fun updateIndexingProgress(progress: IndexingProgress) {}
    override suspend fun saveGeneration(generation: Long) {}
    override suspend fun lastSavedGeneration(): Long? = null
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photos.firstOrNull { it.mediaStoreId == mediaStoreId }
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = photos.filter { it.mediaStoreId in mediaStoreIds }
}

private class BuildPlanFakePhotoGroupRepository : PhotoGroupRepository {
    override suspend fun fetchPhotosNeedingHash(): List<PhotoForHashing> = emptyList()
    override suspend fun saveHash(photoId: Long, contentHash: String, perceptualHash: Long) {}
    override suspend fun markHashFailed(photoId: Long, error: String) {}
    override fun observeHashProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateHashProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllHashes(): List<PhotoHashInput> = emptyList()
    override suspend fun replaceDuplicateGroups(photoIdGroupsByHash: Map<String, List<Long>>) {}
    override fun observeDuplicateGroups(): Flow<List<DuplicateGroupSummary>> = flowOf(emptyList())
    override suspend fun replaceNearDuplicateAndBurstGroups(groups: List<Pair<SimilarGroupKindResult, List<Long>>>) {}
    override fun observeSimilarGroups(kind: SimilarGroupKind): Flow<List<SimilarGroupSummary>> = flowOf(emptyList())
    override suspend fun fetchPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForSimilarityEmbedding> = emptyList()
    override suspend fun saveSimilarityEmbedding(photoId: Long, modelVersion: Int, vector: FloatArray) {}
    override suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String) {}
    override fun observeSimilarityEmbeddingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateSimilarityEmbeddingProgress(progress: IndexingProgress) {}
    override suspend fun fetchAllSimilarityEmbeddings(): List<PhotoEmbeddingForSimilarity> = emptyList()
    override suspend fun fetchExistingSimilarClusters(): List<ExistingSimilarCentroid> = emptyList()
    override suspend fun applyVisuallySimilarGroupingResult(embeddings: List<PhotoEmbeddingForSimilarity>, assignments: List<ClusterAssignmentDto>, newClusterCount: Int) {}
    override fun observeGroupingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun updateGroupingProgress(progress: IndexingProgress) {}
    override suspend fun removePhotoFromAllGroups(photoId: Long) {}
}

private class BuildPlanFakeOrganizationPlanRepository : OrganizationPlanRepository {
    var savedPlan: OrganizationPlan? = null
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan {
        val saved = plan.copy(id = 1L)
        savedPlan = saved
        return saved
    }
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = savedPlan
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = flowOf(savedPlan)
    override suspend fun updateOperation(operation: OrganizationOperation) {}
}

class BuildOrganizationPlanUseCaseTest {

    @Test
    fun `builds and persists a plan for a matching category`() = runBlocking {
        val photoRepository = BuildPlanFakePhotoRepository(listOf(testPhoto(1, "Screenshot_1.png")))
        val planRepository = BuildPlanFakeOrganizationPlanRepository()
        val useCase = BuildOrganizationPlanUseCase(photoRepository, BuildPlanFakePhotoGroupRepository(), planRepository)

        val result = useCase("Organize my screenshots", OrganizationCategory.SCREENSHOTS, null, null)

        assertTrue(result is AppResult.Success)
        val plan = (result as AppResult.Success).value
        assertEquals(1L, plan.id)
        assertTrue(plan.operations.isNotEmpty())
        assertEquals(plan, planRepository.savedPlan)
    }

    @Test
    fun `returns a validation failure when nothing matches`() = runBlocking {
        val photoRepository = BuildPlanFakePhotoRepository(listOf(testPhoto(1, "IMG_1.jpg")))
        val useCase = BuildOrganizationPlanUseCase(photoRepository, BuildPlanFakePhotoGroupRepository(), BuildPlanFakeOrganizationPlanRepository())

        val result = useCase("Organize my screenshots", OrganizationCategory.SCREENSHOTS, null, null)

        assertTrue(result is AppResult.Failure)
    }
}
```

`ConfirmOrganizationPlanUseCaseTest.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ConfirmFakeOrganizationPlanRepository(private var plan: OrganizationPlan?) : OrganizationPlanRepository {
    val updated = mutableListOf<OrganizationOperation>()
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan = plan
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = plan
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = flowOf(plan)
    override suspend fun updateOperation(operation: OrganizationOperation) {
        updated += operation
        plan = plan?.copy(operations = plan!!.operations.map { if (it.id == operation.id) operation else it })
    }
}

private class ConfirmFakeAlbumRepository : AlbumRepository {
    var created: Pair<String, List<Long>>? = null
    override suspend fun createAlbum(name: String, photoIds: List<Long>): Long {
        created = name to photoIds
        return 42L
    }
}

private fun testOperation(id: Long, opType: OperationType = OperationType.MOVE, memberPhotoIds: List<Long> = emptyList()) =
    OrganizationOperation(id = id, opType = opType, source = "content://$id", destination = "dest/$id", reason = "r", confidence = 1.0f, memberPhotoIds = memberPhotoIds)

class ConfirmOrganizationPlanUseCaseTest {

    @Test
    fun `rejecting an operation just updates its status, no album is created`() = runBlocking {
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.SCREENSHOTS, createdAtMs = 1L, operations = listOf(testOperation(10)))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val albumRepository = ConfirmFakeAlbumRepository()
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, albumRepository)

        val result = useCase.confirm(1L, listOf(OperationDecision(operationId = 10, status = ReviewStatus.REJECTED)))

        assertTrue(result is AppResult.Success)
        assertEquals(ReviewStatus.REJECTED, planRepository.updated.single().reviewStatus)
        assertEquals(null, albumRepository.created)
    }

    @Test
    fun `approving a CREATE_ALBUM operation creates the album with the confirmed members`() = runBlocking {
        val op = testOperation(20, opType = OperationType.CREATE_ALBUM, memberPhotoIds = listOf(1L, 2L, 3L)).copy(destination = "Goa Trip")
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.TRIP, createdAtMs = 1L, operations = listOf(op))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val albumRepository = ConfirmFakeAlbumRepository()
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, albumRepository)

        val result = useCase.confirm(
            1L,
            listOf(OperationDecision(operationId = 20, status = ReviewStatus.APPROVED, editedMemberPhotoIds = listOf(1L, 3L))),
        )

        assertTrue(result is AppResult.Success)
        assertEquals("Goa Trip" to listOf(1L, 3L), albumRepository.created)
    }

    @Test
    fun `an edited destination is applied before the operation is marked EDITED`() = runBlocking {
        val plan = OrganizationPlan(id = 1L, requestText = "x", category = OrganizationCategory.SCREENSHOTS, createdAtMs = 1L, operations = listOf(testOperation(10)))
        val planRepository = ConfirmFakeOrganizationPlanRepository(plan)
        val useCase = ConfirmOrganizationPlanUseCase(planRepository, ConfirmFakeAlbumRepository())

        useCase.confirm(1L, listOf(OperationDecision(operationId = 10, status = ReviewStatus.EDITED, editedDestination = "new/dest.png")))

        val updated = planRepository.updated.single()
        assertEquals("new/dest.png", updated.destination)
        assertEquals(ReviewStatus.EDITED, updated.reviewStatus)
    }

    @Test
    fun `returns a failure when the plan doesn't exist`() = runBlocking {
        val useCase = ConfirmOrganizationPlanUseCase(ConfirmFakeOrganizationPlanRepository(null), ConfirmFakeAlbumRepository())
        val result = useCase.confirm(999L, emptyList())
        assertTrue(result is AppResult.Failure)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCaseTest" --tests "com.localphotoai.photomanager.domain.organization.ConfirmOrganizationPlanUseCaseTest"`
Expected: FAIL to compile — the use cases don't exist yet.

- [ ] **Step 3: Implement the use cases**

`BuildOrganizationPlanUseCase.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import kotlinx.coroutines.flow.first

class BuildOrganizationPlanUseCase(
    private val photoRepository: PhotoRepository,
    private val photoGroupRepository: PhotoGroupRepository,
    private val organizationPlanRepository: OrganizationPlanRepository,
) {
    suspend operator fun invoke(
        requestText: String,
        category: OrganizationCategory,
        dateHint: String?,
        nameHint: String?,
    ): AppResult<OrganizationPlan> {
        val photos = photoRepository.observePhotos().first()

        val operations = when (category) {
            OrganizationCategory.SCREENSHOTS -> ScreenshotOrganizationStrategy.build(photos)
            OrganizationCategory.BY_DATE -> ByDateOrganizationStrategy.build(photos)
            OrganizationCategory.TRIP -> TripOrganizationStrategy.build(photos, dateHint, nameHint)
            OrganizationCategory.ARCHIVE -> {
                val duplicateGroups = photoGroupRepository.observeDuplicateGroups().first()
                ArchiveOrganizationStrategy.build(photos, duplicateGroups, System.currentTimeMillis())
            }
        }

        if (operations.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No photos matched this organization request."))
        }

        val plan = OrganizationPlan(
            requestText = requestText,
            category = category,
            createdAtMs = System.currentTimeMillis(),
            operations = operations,
        )
        return AppResult.Success(organizationPlanRepository.savePlan(plan))
    }
}
```

`ConfirmOrganizationPlanUseCase.kt`:
```kotlin
package com.localphotoai.photomanager.domain.organization

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult

data class OperationDecision(
    val operationId: Long,
    val status: ReviewStatus,
    val editedDestination: String? = null,
    val editedMemberPhotoIds: List<Long>? = null,
)

class ConfirmOrganizationPlanUseCase(
    private val organizationPlanRepository: OrganizationPlanRepository,
    private val albumRepository: AlbumRepository,
) {
    suspend fun confirm(planId: Long, decisions: List<OperationDecision>): AppResult<OrganizationPlan> {
        val plan = organizationPlanRepository.fetchPlan(planId)
            ?: return AppResult.Failure(AppError.NotFound("No organization plan found with id $planId"))

        val decisionsById = decisions.associateBy { it.operationId }
        for (operation in plan.operations) {
            val decision = decisionsById[operation.id] ?: continue
            val updated = operation.copy(
                reviewStatus = decision.status,
                destination = decision.editedDestination ?: operation.destination,
                memberPhotoIds = decision.editedMemberPhotoIds ?: operation.memberPhotoIds,
            )
            organizationPlanRepository.updateOperation(updated)

            if (updated.opType == OperationType.CREATE_ALBUM &&
                updated.reviewStatus in setOf(ReviewStatus.APPROVED, ReviewStatus.EDITED)
            ) {
                albumRepository.createAlbum(updated.destination, updated.memberPhotoIds)
            }
        }

        val refreshed = organizationPlanRepository.fetchPlan(planId)
            ?: return AppResult.Failure(AppError.NotFound("Plan $planId disappeared during confirmation"))
        return AppResult.Success(refreshed)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCaseTest" --tests "com.localphotoai.photomanager.domain.organization.ConfirmOrganizationPlanUseCaseTest"`
Expected: PASS, 6/6.

- [ ] **Step 5: Wire the Room implementation (manual verification)**

`data/database/.../dao/AlbumDao.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity

@Dao
interface AlbumDao {
    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    suspend fun insertAlbumPhotos(photos: List<AlbumPhotoEntity>)
}
```

`data/database/.../dao/OrganizationDao.kt`:
```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.localphotoai.photomanager.data.database.entity.OrganizationOperationEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizationDao {
    @Insert
    suspend fun insertPlan(plan: OrganizationPlanEntity): Long

    @Insert
    suspend fun insertOperations(operations: List<OrganizationOperationEntity>): List<Long>

    @Query("SELECT * FROM organization_plans WHERE id = :planId")
    suspend fun getPlan(planId: Long): OrganizationPlanEntity?

    @Query("SELECT * FROM organization_operations WHERE planId = :planId")
    suspend fun getOperations(planId: Long): List<OrganizationOperationEntity>

    @Query("SELECT * FROM organization_plans WHERE id = :planId")
    fun observePlan(planId: Long): Flow<OrganizationPlanEntity?>

    @Query("SELECT * FROM organization_operations WHERE planId = :planId")
    fun observeOperations(planId: Long): Flow<List<OrganizationOperationEntity>>

    @Update
    suspend fun updateOperation(operation: OrganizationOperationEntity)

    /** [OrganizationOperation] (the domain model) has no `planId` field — `updateOperation` in
     * [com.localphotoai.photomanager.data.database.OrganizationRepositoryImpl] needs this to
     * reconstruct the entity's required `planId` from just the operation's own id. */
    @Query("SELECT planId FROM organization_operations WHERE id = :operationId")
    suspend fun getPlanIdForOperation(operationId: Long): Long?
}
```

`data/database/.../AlbumRepositoryImpl.kt`:
```kotlin
package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.AlbumDao
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import javax.inject.Inject

class AlbumRepositoryImpl @Inject constructor(
    private val albumDao: AlbumDao,
) : AlbumRepository {
    override suspend fun createAlbum(name: String, photoIds: List<Long>): Long {
        val albumId = albumDao.insertAlbum(AlbumEntity(name = name, createdAtMs = System.currentTimeMillis()))
        albumDao.insertAlbumPhotos(photoIds.map { AlbumPhotoEntity(albumId = albumId, photoId = it) })
        return albumId
    }
}
```

`data/database/.../OrganizationRepositoryImpl.kt`:
```kotlin
package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.OrganizationDao
import com.localphotoai.photomanager.data.database.entity.OrganizationOperationEntity
import com.localphotoai.photomanager.data.database.entity.OrganizationPlanEntity
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.OrganizationPlanRepository
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class OrganizationRepositoryImpl @Inject constructor(
    private val organizationDao: OrganizationDao,
) : OrganizationPlanRepository {

    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan {
        val planId = organizationDao.insertPlan(
            OrganizationPlanEntity(
                requestText = plan.requestText,
                category = plan.category.name,
                createdAtMs = plan.createdAtMs,
                status = "PROPOSED",
            ),
        )
        val operationIds = organizationDao.insertOperations(plan.operations.map { it.toEntity(planId) })
        val savedOperations = plan.operations.zip(operationIds) { op, id -> op.copy(id = id) }
        return plan.copy(id = planId, operations = savedOperations)
    }

    override suspend fun fetchPlan(planId: Long): OrganizationPlan? {
        val planEntity = organizationDao.getPlan(planId) ?: return null
        val operations = organizationDao.getOperations(planId)
        return planEntity.toDomain(operations)
    }

    override fun observePlan(planId: Long): Flow<OrganizationPlan?> =
        combine(organizationDao.observePlan(planId), organizationDao.observeOperations(planId)) { planEntity, operations ->
            planEntity?.toDomain(operations)
        }

    override suspend fun updateOperation(operation: OrganizationOperation) {
        val planId = organizationDao.getPlanIdForOperation(operation.id) ?: return
        organizationDao.updateOperation(operation.toEntity(planId))
    }
}

private fun OrganizationOperation.toEntity(planId: Long) = OrganizationOperationEntity(
    id = id,
    planId = planId,
    opType = opType.name,
    source = source,
    destination = destination,
    reason = reason,
    confidence = confidence,
    memberPhotoIdsCsv = memberPhotoIds.takeIf { it.isNotEmpty() }?.joinToString(","),
    reviewStatus = reviewStatus.name,
    executionResult = executionResult?.let { if (it) "SUCCESS" else "FAILURE" },
    executionError = executionError,
)

private fun OrganizationPlanEntity.toDomain(operations: List<OrganizationOperationEntity>) = OrganizationPlan(
    id = id,
    requestText = requestText,
    category = OperationCategoryOrDefault(category),
    createdAtMs = createdAtMs,
    operations = operations.map { it.toDomain() },
)

private fun OrganizationOperationEntity.toDomain() = OrganizationOperation(
    id = id,
    opType = OperationType.valueOf(opType),
    source = source,
    destination = destination,
    reason = reason,
    confidence = confidence,
    memberPhotoIds = memberPhotoIdsCsv?.split(",")?.filter { it.isNotBlank() }?.map { it.toLong() } ?: emptyList(),
    reviewStatus = ReviewStatus.valueOf(reviewStatus),
    executionResult = executionResult?.let { it == "SUCCESS" },
    executionError = executionError,
)

private fun OperationCategoryOrDefault(name: String) =
    com.localphotoai.photomanager.domain.organization.OrganizationCategory.valueOf(name)
```

Note why `getPlanIdForOperation` exists at all: `OrganizationOperation` (the domain model, Task 2) deliberately has no `planId` field — nothing outside this repository needs it — but `OrganizationOperationEntity` (Room) requires one on every write, so `updateOperation` reconstructs it via that one focused query rather than adding a field to the domain model purely to satisfy the storage layer.

- [ ] **Step 6: Register the new DAOs and repository bindings**

In `AppDatabase.kt`: add `OrganizationDao`/`AlbumDao` abstract functions (`abstract fun organizationDao(): OrganizationDao`, `abstract fun albumDao(): AlbumDao`).

In `DatabaseModule.kt`, add:
```kotlin
    @Provides
    fun provideOrganizationDao(database: AppDatabase): OrganizationDao = database.organizationDao()

    @Provides
    fun provideAlbumDao(database: AppDatabase): AlbumDao = database.albumDao()
```

In `RepositoryModule.kt`, add bindings and a use-case provider:
```kotlin
    @Binds
    @Singleton
    abstract fun bindOrganizationPlanRepository(impl: OrganizationRepositoryImpl): OrganizationPlanRepository

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(impl: AlbumRepositoryImpl): AlbumRepository
```
and, in the companion object:
```kotlin
        @Provides
        fun provideBuildOrganizationPlanUseCase(
            photoRepository: PhotoRepository,
            photoGroupRepository: PhotoGroupRepository,
            organizationPlanRepository: OrganizationPlanRepository,
        ): BuildOrganizationPlanUseCase = BuildOrganizationPlanUseCase(photoRepository, photoGroupRepository, organizationPlanRepository)

        @Provides
        fun provideConfirmOrganizationPlanUseCase(
            organizationPlanRepository: OrganizationPlanRepository,
            albumRepository: AlbumRepository,
        ): ConfirmOrganizationPlanUseCase = ConfirmOrganizationPlanUseCase(organizationPlanRepository, albumRepository)
```
(add the corresponding imports for `OrganizationPlanRepository`, `AlbumRepository`, `BuildOrganizationPlanUseCase`, `ConfirmOrganizationPlanUseCase`, `PhotoRepository` at the top of the file.)

- [ ] **Step 7: Manually verify**

Run: `./gradlew :domain:test :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL, no missing-migration warning (Task 1 already covers this schema).

---

### Task 6: `:fsops` module — `PlanValidator` (pure, TDD)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `fsops/build.gradle.kts`
- Create: `fsops/src/main/AndroidManifest.xml`
- Create: `fsops/src/main/kotlin/com/localphotoai/photomanager/fsops/PlanValidator.kt`
- Test: `fsops/src/test/kotlin/com/localphotoai/photomanager/fsops/PlanValidatorTest.kt`

**Interfaces:**
- Produces: `sealed class ValidationResult { object Valid : ValidationResult(); data class Invalid(val reason: String) : ValidationResult() }`, `class PlanValidator(photoRepository: PhotoRepository) { suspend fun validate(operation: OrganizationOperation, otherDestinationsInPlan: Set<String>): ValidationResult }` — Task 8's `PlanExecutor` calls this before every operation.
- Consumes: `OrganizationOperation`/`OperationType` (Task 2), `PhotoRepository` (pre-existing).

`:fsops` is an Android module (it will need `ContentResolver`/`Context` in Task 8), but `PlanValidator` itself has no Android dependency — it's pure logic living in an Android-library module for convenience of co-location with `PlanExecutor`, the same way `:ml:embeddings` mixes pure and Android-dependent classes.

- [ ] **Step 1: Add the module**

In `settings.gradle.kts`, add `":fsops",` to `include(...)`.

`fsops/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localphotoai.photomanager.fsops"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`fsops/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.localphotoai.photomanager.fsops

import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = 1L, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null, relativePath = "DCIM/Camera/",
)

private fun testOperation(
    opType: OperationType = OperationType.MOVE,
    source: String? = "content://1",
    destination: String = "Pictures/Screenshots/1.jpg",
) = OrganizationOperation(id = 1, opType = opType, source = source, destination = destination, reason = "r", confidence = 1.0f)

private class ValidatorFakePhotoRepository(private val photos: List<Photo>) : PhotoRepository {
    override fun observePhotos(): Flow<List<Photo>> = emptyFlow()
    override fun observeIndexingProgress(): Flow<IndexingProgress> = emptyFlow()
    override suspend fun fetchGeneration(): Long? = null
    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> = emptyList()
    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> = emptyList()
    override suspend fun upsert(photos: List<PhotoMetadata>) {}
    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) {}
    override suspend fun updateIndexingProgress(progress: IndexingProgress) {}
    override suspend fun saveGeneration(generation: Long) {}
    override suspend fun lastSavedGeneration(): Long? = null
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photos.firstOrNull { it.mediaStoreId == mediaStoreId }
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = photos.filter { it.mediaStoreId in mediaStoreIds }
}

class PlanValidatorTest {

    @Test
    fun `a MOVE whose source photo no longer exists is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(emptyList()))
        val result = validator.validate(testOperation(source = "content://1"), emptySet())
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a destination outside the allowed roots is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(testOperation(destination = "../../etc/passwd"), emptySet())
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a destination colliding with another operation in the same plan is invalid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(
            testOperation(destination = "Pictures/Screenshots/1.jpg"),
            setOf("Pictures/Screenshots/1.jpg"),
        )
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `a valid MOVE with an existing source and a clean destination is valid`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(listOf(testPhoto(1))))
        val result = validator.validate(testOperation(), emptySet())
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `CREATE_FOLDER and CREATE_ALBUM need no source photo`() = runBlocking {
        val validator = PlanValidator(ValidatorFakePhotoRepository(emptyList()))
        val folderResult = validator.validate(
            testOperation(opType = OperationType.CREATE_FOLDER, source = null, destination = "Pictures/Archive"),
            emptySet(),
        )
        val albumResult = validator.validate(
            testOperation(opType = OperationType.CREATE_ALBUM, source = null, destination = "My Album"),
            emptySet(),
        )
        assertTrue(folderResult is ValidationResult.Valid)
        assertTrue(albumResult is ValidationResult.Valid)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :fsops:test`
Expected: FAIL to compile — `PlanValidator`/`ValidationResult` don't exist.

- [ ] **Step 4: Implement**

```kotlin
package com.localphotoai.photomanager.fsops

import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import java.io.File
import javax.inject.Inject

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

private val ALLOWED_ROOTS = listOf("Pictures/", "DCIM/")

/**
 * Validates one operation immediately before it runs — re-checked at execution time even though
 * the same checks already informed the review UI, since state can change between review and
 * confirmation (a photo could be deleted, a destination could collide from an unrelated write).
 */
class PlanValidator @Inject constructor(
    private val photoRepository: PhotoRepository,
) {
    suspend fun validate(operation: OrganizationOperation, otherDestinationsInPlan: Set<String>): ValidationResult {
        when (operation.opType) {
            OperationType.MOVE, OperationType.COPY, OperationType.RENAME -> {
                val sourceUri = operation.source
                    ?: return ValidationResult.Invalid("${operation.opType} requires a source photo")
                val photoId = sourceUri.substringAfterLast("/").toLongOrNull()
                    ?: return ValidationResult.Invalid("Malformed source URI: $sourceUri")
                if (photoRepository.fetchById(photoId) == null) {
                    return ValidationResult.Invalid("Source photo $photoId no longer exists")
                }
            }
            OperationType.CREATE_FOLDER, OperationType.CREATE_ALBUM -> {
                // no source photo required
            }
        }

        val canonicalRoot = canonicalizeOrNull(operation.destination)
            ?: return ValidationResult.Invalid("Destination path is not valid: ${operation.destination}")
        if (operation.opType != OperationType.CREATE_ALBUM && ALLOWED_ROOTS.none { canonicalRoot.startsWith(it) }) {
            return ValidationResult.Invalid("Destination must be under Pictures/ or DCIM/: ${operation.destination}")
        }

        if (operation.destination in otherDestinationsInPlan) {
            return ValidationResult.Invalid("Destination collides with another operation in this plan: ${operation.destination}")
        }

        return ValidationResult.Valid
    }

    /** Rejects any `..` segment or absolute-path escape outright — never "sanitizes" a
     * traversal attempt into something else, per ARCHITECTURE.md §19's threat model. */
    private fun canonicalizeOrNull(path: String): String? {
        if (path.split("/").any { it == ".." }) return null
        if (File(path).isAbsolute) return null
        return path
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :fsops:test`
Expected: PASS, 5/5.

- [ ] **Step 6: Commit** (only if the user has asked for commits in this session)

---

### Task 7: `:fsops` — `MediaStoreWriter` and `PlanExecutor` (Android mechanics, manual verification)

**Files:**
- Create: `fsops/src/main/kotlin/com/localphotoai/photomanager/fsops/MediaStoreWriter.kt`
- Create: `fsops/src/main/kotlin/com/localphotoai/photomanager/fsops/PlanExecutor.kt`
- Create: `fsops/src/main/kotlin/com/localphotoai/photomanager/fsops/FsopsModule.kt`

**Interfaces:**
- Produces: `data class OperationExecutionResult(operationId: Long, success: Boolean, error: String? = null)`, `class MediaStoreWriter { fun writeRequestIntentSender(context: Context, uri: Uri): IntentSender }`, `class PlanExecutor(context: Context, photoRepository: PhotoRepository, albumRepository: AlbumRepository, planValidator: PlanValidator) { suspend fun executeFileOperation(operation: OrganizationOperation): OperationExecutionResult; suspend fun executeAlbumOperation(operation: OrganizationOperation): OperationExecutionResult }` — Task 12's `OrganizationReviewScreen`/`OrganizationReviewViewModel` call these.
- Consumes: `PlanValidator` (Task 6), `OrganizationOperation`/`OperationType` (Task 2), `AlbumRepository`/`PhotoRepository` (pre-existing/Task 2).

This mirrors Phase 7's `DuplicatesScreen` delete pattern exactly (one `IntentSender`/`RecoverableSecurityException` per operation, not a batched multi-URI request) — matching this project's proven, already-working precedent rather than introducing a new batched-consent code path.

- [ ] **Step 1: `MediaStoreWriter.kt`**

```kotlin
package com.localphotoai.photomanager.fsops

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import javax.inject.Inject

/**
 * Wraps the one real Android-version-dependent decision this module needs: on API 30+, request
 * write access via [MediaStore.createWriteRequest] up front; below 30, attempt the write
 * directly and only surface an [IntentSender] if a [RecoverableSecurityException] is thrown —
 * exactly mirroring Phase 7's `DuplicatesScreen` delete flow, per operation, not batched.
 */
class MediaStoreWriter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    fun contentResolver(): ContentResolver = context.contentResolver

    fun writeRequestIntentSender(uri: Uri): IntentSender {
        val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
        return request.intentSender
    }

    fun isPreApi30(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun intentSenderFromRecoverableSecurityException(e: RecoverableSecurityException): IntentSender =
        e.userAction.actionIntent.intentSender
}
```

- [ ] **Step 2: `PlanExecutor.kt`**

```kotlin
package com.localphotoai.photomanager.fsops

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import javax.inject.Inject

data class OperationExecutionResult(val operationId: Long, val success: Boolean, val error: String? = null)

/**
 * Performs one confirmed operation. `CREATE_FOLDER` has no independent execution step — Android's
 * scoped storage has no primitive for an empty folder, so a folder only ever exists as a
 * byproduct of the `MOVE`(s) that populate it (the destination path passed to `ContentResolver`
 * for those `MOVE`s is what actually creates the folder). `CREATE_ALBUM` never touches
 * MediaStore/the filesystem at all — see [executeAlbumOperation].
 */
class PlanExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val albumRepository: AlbumRepository,
    private val planValidator: PlanValidator,
) {
    /** Re-validates, then performs a MOVE/COPY/RENAME. Call only after any needed
     * write-consent [android.content.IntentSender] has already succeeded (MOVE/RENAME on
     * API 30+) — see `OrganizationReviewScreen` for that sequencing. */
    suspend fun executeFileOperation(operation: OrganizationOperation): OperationExecutionResult {
        val validation = planValidator.validate(operation, otherDestinationsInPlan = emptySet())
        if (validation is ValidationResult.Invalid) {
            return OperationExecutionResult(operation.id, success = false, error = validation.reason)
        }

        return try {
            when (operation.opType) {
                OperationType.MOVE -> moveOrRename(operation, renameOnly = false)
                OperationType.RENAME -> moveOrRename(operation, renameOnly = true)
                OperationType.COPY -> copy(operation)
                OperationType.CREATE_FOLDER -> OperationExecutionResult(operation.id, success = true)
                OperationType.CREATE_ALBUM -> error("CREATE_ALBUM must go through executeAlbumOperation")
            }
        } catch (e: RecoverableSecurityException) {
            OperationExecutionResult(operation.id, success = false, error = "NEEDS_CONSENT")
        } catch (t: Throwable) {
            OperationExecutionResult(operation.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    /** CREATE_ALBUM only writes to Room — no MediaStore/filesystem interaction. */
    suspend fun executeAlbumOperation(operation: OrganizationOperation): OperationExecutionResult {
        require(operation.opType == OperationType.CREATE_ALBUM)
        return try {
            albumRepository.createAlbum(operation.destination, operation.memberPhotoIds)
            OperationExecutionResult(operation.id, success = true)
        } catch (t: Throwable) {
            OperationExecutionResult(operation.id, success = false, error = t.message ?: "Unknown error")
        }
    }

    private fun moveOrRename(operation: OrganizationOperation, renameOnly: Boolean): OperationExecutionResult {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val destinationPath = operation.destination
        val destinationFolder = destinationPath.substringBeforeLast("/")
        val destinationFilename = destinationPath.substringAfterLast("/")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationFilename)
            if (!renameOnly) put(MediaStore.Images.Media.RELATIVE_PATH, "$destinationFolder/")
        }
        val updated = context.contentResolver.update(uri, values, null, null)
        return OperationExecutionResult(operation.id, success = updated > 0)
    }

    private suspend fun copy(operation: OrganizationOperation): OperationExecutionResult {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        val sourcePhoto = photoRepository.fetchById(photoId)
            ?: return OperationExecutionResult(operation.id, success = false, error = "Source photo not found")
        val sourceUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)

        val destinationFolder = operation.destination.substringBeforeLast("/")
        val destinationFilename = operation.destination.substringAfterLast("/")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, destinationFilename)
            put(MediaStore.Images.Media.MIME_TYPE, sourcePhoto.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "$destinationFolder/")
        }
        val newUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return OperationExecutionResult(operation.id, success = false, error = "Could not create destination entry")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(newUri)?.use { output ->
                input.copyTo(output)
            }
        }
        return OperationExecutionResult(operation.id, success = true)
    }
}
```

- [ ] **Step 3: `FsopsModule.kt`**

```kotlin
package com.localphotoai.photomanager.fsops

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object FsopsModule
```

`PlanValidator`/`MediaStoreWriter`/`PlanExecutor` all use `@Inject constructor` directly (unlike `:tools`/`:llm:orchestration`'s plain-constructor convention) because `:fsops` **does** apply the Hilt plugin (it's an Android module needing `Context`, same category as `:llm:runtime`/`:ml:embeddings`) — this empty `FsopsModule` exists only so the module has at least one `@InstallIn` anchor for Hilt's per-module aggregation; no `@Provides`/`@Binds` are needed since every class here is directly `@Inject`-constructable.

- [ ] **Step 4: Manually verify**

Run: `./gradlew :fsops:assembleDebug`
Expected: BUILD SUCCESSFUL.

---

### Task 8: `:tools` — `BuildOrganizationPlanTool`

**Files:**
- Modify: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/ToolValidator.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/BuildOrganizationPlanTool.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/ToolValidatorTest.kt` (extend)
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/BuildOrganizationPlanToolTest.kt`

**Interfaces:**
- Consumes: `BuildOrganizationPlanUseCase` (Task 5), `OrganizationCategory` (Task 2), `ToolCall`/`ToolName`/`ToolOutcome` (Task 9 extends these — see note below).
- Produces: `ToolValidator.parseOrganizationCategory(value: String?): AppResult<OrganizationCategory>`, `class BuildOrganizationPlanTool(useCase: BuildOrganizationPlanUseCase) : Tool` — Task 11's `RuntimeModule` wires this into the registry.

**Note on task ordering**: this task references `ToolCall.category`/`ToolCall.dateHint`/`ToolCall.nameHint` and `ToolOutcome.Plan`, which Task 9 adds to `:domain`'s `ToolModels.kt`. Do Task 9's `ToolModels.kt` edit *first* (it's a small, non-TDD models change — see Task 9 Step 1) before writing this task's test, exactly the same ordering `:tools`/`:llm:orchestration` already have (models in `:domain`, consumed by both).

- [ ] **Step 1: Add `category`/`dateHint`/`nameHint` to `ToolCall` and `ToolName.BUILD_ORGANIZATION_PLAN`, plus `ToolOutcome.Plan`**

In `domain/.../domain/tool/ToolModels.kt`, add to the `ToolName` enum:
```kotlin
    BUILD_ORGANIZATION_PLAN("build_organization_plan"),
```
add to `ToolCall`:
```kotlin
    val category: String? = null,
    val dateHint: String? = null,
    val nameHint: String? = null,
```
add to `ToolOutcome`:
```kotlin
    data class Plan(val plan: OrganizationPlan, val message: String) : ToolOutcome()
```
(add `import com.localphotoai.photomanager.domain.organization.OrganizationPlan` at the top of the file).

- [ ] **Step 2: Write the failing tests**

Add to `ToolValidatorTest.kt`:
```kotlin
    @Test
    fun `parseOrganizationCategory accepts a known category case-insensitively`() {
        val result = ToolValidator.parseOrganizationCategory("screenshots")
        assertTrue(result is AppResult.Success)
        assertEquals(com.localphotoai.photomanager.domain.organization.OrganizationCategory.SCREENSHOTS, (result as AppResult.Success).value)
    }

    @Test
    fun `parseOrganizationCategory rejects an unknown category`() {
        val result = ToolValidator.parseOrganizationCategory("vacation_photos")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseOrganizationCategory rejects a null category`() {
        val result = ToolValidator.parseOrganizationCategory(null)
        assertTrue(result is AppResult.Failure)
    }
```

`BuildOrganizationPlanToolTest.kt`:
```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.OrganizationCategory
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.OrganizationPlanRepository
import com.localphotoai.photomanager.domain.similarity.DuplicateGroupSummary
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private class ToolFakeOrganizationPlanRepository(private val plan: OrganizationPlan) : OrganizationPlanRepository {
    override suspend fun savePlan(plan: OrganizationPlan): OrganizationPlan = this.plan
    override suspend fun fetchPlan(planId: Long): OrganizationPlan? = this.plan
    override fun observePlan(planId: Long): Flow<OrganizationPlan?> = flowOf(this.plan)
    override suspend fun updateOperation(operation: com.localphotoai.photomanager.domain.organization.OrganizationOperation) {}
}

class BuildOrganizationPlanToolTest {

    @Test
    fun `rejects a missing category`() = runBlocking {
        val tool = BuildOrganizationPlanTool(
            BuildOrganizationPlanUseCase(
                photoRepository = throw NotImplementedError("not reached — validation fails first"),
                photoGroupRepository = throw NotImplementedError("not reached"),
                organizationPlanRepository = throw NotImplementedError("not reached"),
            ),
        )
        val result = tool.execute(ToolCall(tool = ToolName.BUILD_ORGANIZATION_PLAN, category = null))
        assertTrue(result is ToolOutcome.Error)
    }

    @Test
    fun `rejects an unknown category`() = runBlocking {
        val tool = BuildOrganizationPlanTool(
            BuildOrganizationPlanUseCase(
                photoRepository = throw NotImplementedError("not reached — validation fails first"),
                photoGroupRepository = throw NotImplementedError("not reached"),
                organizationPlanRepository = throw NotImplementedError("not reached"),
            ),
        )
        val result = tool.execute(ToolCall(tool = ToolName.BUILD_ORGANIZATION_PLAN, category = "vacation_photos"))
        assertTrue(result is ToolOutcome.Error)
    }
}
```

The two tests above deliberately never construct a real `BuildOrganizationPlanUseCase` dependency graph — `ToolValidator.parseOrganizationCategory` must reject the bad input *before* the use case is ever called, exactly like `GetPhotoMetadataTool`'s `requirePhotoId` check in Phase 8. If either test actually reaches the `throw NotImplementedError(...)`, that's the test correctly catching a validation-ordering bug.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :tools:test --tests "com.localphotoai.photomanager.tools.ToolValidatorTest" --tests "com.localphotoai.photomanager.tools.BuildOrganizationPlanToolTest"`
Expected: FAIL to compile — `parseOrganizationCategory`/`BuildOrganizationPlanTool` don't exist.

- [ ] **Step 4: Implement**

Add to `ToolValidator.kt`:
```kotlin
    fun parseOrganizationCategory(value: String?): AppResult<com.localphotoai.photomanager.domain.organization.OrganizationCategory> {
        if (value.isNullOrBlank()) return AppResult.Failure(AppError.Validation("category is required."))
        return try {
            AppResult.Success(com.localphotoai.photomanager.domain.organization.OrganizationCategory.valueOf(value.uppercase(Locale.US)))
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(AppError.Validation("Unknown organization category \"$value\"."))
        }
    }
```

`BuildOrganizationPlanTool.kt`:
```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class BuildOrganizationPlanTool(
    private val buildOrganizationPlanUseCase: BuildOrganizationPlanUseCase,
) : Tool {
    override val name = ToolName.BUILD_ORGANIZATION_PLAN

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val category = when (val r = ToolValidator.parseOrganizationCategory(call.category)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }

        return when (
            val result = buildOrganizationPlanUseCase(
                requestText = call.toString(),
                category = category,
                dateHint = call.dateHint,
                nameHint = call.nameHint,
            )
        ) {
            is AppResult.Success -> ToolOutcome.Plan(
                result.value,
                "Proposed ${result.value.operations.size} operation(s) — review before anything changes.",
            )
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :tools:test --tests "com.localphotoai.photomanager.tools.ToolValidatorTest" --tests "com.localphotoai.photomanager.tools.BuildOrganizationPlanToolTest"`
Expected: PASS, all cases green.

- [ ] **Step 6: Commit** (only if the user has asked for commits in this session)

---

### Task 9: `:llm:orchestration` — grammar, parser, and loop extended for `build_organization_plan`

**Files:**
- Modify: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/GrammarBuilder.kt`
- Modify: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallParser.kt`
- Modify: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallLoop.kt`
- Modify: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/GrammarBuilderTest.kt`
- Modify: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallParserTest.kt`
- Modify: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallLoopTest.kt`

**Interfaces:**
- Consumes: `ToolCall.category`/`dateHint`/`nameHint`, `ToolOutcome.Plan` (Task 9 Step 1, done as part of Task 8).
- Produces: nothing new for later tasks — this is the last `:llm:*` change; Task 10 wires `BuildOrganizationPlanTool` into the registry.

- [ ] **Step 1: Write the failing tests**

Add to `GrammarBuilderTest.kt`:
```kotlin
    @Test
    fun `grammar references the organization-plan tool id`() {
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.contains("\\\"build_organization_plan\\\""))
    }
```

Add to `ToolCallParserTest.kt`:
```kotlin
    @Test
    fun `parses a valid build_organization_plan call`() {
        val json = """{"tool":"build_organization_plan","params":{"category":"TRIP","dateHint":"2025-03-15","nameHint":"Goa Trip"}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        val call = (result as AppResult.Success).value
        assertEquals(ToolName.BUILD_ORGANIZATION_PLAN, call.tool)
        assertEquals("TRIP", call.category)
        assertEquals("2025-03-15", call.dateHint)
        assertEquals("Goa Trip", call.nameHint)
    }
```

Add to `ToolCallLoopTest.kt` (needs a fake `Plan`-returning tool and `OrganizationPlan`/`OrganizationCategory` fixtures):
```kotlin
    @Test
    fun `a Plan outcome is traced the same way as other outcomes`() = runBlocking {
        val plan = com.localphotoai.photomanager.domain.organization.OrganizationPlan(
            id = 1L, requestText = "x",
            category = com.localphotoai.photomanager.domain.organization.OrganizationCategory.SCREENSHOTS,
            createdAtMs = 1L, operations = emptyList(),
        )
        val engine = ScriptedEngine(listOf("""{"tool":"build_organization_plan","params":{"category":"SCREENSHOTS"}}"""))
        val tool = object : com.localphotoai.photomanager.tools.Tool {
            override val name = ToolName.BUILD_ORGANIZATION_PLAN
            override suspend fun execute(call: ToolCall): ToolOutcome = ToolOutcome.Plan(plan, "1 operation proposed")
        }
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), LoopFakeTraceLogger())

        val outcome = loop.run("organize my screenshots")

        assertTrue(outcome is SearchOutcome.Answered)
        assertTrue((outcome as SearchOutcome.Answered).outcome is ToolOutcome.Plan)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :llm:orchestration:test`
Expected: FAIL — grammar has no `build_organization_plan` alternative, parser doesn't read `category`/`dateHint`/`nameHint`, `ToolCallLoop`'s exhaustive `when` on `ToolOutcome` doesn't yet handle `Plan` (a compile error, not just a failing assertion — Kotlin's `when` over a `sealed class` without an `else` branch fails to compile once a new subtype exists).

- [ ] **Step 3: Implement**

In `GrammarBuilder.kt`, add `build-organization-plan-call` to the `root` alternatives and define it:
```kotlin
        root ::= search-photos-call | find-duplicates-call | find-similar-photos-call | get-photo-metadata-call | get-storage-statistics-call | build-organization-plan-call
```
and, alongside the other `-call` rules:
```kotlin
        build-organization-plan-call ::= "{" ws "\"tool\":" ws "\"build_organization_plan\"" "," ws "\"params\":" ws build-organization-plan-params "}"
        build-organization-plan-params ::= "{" ws "\"category\":" ws ("\"SCREENSHOTS\"" | "\"BY_DATE\"" | "\"TRIP\"" | "\"ARCHIVE\"") (organize-optional-field)* ws "}"
        organize-optional-field ::= "," ws ("\"dateHint\":" ws date-string | "\"nameHint\":" ws string)
```

In `ToolCallParser.kt`, add to the `ToolCall(...)` construction:
```kotlin
                category = params["category"]?.jsonPrimitive?.content,
                dateHint = params["dateHint"]?.jsonPrimitive?.content,
                nameHint = params["nameHint"]?.jsonPrimitive?.content,
```

In `ToolCallLoop.kt`:
- Update `SYSTEM_PROMPT` to mention the sixth tool:
```kotlin
private val SYSTEM_PROMPT = """
    You are a photo search and organization assistant. Given the user's request, respond with
    exactly one JSON tool call matching the grammar. Tools: search_photos (params: people,
    startDate, endDate, location, sortBy), find_duplicates (no params), find_similar_photos (no
    params), get_photo_metadata (params: photoId), get_storage_statistics (no params),
    build_organization_plan (params: category [SCREENSHOTS|BY_DATE|TRIP|ARCHIVE], dateHint,
    nameHint) for requests like "organize my photos," "organize my screenshots," "put photos
    from my trip into an album," or "find photos that should be archived."
""".trimIndent()
```
- Add `is ToolOutcome.Plan -> 1` to the `resultCount` `when` (a plan counts as "1 result" — a plan, not a photo — for trace-log purposes) and `is ToolOutcome.Plan -> outcome.message` to the `message` `when`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :llm:orchestration:test`
Expected: PASS, all cases green (2 new grammar/parser cases + 1 new loop case + no regressions in the existing 12).

- [ ] **Step 5: Commit** (only if the user has asked for commits in this session)

---

### Task 10: DI wiring — `RuntimeModule` gains the organization tool and use cases

**Files:**
- Modify: `llm/runtime/build.gradle.kts`
- Modify: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/RuntimeModule.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `BuildOrganizationPlanTool` (Task 8), `BuildOrganizationPlanUseCase` (Task 5, provided by `:data:database`'s `RepositoryModule`).

- [ ] **Step 1: `llm:runtime`'s dependencies are already sufficient** — it already depends on `:tools` and `:domain` transitively (Phase 8), and `BuildOrganizationPlanTool` lives in `:tools` (already a dependency) using `BuildOrganizationPlanUseCase` from `:domain` (already a dependency, via `:tools`' own `api(project(":domain"))`). No new `implementation(...)` line is needed here.

- [ ] **Step 2: Add the provider and extend the registry**

In `RuntimeModule.kt`, add:
```kotlin
        @Provides
        fun provideBuildOrganizationPlanTool(useCase: BuildOrganizationPlanUseCase): BuildOrganizationPlanTool =
            BuildOrganizationPlanTool(useCase)
```
and update `provideToolRegistry` to take and include it:
```kotlin
        @Provides
        @Singleton
        fun provideToolRegistry(
            searchPhotosTool: SearchPhotosTool,
            findDuplicatesTool: FindDuplicatesTool,
            findSimilarPhotosTool: FindSimilarPhotosTool,
            getPhotoMetadataTool: GetPhotoMetadataTool,
            getStorageStatisticsTool: GetStorageStatisticsTool,
            buildOrganizationPlanTool: BuildOrganizationPlanTool,
        ): ToolRegistry = ToolRegistry(
            listOf(
                searchPhotosTool, findDuplicatesTool, findSimilarPhotosTool,
                getPhotoMetadataTool, getStorageStatisticsTool, buildOrganizationPlanTool,
            ),
        )
```
Add the two imports: `com.localphotoai.photomanager.domain.organization.BuildOrganizationPlanUseCase`, `com.localphotoai.photomanager.tools.BuildOrganizationPlanTool`.

- [ ] **Step 3: Manually verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — Hilt resolves `BuildOrganizationPlanUseCase` from `:data:database`'s `RepositoryModule` (Task 5) across the module graph, the same transitive-Hilt-aggregation pattern Phase 8 already relies on.

---

### Task 11: `:app`/`:feature:search` — depend on `:fsops`

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `feature/search/build.gradle.kts`

- [ ] **Step 1: Add the dependency to both**

In `app/build.gradle.kts`, add `implementation(project(":fsops"))` alongside the existing `implementation(project(":llm:runtime"))` line — needed so Hilt's aggregation sees `FsopsModule` and so `PlanExecutor`/`MediaStoreWriter` are on `:app`'s classpath, matching the reasoning already documented for `:llm:runtime` in Phase 8.

In `feature/search/build.gradle.kts`, add `implementation(project(":fsops"))` to the `dependencies` block — `OrganizationReviewViewModel` (Task 12) constructs/injects `PlanExecutor`/`MediaStoreWriter` directly.

- [ ] **Step 2: Manually verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

---

### Task 12: `OrganizationReviewScreen` + `OrganizationReviewViewModel`, wired into Search

**Files:**
- Create: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/OrganizationReviewViewModel.kt`
- Create: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/OrganizationReviewScreen.kt`
- Modify: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchScreen.kt`

**Interfaces:**
- Consumes: `OrganizationPlan`/`OrganizationOperation`/`OperationType`/`ReviewStatus` (Task 2), `ConfirmOrganizationPlanUseCase`/`OperationDecision` (Task 5), `PlanExecutor`/`MediaStoreWriter`/`OperationExecutionResult` (Task 7), `NlSearchUiState` (pre-existing, Phase 8, in `SearchViewModel.kt`).

No TDD steps — Compose UI and ViewModel wiring, verified manually on-device (Task 13), per this project's standing preference.

**Scope note vs. the design spec's §6**: the spec describes validation running informationally at plan-build time (shown as a per-operation warning in the review UI) *and* mandatorily before execution. This task only wires the mandatory pre-execution check (via `PlanExecutor.executeFileOperation`'s internal `planValidator.validate(...)` call) — the review screen itself does not call `PlanValidator` to show warnings before the user approves. Add that if time allows; otherwise record it as a known limitation in Task 13 Step 4 (already listed there).

**Write-consent sequencing matters here**: Phase 7's `DuplicatesScreen` requests write consent (`createDeleteRequest`) *proactively* on API 30+ — before attempting the write, not reactively by catching an exception — and only falls back to catching `RecoverableSecurityException` below API 30. `executeFileOperation` (Task 7) does attempt the write directly and can only report `NEEDS_CONSENT` after the fact, which does not by itself get a consent dialog on screen. The Composable in Step 3 below drives this correctly: it walks approved MOVE/RENAME operations **one at a time**, requesting consent proactively via `MediaStoreWriter.writeRequestIntentSender` on API 30+ before calling execute for that operation, and simply calling execute directly below API 30 (relying on `executeFileOperation`'s existing `RecoverableSecurityException` catch as the fallback path there). This one-at-a-time sequencing mirrors `DuplicatesScreen`'s `it.forEach(::deletePhoto)` — one `IntentSender` per operation, not a single batched request.

- [ ] **Step 1: Extend `NlSearchUiState` and `SearchViewModel` to surface a plan**

In `SearchViewModel.kt`, add a `Plan` case to `NlSearchUiState`:
```kotlin
    data class Plan(val plan: com.localphotoai.photomanager.domain.organization.OrganizationPlan, val message: String) : NlSearchUiState()
```
and add `is ToolOutcome.Plan -> NlSearchUiState.Plan(result.plan, result.message)` to `onNlQuerySubmitted`'s `when` over `outcome.outcome`.

- [ ] **Step 2: `OrganizationReviewViewModel.kt`**

```kotlin
package com.localphotoai.photomanager.feature.search

import android.content.ContentUris
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.organization.ConfirmOrganizationPlanUseCase
import com.localphotoai.photomanager.domain.organization.OperationDecision
import com.localphotoai.photomanager.domain.organization.OperationType
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import com.localphotoai.photomanager.fsops.MediaStoreWriter
import com.localphotoai.photomanager.fsops.OperationExecutionResult
import com.localphotoai.photomanager.fsops.PlanExecutor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewOperationState(
    val operation: OrganizationOperation,
    val status: ReviewStatus = ReviewStatus.PENDING,
    val editedDestination: String? = null,
    val excludedMemberIds: Set<Long> = emptySet(),
)

sealed class ExecutionUiState {
    object NotStarted : ExecutionUiState()
    object Running : ExecutionUiState()
    /** API 30+ only — see [MediaStoreWriter]. The Composable launches [intentSender] via
     * `rememberLauncherForActivityResult` and reports the result back via [onConsentResult]. */
    data class AwaitingConsent(val operation: OrganizationOperation, val intentSender: IntentSender) : ExecutionUiState()
    data class Done(val results: List<OperationExecutionResult>) : ExecutionUiState()
}

@HiltViewModel
class OrganizationReviewViewModel @Inject constructor(
    private val confirmOrganizationPlanUseCase: ConfirmOrganizationPlanUseCase,
    private val planExecutor: PlanExecutor,
    private val mediaStoreWriter: MediaStoreWriter,
) : ViewModel() {

    private val operationStates = MutableStateFlow<List<ReviewOperationState>>(emptyList())
    val operations: StateFlow<List<ReviewOperationState>> = operationStates.asStateFlow()

    private val executionState = MutableStateFlow<ExecutionUiState>(ExecutionUiState.NotStarted)
    val execution: StateFlow<ExecutionUiState> = executionState.asStateFlow()

    private var planId: Long = 0
    private val pendingQueue = ArrayDeque<OrganizationOperation>()
    private val collectedResults = mutableListOf<OperationExecutionResult>()

    fun loadPlan(plan: OrganizationPlan) {
        planId = plan.id
        operationStates.value = plan.operations.map { ReviewOperationState(it) }
    }

    fun onApproveAll() = operationStates.update { list -> list.map { it.copy(status = ReviewStatus.APPROVED) } }

    fun onRejectAll() = operationStates.update { list -> list.map { it.copy(status = ReviewStatus.REJECTED) } }

    fun onOperationToggled(operationId: Long, approved: Boolean) = operationStates.update { list ->
        list.map { if (it.operation.id == operationId) it.copy(status = if (approved) ReviewStatus.APPROVED else ReviewStatus.REJECTED) else it }
    }

    fun onDestinationEdited(operationId: Long, newDestination: String) = operationStates.update { list ->
        list.map { if (it.operation.id == operationId) it.copy(status = ReviewStatus.EDITED, editedDestination = newDestination) else it }
    }

    fun onMemberToggled(operationId: Long, photoId: Long, included: Boolean) = operationStates.update { list ->
        list.map { state ->
            if (state.operation.id != operationId) return@map state
            val excluded = if (included) state.excludedMemberIds - photoId else state.excludedMemberIds + photoId
            state.copy(excludedMemberIds = excluded)
        }
    }

    fun onExecuteConfirmed() {
        executionState.value = ExecutionUiState.Running
        viewModelScope.launch {
            val decisions = operationStates.value
                .filter { it.status == ReviewStatus.APPROVED || it.status == ReviewStatus.EDITED }
                .map { state ->
                    OperationDecision(
                        operationId = state.operation.id,
                        status = state.status,
                        editedDestination = state.editedDestination,
                        editedMemberPhotoIds = if (state.excludedMemberIds.isEmpty()) {
                            null
                        } else {
                            state.operation.memberPhotoIds - state.excludedMemberIds
                        },
                    )
                }

            when (val confirmed = confirmOrganizationPlanUseCase.confirm(planId, decisions)) {
                is AppResult.Success -> {
                    pendingQueue.clear()
                    pendingQueue.addAll(
                        confirmed.value.operations.filter {
                            it.reviewStatus == ReviewStatus.APPROVED || it.reviewStatus == ReviewStatus.EDITED
                        },
                    )
                    collectedResults.clear()
                    processNext()
                }
                is AppResult.Failure -> executionState.value = ExecutionUiState.Done(emptyList())
            }
        }
    }

    /** Called by the Composable once the user has responded to a write-consent dialog launched
     * for [ExecutionUiState.AwaitingConsent]. */
    fun onConsentResult(operation: OrganizationOperation, granted: Boolean) {
        viewModelScope.launch {
            collectedResults += if (granted) {
                planExecutor.executeFileOperation(operation)
            } else {
                OperationExecutionResult(operation.id, success = false, error = "Write consent denied")
            }
            processNext()
        }
    }

    /** Walks the approved-operation queue one at a time — never all at once — so a `MOVE`/
     * `RENAME` needing write consent (API 30+) can pause for exactly one [IntentSender] before
     * the next operation starts, mirroring `DuplicatesScreen`'s per-photo `createDeleteRequest`
     * pattern (Phase 7) rather than introducing a new batched-consent code path. */
    private fun processNext() {
        val operation = pendingQueue.removeFirstOrNull()
        if (operation == null) {
            executionState.value = ExecutionUiState.Done(collectedResults.toList())
            return
        }
        when {
            operation.opType == OperationType.CREATE_ALBUM -> {
                // ConfirmOrganizationPlanUseCase already created the album (Task 5) — record a
                // synthetic success rather than re-creating it here.
                collectedResults += OperationExecutionResult(operation.id, success = true)
                processNext()
            }
            operation.opType in setOf(OperationType.MOVE, OperationType.RENAME) && !mediaStoreWriter.isPreApi30() -> {
                val uri = uriForOperation(operation)
                executionState.value = ExecutionUiState.AwaitingConsent(operation, mediaStoreWriter.writeRequestIntentSender(uri))
            }
            else -> viewModelScope.launch {
                collectedResults += planExecutor.executeFileOperation(operation)
                processNext()
            }
        }
    }

    private fun uriForOperation(operation: OrganizationOperation): Uri {
        val photoId = requireNotNull(operation.source).substringAfterLast("/").toLong()
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
    }
}
```

- [ ] **Step 3: `OrganizationReviewScreen.kt`**

```kotlin
package com.localphotoai.photomanager.feature.search

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.organization.OrganizationOperation
import com.localphotoai.photomanager.domain.organization.OrganizationPlan
import com.localphotoai.photomanager.domain.organization.ReviewStatus
import com.localphotoai.photomanager.fsops.OperationExecutionResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizationReviewScreen(
    plan: OrganizationPlan,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrganizationReviewViewModel = hiltViewModel(),
) {
    LaunchedEffect(plan.id) { viewModel.loadPlan(plan) }

    val operations by viewModel.operations.collectAsState()
    val execution by viewModel.execution.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val awaiting = execution as? ExecutionUiState.AwaitingConsent ?: return@rememberLauncherForActivityResult
        viewModel.onConsentResult(awaiting.operation, result.resultCode == Activity.RESULT_OK)
    }

    // One IntentSender launch per AwaitingConsent state — the ViewModel advances to the next
    // operation (or a new AwaitingConsent) only after onConsentResult runs, so this never
    // launches more than one dialog at a time.
    LaunchedEffect(execution) {
        val awaiting = execution as? ExecutionUiState.AwaitingConsent ?: return@LaunchedEffect
        consentLauncher.launch(IntentSenderRequest.Builder(awaiting.intentSender).build())
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Review Organization Plan") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val state = execution) {
                is ExecutionUiState.Done -> ExecutionSummary(state.results, onBack)
                is ExecutionUiState.Running -> Text("Executing…", modifier = Modifier.padding(16.dp))
                is ExecutionUiState.AwaitingConsent -> Text("Waiting for permission…", modifier = Modifier.padding(16.dp))
                is ExecutionUiState.NotStarted -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::onApproveAll) { Text("Approve all") }
                        OutlinedButton(onClick = viewModel::onRejectAll) { Text("Reject all") }
                    }
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(operations, key = { it.operation.id }) { state ->
                            OperationRow(
                                state = state,
                                onToggled = { approved -> viewModel.onOperationToggled(state.operation.id, approved) },
                                onDestinationEdited = { viewModel.onDestinationEdited(state.operation.id, it) },
                                onMemberToggled = { photoId, included -> viewModel.onMemberToggled(state.operation.id, photoId, included) },
                            )
                        }
                    }
                    val anyApproved = operations.any { it.status == ReviewStatus.APPROVED || it.status == ReviewStatus.EDITED }
                    Button(
                        enabled = anyApproved,
                        onClick = viewModel::onExecuteConfirmed,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Text("Execute")
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(
    state: ReviewOperationState,
    onToggled: (Boolean) -> Unit,
    onDestinationEdited: (String) -> Unit,
    onMemberToggled: (Long, Boolean) -> Unit,
) {
    var destinationText by remember(state.operation.id) { mutableStateOf(state.editedDestination ?: state.operation.destination) }

    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = state.status == ReviewStatus.APPROVED || state.status == ReviewStatus.EDITED,
                    onCheckedChange = onToggled,
                )
                Column {
                    Text("${state.operation.opType}: ${state.operation.reason}", style = MaterialTheme.typography.bodyMedium)
                    state.operation.confidence?.let {
                        Text("Confidence: ${(it * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            OutlinedTextField(
                value = destinationText,
                onValueChange = {
                    destinationText = it
                    onDestinationEdited(it)
                },
                label = { Text(if (state.operation.opType.name == "CREATE_ALBUM") "Album name" else "Destination") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (state.operation.memberPhotoIds.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("${state.operation.memberPhotoIds.size - state.excludedMemberIds.size} of ${state.operation.memberPhotoIds.size} photos included")
                }
                for (photoId in state.operation.memberPhotoIds) {
                    Row {
                        Checkbox(
                            checked = photoId !in state.excludedMemberIds,
                            onCheckedChange = { included -> onMemberToggled(photoId, included) },
                        )
                        Text("Photo $photoId")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionSummary(results: List<OperationExecutionResult>, onBack: () -> Unit) {
    val succeeded = results.count { it.success }
    val failed = results.size - succeeded
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("$succeeded succeeded, $failed failed", style = MaterialTheme.typography.titleMedium)
        for (result in results.filter { !it.success }) {
            Text("Operation ${result.operationId}: ${result.error}", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
    }
}
```

- [ ] **Step 4: Swap to the review screen from `SearchScreen.kt`**

In `NlSearchResultSection`'s `when (state)`, add:
```kotlin
        is NlSearchUiState.Plan -> {
            var showReview by remember(state.plan.id) { mutableStateOf(true) }
            if (showReview) {
                OrganizationReviewScreen(plan = state.plan, onBack = { showReview = false })
            } else {
                Text(state.message, modifier = Modifier.padding(8.dp))
            }
        }
```
(matching the existing `showDuplicates`-style local-state screen swap already used in `PhotosScreen.kt` — no new NavHost route).

- [ ] **Step 5: Manually verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

---

### Task 13: End-to-end on-device verification

**No new files — verification only, per this project's phase-gate convention.**

- [ ] **Step 1: Full regression + build**

Run: `./gradlew :domain:test :core:common:test :tools:test :llm:orchestration:test :fsops:test`
Expected: PASS, zero regressions in the pre-existing 118 tests plus every new test from Tasks 3–9 (5 `TripClustererTest` + 17 across the four strategy tests + 6 use-case tests + 5 `PlanValidatorTest` + 3 `ToolValidator`/`BuildOrganizationPlanTool` tests + 3 grammar/parser/loop tests ≈ 39 new).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install and exercise on the project's standard AVD**

Install to the same Android 15 (API 35) AVD used by every prior phase (with the model already downloaded from Phase 8's verification, if still present). Through the Search screen's NL box, try:
- "Organize my screenshots" (needs at least one photo with "Screenshot" in its filename in the test library).
- "Put photos from my trip into an album" (needs at least `TripClusterer.MIN_PHOTOS` GPS-tagged photos within the distance/time thresholds — push synthetic GPS-tagged test photos if the real library has none, the same technique Phase 6's performance test used).
- "Find photos that should be archived".

For each: confirm the review screen shows the proposed operations with correct source/destination/reason/confidence, confirm "Reject all" then re-open leaves nothing executed, confirm approving one operation and tapping "Execute" performs only that operation (verify via `adb shell content query` against the MediaStore URI that the file actually moved, or via a direct SQLite check for a `CREATE_ALBUM` case) and reports "1 succeeded, 0 failed" — never a blanket claim if something was rejected.

- [ ] **Step 3: Verify validation rejects a bad plan without crashing**

Manually construct (via direct SQLite insert against the pulled on-device database, the same technique Phase 6/7 used for synthetic test data) an `organization_operations` row with a path-traversal destination (e.g. `"../../etc/evil"`) or an out-of-range `opType`, confirm `PlanValidator` rejects it at execution time with a clear per-operation error rather than crashing the app or silently succeeding.

- [ ] **Step 4: Write up the Known Limitations section**

Update the master plan (`docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md`)'s Phase 9 entry with: Status → Done, a "What was built" summary mirroring Phases 1-8's format, the verification performed (Steps 1-3 above with real observed behavior, not guessed), and a "Known limitations" section covering at minimum: `TripClusterer`'s untuned distance/time thresholds (same honest treatment as every prior phase's heuristics), `ArchiveOrganizationStrategy.SCREENSHOT_AGE_MS`'s untuned value, the per-operation (not batched) write-consent flow's UX cost for a plan with many MOVE operations on API 30+ (a documented, deliberate consistency choice with Phase 7's existing pattern, not an oversight), validation warnings not being surfaced in the review UI before execution (only enforced mandatorily at execution time, per Task 12's scope note), and no undo/operation-history beyond per-operation success/failure (Phase 10's job). Do not commit this update automatically — ask first, per this project's standing instruction.
