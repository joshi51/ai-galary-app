# Phase 6 — Search by People Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Deviation from the standard writing-plans template:** this project has a standing "basic-level testing only, scoped to business logic" preference (see Global Constraints). Only pure-Kotlin domain logic (filter validation, bounding-box math) gets full TDD red/green/commit steps. Room DAOs, repository impls, migrations, ViewModels, and Compose UI are NOT unit tested — consistent with every prior phase (2–5) in this project, which verified that layer manually (on-device SQL checks, screenshots) rather than adding Robolectric/instrumented-test infrastructure. Task 7's performance verification is a manual on-device procedure with exact commands, mirroring Phases 4/5's synthetic-data benchmarking approach.

**Goal:** Deterministic (non-LLM) search for photos by the people in them — single person, multiple people (AND), person+date, person+location — with pagination and indexes that stay responsive on a large library.

**Architecture:** New read-only query surface (`SearchDao` in `:data:database`) backed by a single parameterized Room `@Query` using the SQL optional-filter pattern, exposed through `:domain`'s `SearchRepository`/`SearchPhotosUseCase`, wired to a new `:feature:search` screen (person picker + year quick-filter + location toggle) via Jetpack Paging 3. Three new indexes on `PhotoEntity` back the date/location filters.

**Tech Stack:** Kotlin, Room 2.8.2 (+ `room-paging`), Jetpack Paging 3, Jetpack Compose, Hilt.

**Spec:** [docs/superpowers/specs/2026-08-30-phase6-people-search-design.md](../specs/2026-08-30-phase6-people-search-design.md)

## Global Constraints

- No paid/mandatory cloud AI APIs; this phase has no AI/ML component at all — pure deterministic SQL.
- Basic-level testing only, scoped to business logic: unit-test `PhotoSearchFilter`/`SearchPhotosUseCase` validation and `LocationBoundingBoxCalculator` math in `:domain`. No tests for `SearchDao`, `SearchRepositoryImpl`, `SearchViewModel`, or Compose UI — verify those manually per-task and in Task 7's on-device pass.
- Never commit to git unless explicitly asked in the current request — every task below ends with a `git add`/`git commit` step; if the calling context says not to commit, skip that step and leave changes staged/unstaged instead.
- Multi-person search is AND (intersection) semantics — a photo must contain every selected person.
- Location filtering is GPS-bounding-box only (existing nullable `latitude`/`longitude` columns) — no folder/album filtering in this phase.
- Build incrementally: each task should compile (`./gradlew :app:assembleDebug`) before moving to the next.

---

### Task 1: Add Paging 3 dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `domain/build.gradle.kts`
- Modify: `data/database/build.gradle.kts`
- Modify: `feature/search/build.gradle.kts`

**Interfaces:**
- Produces: `libs.androidx.paging.common`, `libs.androidx.paging.compose`, `libs.androidx.room.paging` version-catalog entries, available to every later task in this plan.

- [ ] **Step 1: Add version-catalog entries**

In `gradle/libs.versions.toml`, add to `[versions]` (after the existing `room = "2.8.2"` line):

```toml
paging = "3.4.2"
```

Add to `[libraries]` (after the existing `androidx-room-testing` line):

```toml
androidx-room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
androidx-paging-common = { group = "androidx.paging", name = "paging-common", version.ref = "paging" }
androidx-paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
```

- [ ] **Step 2: Verify the pinned Paging version is still current**

Check https://developer.android.com/jetpack/androidx/releases/paging (or `mvnrepository.com/artifact/androidx.paging/paging-common`) for the latest stable (non-alpha/beta/rc) release as of today. If a newer stable version exists, update `paging = "3.4.2"` to that version before proceeding — this project's convention (see `ARCHITECTURE.md` §22) is to re-check toolchain versions at implementation time rather than trust a value written earlier.

- [ ] **Step 3: Add `paging-common` to `:domain`**

In `domain/build.gradle.kts`, add inside the existing `dependencies { }` block, after `implementation(libs.kotlinx.coroutines.core)`:

```kotlin
    implementation(libs.androidx.paging.common)
```

- [ ] **Step 4: Add `room-paging` to `:data:database`**

In `data/database/build.gradle.kts`, add inside `dependencies { }`, after `ksp(libs.androidx.room.compiler)`:

```kotlin
    implementation(libs.androidx.room.paging)
```

- [ ] **Step 5: Add `:domain`, `paging-compose`, and lifecycle/hilt deps to `:feature:search`**

Replace the entire `dependencies { }` block in `feature/search/build.gradle.kts` (it currently only has UI/Compose deps) with:

```kotlin
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":domain"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.androidx.paging.common)
    implementation(libs.androidx.paging.compose)

    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
```

Also update the `plugins { }` block at the top of `feature/search/build.gradle.kts` (currently missing `ksp`/`hilt`, which the block above now needs) to match `feature/people/build.gradle.kts`'s plugins block exactly:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
```

- [ ] **Step 6: Verify the project still syncs/compiles**

Run: `./gradlew :domain:compileKotlin :data:database:compileDebugKotlin :feature:search:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `:domain:compileKotlin` fails because `paging-common` can't resolve a plain-JVM variant, stop and report the exact error before continuing — this would mean Paging 3's Kotlin Multiplatform support doesn't cover `:domain`'s `kotlin.jvm`-only module in the pinned version, and the domain interface in Task 3 would need to move its `PagingData` import to a `commonMain`/multiplatform-aware source or `:data:database` instead.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml domain/build.gradle.kts data/database/build.gradle.kts feature/search/build.gradle.kts
git commit -m "build: add Jetpack Paging 3 dependencies for Phase 6 search"
```

---

### Task 2: Add search indexes and Room migration 4→5

**Files:**
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`

**Interfaces:**
- Produces: `photos` table now has indexes `index_photos_dateTakenMs` and `index_photos_latitude_longitude`, which Task 4's `SearchDao` query relies on for performance (verified in Task 7).

Note: the approved spec (§3) assumed `FaceEntity` also needed a new `photoId` index, but it already has `indices = [Index("photoId")]` (added in Phase 3) — no change needed there. Only `PhotoEntity` needs new indexes.

- [ ] **Step 1: Add indexes to `PhotoEntity`**

In `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt`, change:

```kotlin
import androidx.room.Entity
import androidx.room.PrimaryKey
```

to:

```kotlin
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
```

and change:

```kotlin
@Entity(tableName = "photos")
data class PhotoEntity(
```

to:

```kotlin
@Entity(
    tableName = "photos",
    indices = [
        Index("dateTakenMs"),
        Index(value = ["latitude", "longitude"]),
    ],
)
data class PhotoEntity(
```

- [ ] **Step 2: Bump the database version and add `MIGRATION_4_5`**

In `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`, change:

```kotlin
    version = 4,
```

to:

```kotlin
    version = 5,
```

Then append this migration at the end of the file, after `MIGRATION_3_4`:

```kotlin

/** Phase 6: adds search indexes on `photos` for date and location filtering. No data changes. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_dateTakenMs ON photos(dateTakenMs)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_photos_latitude_longitude ON photos(latitude, longitude)",
        )
    }
}
```

- [ ] **Step 3: Register the migration**

In `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`, change:

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
```

to:

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manually verify the migration on an existing on-device database**

Install the app on the Phase 5 test device/emulator (which already has a v4 database from prior phases). Launch the app and open the Photos tab (triggers a DB open/read). Confirm via `adb logcat` that no `IllegalStateException`/migration-mismatch crash occurs. Then confirm the indexes actually exist:

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db ".indexes photos"
```

Expected output includes `index_photos_dateTakenMs` and `index_photos_latitude_longitude`.

- [ ] **Step 6: Commit**

```bash
git add data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/entity/PhotoEntity.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt
git commit -m "feat(db): add photos date/location indexes, migration 4->5"
```

---

### Task 3: Domain layer — search filter, bounding-box math, use case

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/PhotoSearchFilter.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/LocationBoundingBoxCalculator.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/SearchRepository.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/SearchPhotosUseCase.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/search/LocationBoundingBoxCalculatorTest.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/search/SearchPhotosUseCaseTest.kt`

**Interfaces:**
- Consumes: `com.localphotoai.photomanager.core.common.AppResult`, `AppError` (existing, `:core:common`); `com.localphotoai.photomanager.domain.photo.Photo` (existing).
- Produces: `PhotoSearchFilter(personIds: Set<Long>, startDateMs: Long?, endDateMs: Long?, locationBoundingBox: BoundingBox?)`, `BoundingBox(minLatitude, maxLatitude, minLongitude, maxLongitude: Double)`, `LocationBoundingBoxCalculator.fromPointAndRadiusKm(latitude, longitude, radiusKm): BoundingBox`, `SearchRepository.observeSearchResults(filter): Flow<PagingData<Photo>>`, `SearchPhotosUseCase.invoke(filter): AppResult<Flow<PagingData<Photo>>>` — all consumed by Task 4 (`SearchRepositoryImpl`) and Task 6 (`SearchViewModel`).

- [ ] **Step 1: Write the failing tests**

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/search/LocationBoundingBoxCalculatorTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationBoundingBoxCalculatorTest {

    @Test
    fun `zero radius produces a box centered exactly on the point`() {
        val box = LocationBoundingBoxCalculator.fromPointAndRadiusKm(
            latitude = 12.0,
            longitude = 77.0,
            radiusKm = 0.0,
        )
        assertTrue(abs(box.minLatitude - 12.0) < 1e-9)
        assertTrue(abs(box.maxLatitude - 12.0) < 1e-9)
        assertTrue(abs(box.minLongitude - 77.0) < 1e-9)
        assertTrue(abs(box.maxLongitude - 77.0) < 1e-9)
    }

    @Test
    fun `larger radius produces a larger box`() {
        val small = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 1.0)
        val large = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 10.0)
        val smallSpan = small.maxLatitude - small.minLatitude
        val largeSpan = large.maxLatitude - large.minLatitude
        assertTrue(largeSpan > smallSpan)
    }

    @Test
    fun `longitude span widens the same latitude span shrinks away from the equator`() {
        // At the same radius, longitude degrees-per-km shrinks as |latitude| grows
        // (meridians converge toward the poles), so the box's longitude span should
        // be wider near the equator than at a high latitude for the same radius.
        val equator = LocationBoundingBoxCalculator.fromPointAndRadiusKm(0.0, 0.0, radiusKm = 50.0)
        val highLatitude = LocationBoundingBoxCalculator.fromPointAndRadiusKm(60.0, 0.0, radiusKm = 50.0)
        val equatorLonSpan = equator.maxLongitude - equator.minLongitude
        val highLatLonSpan = highLatitude.maxLongitude - highLatitude.minLongitude
        assertTrue(highLatLonSpan > equatorLonSpan)
    }
}
```

Create `domain/src/test/kotlin/com/localphotoai/photomanager/domain/search/SearchPhotosUseCaseTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSearchRepository : SearchRepository {
    var lastFilter: PhotoSearchFilter? = null
    var callCount = 0

    override fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>> {
        lastFilter = filter
        callCount++
        return flowOf(PagingData.empty())
    }
}

class SearchPhotosUseCaseTest {

    @Test
    fun `rejects a filter with no selected people`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(PhotoSearchFilter(personIds = emptySet()))

        assertTrue(result is AppResult.Failure)
        assertEquals(0, repository.callCount)
    }

    @Test
    fun `rejects a date range where start is after end`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(
            PhotoSearchFilter(personIds = setOf(1L), startDateMs = 2_000L, endDateMs = 1_000L),
        )

        assertTrue(result is AppResult.Failure)
        assertEquals(0, repository.callCount)
    }

    @Test
    fun `delegates a valid filter to the repository unchanged`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)
        val filter = PhotoSearchFilter(personIds = setOf(1L, 2L), startDateMs = 1_000L, endDateMs = 2_000L)

        val result = useCase(filter)

        assertTrue(result is AppResult.Success)
        assertEquals(filter, repository.lastFilter)
        assertEquals(1, repository.callCount)
    }

    @Test
    fun `accepts a filter with only a start date and no end date`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(PhotoSearchFilter(personIds = setOf(1L), startDateMs = 1_000L))

        assertTrue(result is AppResult.Success)
        assertEquals(1, repository.callCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.search.*"`
Expected: FAIL — `PhotoSearchFilter`, `BoundingBox`, `LocationBoundingBoxCalculator`, `SearchRepository`, `SearchPhotosUseCase` are unresolved references (none of these types exist yet).

- [ ] **Step 3: Create `PhotoSearchFilter.kt`**

```kotlin
package com.localphotoai.photomanager.domain.search

/**
 * A deterministic (non-LLM) photo search request. At least one person must be selected —
 * this phase is people-search, not a generic browse-all filter (the Photos tab already
 * covers browsing everything). Multi-person selection is AND (intersection): a matching
 * photo must contain every id in [personIds], not just one of them.
 */
data class PhotoSearchFilter(
    val personIds: Set<Long>,
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val locationBoundingBox: BoundingBox? = null,
)

/** A GPS bounding box used for location filtering, in decimal degrees. */
data class BoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
)
```

- [ ] **Step 4: Create `LocationBoundingBoxCalculator.kt`**

```kotlin
package com.localphotoai.photomanager.domain.search

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Converts a center point + radius into a rectangular [BoundingBox] for a fast indexed
 * `BETWEEN` query, rather than an exact circular geo-distance calculation (unnecessary
 * precision for "near a saved location" search — a bounding box is a cheap superset).
 */
object LocationBoundingBoxCalculator {

    private const val KM_PER_DEGREE_LATITUDE = 111.0

    fun fromPointAndRadiusKm(latitude: Double, longitude: Double, radiusKm: Double): BoundingBox {
        val latDelta = radiusKm / KM_PER_DEGREE_LATITUDE
        // Longitude degrees shrink in real-world distance as latitude moves away from the
        // equator (meridians converge toward the poles); dividing by cos(latitude) keeps the
        // box's real-world east-west width roughly constant regardless of latitude. Clamp
        // near the poles (cos -> 0) to avoid dividing by (near-)zero.
        val cosLatitude = max(cos(Math.toRadians(latitude)), 0.01)
        val lonDelta = radiusKm / (KM_PER_DEGREE_LATITUDE * cosLatitude)

        return BoundingBox(
            minLatitude = max(latitude - latDelta, -90.0),
            maxLatitude = min(latitude + latDelta, 90.0),
            minLongitude = max(longitude - lonDelta, -180.0),
            maxLongitude = min(longitude + lonDelta, 180.0),
        )
    }
}
```

- [ ] **Step 5: Create `SearchRepository.kt`**

```kotlin
package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

/** Access to deterministic people-search queries. Implemented in `:data:database` (Room only). */
interface SearchRepository {
    fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>>
}
```

- [ ] **Step 6: Create `SearchPhotosUseCase.kt`**

```kotlin
package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class SearchPhotosUseCase @Inject constructor(
    private val searchRepository: SearchRepository,
) {
    operator fun invoke(filter: PhotoSearchFilter): AppResult<Flow<PagingData<Photo>>> {
        if (filter.personIds.isEmpty()) {
            return AppResult.Failure(AppError.Validation("Select at least one person to search for."))
        }
        val start = filter.startDateMs
        val end = filter.endDateMs
        if (start != null && end != null && start > end) {
            return AppResult.Failure(AppError.Validation("Start date must be before end date."))
        }
        return AppResult.Success(searchRepository.observeSearchResults(filter))
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.search.*"`
Expected: PASS, 7/7 tests (3 in `LocationBoundingBoxCalculatorTest`, 4 in `SearchPhotosUseCaseTest`).

- [ ] **Step 8: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/search domain/src/test/kotlin/com/localphotoai/photomanager/domain/search
git commit -m "feat(domain): add PhotoSearchFilter, SearchPhotosUseCase, bounding-box math"
```

---

### Task 4: `SearchDao` and `SearchRepositoryImpl`

**Files:**
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/SearchDao.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/SearchRepositoryImpl.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/RepositoryModule.kt`

**Interfaces:**
- Consumes: `PhotoEntity.toDomain()` (existing, `PhotoEntityMappers.kt`); `PhotoSearchFilter`/`BoundingBox`/`SearchRepository` (Task 3).
- Produces: `SearchDao.searchPhotos(...): PagingSource<Int, PhotoEntity>`, `SearchRepositoryImpl` bound to `SearchRepository` via Hilt — consumed by Task 6's `SearchViewModel`.

No automated test for this task — Room query correctness and its performance are verified manually in Task 7 against a real on-device database, per this plan's testing-scope note.

- [ ] **Step 1: Create `SearchDao.kt`**

```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import com.localphotoai.photomanager.data.database.entity.PhotoEntity

@Dao
interface SearchDao {

    /**
     * Photos containing every person in [personIds] (AND/intersection — see [personCount],
     * which must equal `personIds.size`; SQL can't call `COUNT()` on a bound list parameter
     * directly, so the caller passes the count explicitly), optionally narrowed by a
     * `dateTakenMs` range and/or a lat/lon bounding box. A photo with a null `dateTakenMs`
     * never matches a date filter; a photo with null lat/lon never matches a location filter
     * — both are correct behavior (an unknown value shouldn't satisfy a range predicate).
     */
    @Query(
        """
        SELECT p.* FROM photos p
        WHERE p.mediaStoreId IN (
            SELECT joined.photoId FROM (
                SELECT f.photoId AS photoId, pf.personId AS personId
                FROM person_faces pf
                INNER JOIN faces f ON f.id = pf.faceId
                WHERE pf.personId IN (:personIds)
            ) joined
            GROUP BY joined.photoId
            HAVING COUNT(DISTINCT joined.personId) = :personCount
        )
        AND (:startDateMs IS NULL OR p.dateTakenMs >= :startDateMs)
        AND (:endDateMs IS NULL OR p.dateTakenMs <= :endDateMs)
        AND (:minLat IS NULL OR p.latitude BETWEEN :minLat AND :maxLat)
        AND (:minLon IS NULL OR p.longitude BETWEEN :minLon AND :maxLon)
        ORDER BY p.dateTakenMs DESC, p.dateAddedMs DESC
        """,
    )
    fun searchPhotos(
        personIds: List<Long>,
        personCount: Int,
        startDateMs: Long?,
        endDateMs: Long?,
        minLat: Double?,
        maxLat: Double?,
        minLon: Double?,
        maxLon: Double?,
    ): PagingSource<Int, PhotoEntity>
}
```

- [ ] **Step 2: Register `SearchDao` on `AppDatabase`**

In `AppDatabase.kt`, add the import:

```kotlin
import com.localphotoai.photomanager.data.database.dao.SearchDao
```

and add inside the `AppDatabase` class body, after `abstract fun clusteringStatusDao(): ClusteringStatusDao`:

```kotlin
    abstract fun searchDao(): SearchDao
```

- [ ] **Step 3: Provide `SearchDao` via Hilt**

In `DatabaseModule.kt`, add the import:

```kotlin
import com.localphotoai.photomanager.data.database.dao.SearchDao
```

and add inside the `DatabaseModule` object, after `provideClusteringStatusDao`:

```kotlin

    @Provides
    fun provideSearchDao(database: AppDatabase): SearchDao = database.searchDao()
```

- [ ] **Step 4: Create `SearchRepositoryImpl.kt`**

```kotlin
package com.localphotoai.photomanager.data.database

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.localphotoai.photomanager.data.database.dao.SearchDao
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SEARCH_PAGE_SIZE = 30

class SearchRepositoryImpl @Inject constructor(
    private val searchDao: SearchDao,
) : SearchRepository {

    override fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>> {
        val box = filter.locationBoundingBox
        return Pager(
            config = PagingConfig(pageSize = SEARCH_PAGE_SIZE, enablePlaceholders = false),
        ) {
            searchDao.searchPhotos(
                personIds = filter.personIds.toList(),
                personCount = filter.personIds.size,
                startDateMs = filter.startDateMs,
                endDateMs = filter.endDateMs,
                minLat = box?.minLatitude,
                maxLat = box?.maxLatitude,
                minLon = box?.minLongitude,
                maxLon = box?.maxLongitude,
            )
        }.flow.map { pagingData -> pagingData.map { it.toDomain() } }
    }
}
```

- [ ] **Step 5: Bind `SearchRepository` via Hilt**

In `RepositoryModule.kt`, add the import:

```kotlin
import com.localphotoai.photomanager.domain.search.SearchRepository
```

and add inside the `RepositoryModule` class, after `bindPersonRepository`:

```kotlin

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository
```

- [ ] **Step 6: Build**

Run: `./gradlew :data:database:assembleDebug`
Expected: BUILD SUCCESSFUL. If Room's KSP processor rejects the `SearchDao` query (e.g. a column/table name typo), fix the query text to match the exact table/column names in `PhotoEntity`/`FaceEntity`/`PersonFaceEntity` (`photos.mediaStoreId`, `photos.dateTakenMs`, `photos.latitude`/`longitude`, `faces.id`, `faces.photoId`, `person_faces.faceId`, `person_faces.personId`).

- [ ] **Step 7: Commit**

```bash
git add data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/SearchDao.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/SearchRepositoryImpl.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/RepositoryModule.kt
git commit -m "feat(db): add SearchDao and SearchRepositoryImpl for people search"
```

---

### Task 5: Saved search location preference (Settings)

**Files:**
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/settings/SettingsRepository.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/settings/SavedSearchLocation.kt`
- Modify: `data/preferences/src/main/kotlin/com/localphotoai/photomanager/data/preferences/DataStoreSettingsRepository.kt`
- Modify: `feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsScreen.kt`

**Interfaces:**
- Produces: `SavedSearchLocation(latitude, longitude, radiusKm: Double)`, `SettingsRepository.observeSavedSearchLocation(): Flow<SavedSearchLocation?>`, `setSavedSearchLocation(latitude, longitude, radiusKm)`, `clearSavedSearchLocation()` — consumed by Task 6's `SearchViewModel`.

No automated test — this is plain DataStore/ViewModel/Compose glue, matching this project's existing `SettingsRepository`/`SettingsViewModel` (also untested).

- [ ] **Step 1: Create `SavedSearchLocation.kt`**

```kotlin
package com.localphotoai.photomanager.domain.settings

/** A user-saved point + radius used to build a location-search bounding box. */
data class SavedSearchLocation(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
)
```

- [ ] **Step 2: Extend `SettingsRepository`**

Replace the full contents of `SettingsRepository.kt` with:

```kotlin
package com.localphotoai.photomanager.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persists user-facing app preferences. Implemented in `:data:preferences` on top of DataStore.
 */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Null if the user hasn't saved a search location yet. */
    fun observeSavedSearchLocation(): Flow<SavedSearchLocation?>
    suspend fun setSavedSearchLocation(latitude: Double, longitude: Double, radiusKm: Double)
    suspend fun clearSavedSearchLocation()
}
```

- [ ] **Step 3: Implement it in `DataStoreSettingsRepository`**

Replace the full contents of `DataStoreSettingsRepository.kt` with:

```kotlin
package com.localphotoai.photomanager.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import com.localphotoai.photomanager.domain.settings.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val SEARCH_LOCATION_LAT_KEY = doublePreferencesKey("search_location_lat")
private val SEARCH_LOCATION_LON_KEY = doublePreferencesKey("search_location_lon")
private val SEARCH_LOCATION_RADIUS_KM_KEY = doublePreferencesKey("search_location_radius_km")

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override fun observeThemeMode(): Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    override fun observeSavedSearchLocation(): Flow<SavedSearchLocation?> =
        dataStore.data.map { prefs ->
            val lat = prefs[SEARCH_LOCATION_LAT_KEY]
            val lon = prefs[SEARCH_LOCATION_LON_KEY]
            val radius = prefs[SEARCH_LOCATION_RADIUS_KM_KEY]
            if (lat != null && lon != null && radius != null) {
                SavedSearchLocation(latitude = lat, longitude = lon, radiusKm = radius)
            } else {
                null
            }
        }

    override suspend fun setSavedSearchLocation(latitude: Double, longitude: Double, radiusKm: Double) {
        dataStore.edit { prefs ->
            prefs[SEARCH_LOCATION_LAT_KEY] = latitude
            prefs[SEARCH_LOCATION_LON_KEY] = longitude
            prefs[SEARCH_LOCATION_RADIUS_KM_KEY] = radiusKm
        }
    }

    override suspend fun clearSavedSearchLocation() {
        dataStore.edit { prefs ->
            prefs.remove(SEARCH_LOCATION_LAT_KEY)
            prefs.remove(SEARCH_LOCATION_LON_KEY)
            prefs.remove(SEARCH_LOCATION_RADIUS_KM_KEY)
        }
    }
}
```

- [ ] **Step 4: Expose it from `SettingsViewModel`**

Replace the full contents of `SettingsViewModel.kt` with:

```kotlin
package com.localphotoai.photomanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.face.EmbeddingModelDownloader
import com.localphotoai.photomanager.domain.face.ModelDownloadState
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import com.localphotoai.photomanager.domain.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val embeddingModelDownloader: EmbeddingModelDownloader,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.observeThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val modelDownloadState: StateFlow<ModelDownloadState> = embeddingModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelDownloadState.NotDownloaded)

    val savedSearchLocation: StateFlow<SavedSearchLocation?> = settingsRepository.observeSavedSearchLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun onDownloadModelClicked() {
        viewModelScope.launch {
            embeddingModelDownloader.downloadModel()
        }
    }

    fun onSaveSearchLocation(latitude: Double, longitude: Double, radiusKm: Double) {
        viewModelScope.launch {
            settingsRepository.setSavedSearchLocation(latitude, longitude, radiusKm)
        }
    }

    fun onClearSearchLocation() {
        viewModelScope.launch {
            settingsRepository.clearSavedSearchLocation()
        }
    }
}
```

- [ ] **Step 5: Add a "Search location" section to `SettingsScreen`**

Open `feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsScreen.kt` and locate the end of the existing "AI Models" section's `Column`/`Card` content (the section added in Phase 4 with the "Download face-embedding model" button). Add a new sibling section directly after it, inside the same outer scrollable/column container the other settings sections live in:

```kotlin
        SearchLocationSection(
            savedLocation = viewModel.savedSearchLocation.collectAsState().value,
            onSave = viewModel::onSaveSearchLocation,
            onClear = viewModel::onClearSearchLocation,
        )
```

Then add this composable at the bottom of the file (outside any existing private composable, as a new top-level `private fun` in the same file):

```kotlin
@Composable
private fun SearchLocationSection(
    savedLocation: com.localphotoai.photomanager.domain.settings.SavedSearchLocation?,
    onSave: (latitude: Double, longitude: Double, radiusKm: Double) -> Unit,
    onClear: () -> Unit,
) {
    var latitudeText by remember(savedLocation) {
        mutableStateOf(savedLocation?.latitude?.toString() ?: "")
    }
    var longitudeText by remember(savedLocation) {
        mutableStateOf(savedLocation?.longitude?.toString() ?: "")
    }
    var radiusText by remember(savedLocation) {
        mutableStateOf(savedLocation?.radiusKm?.toString() ?: "10")
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Search location", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Used by Search's \"near a saved location\" filter. Enter the coordinates " +
                "of a place you search near often (e.g. home).",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = latitudeText,
            onValueChange = { latitudeText = it },
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = longitudeText,
            onValueChange = { longitudeText = it },
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = radiusText,
            onValueChange = { radiusText = it },
            label = { Text("Radius (km)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(
                onClick = {
                    val lat = latitudeText.toDoubleOrNull()
                    val lon = longitudeText.toDoubleOrNull()
                    val radius = radiusText.toDoubleOrNull()
                    if (lat != null && lon != null && radius != null) {
                        onSave(lat, lon, radius)
                    }
                },
            ) {
                Text("Save")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }
    }
}
```

Add any imports this needs that aren't already present in the file (`androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.width`, `androidx.compose.material3.OutlinedTextField`, `androidx.compose.material3.OutlinedButton`, `androidx.compose.material3.Button`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.collectAsState`) — check the top of the file first and only add ones genuinely missing.

- [ ] **Step 6: Build**

Run: `./gradlew :feature:settings:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manually verify**

Run the app, open Settings, enter a latitude/longitude/radius, tap Save, background and reopen the app, reopen Settings — confirm the fields still show the saved values (DataStore persisted). Tap Clear, confirm the fields reset to empty/default.

- [ ] **Step 8: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/settings feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsViewModel.kt feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsScreen.kt data/preferences/src/main/kotlin/com/localphotoai/photomanager/data/preferences/DataStoreSettingsRepository.kt
git commit -m "feat(settings): add saved search location preference"
```

---

### Task 6: `:feature:search` — person picker, filters, paged results

**Files:**
- Create: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchScreen.kt` (replace placeholder entirely)

**Interfaces:**
- Consumes: `PersonRepository.observePeopleWithStats()` (existing), `SettingsRepository.observeSavedSearchLocation()` (Task 5), `SearchPhotosUseCase` (Task 3), `LocationBoundingBoxCalculator` (Task 3), `PhotoSearchFilter` (Task 3).
- Produces: `SearchViewModel.people: StateFlow<List<PersonWithStats>>`, `.savedLocation: StateFlow<SavedSearchLocation?>`, `.filterUiState: StateFlow<SearchFilterState>`, `.results: Flow<PagingData<Photo>>`, `.onPersonToggled(personId)`, `.onYearSelected(year: Int?)`, `.onLocationFilterToggled(enabled)` — consumed by `SearchScreen`.

No automated test — ViewModel/Compose glue, per this plan's testing scope.

- [ ] **Step 1: Create `SearchViewModel.kt`**

```kotlin
package com.localphotoai.photomanager.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.LocationBoundingBoxCalculator
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** In-progress filter selection made by the user in the Search screen. */
data class SearchFilterState(
    val selectedPersonIds: Set<Long> = emptySet(),
    val selectedYear: Int? = null,
    val locationFilterEnabled: Boolean = false,
) {
    fun toDomainFilterOrNull(savedLocation: SavedSearchLocation?): PhotoSearchFilter? {
        if (selectedPersonIds.isEmpty()) return null

        val (startDateMs, endDateMs) = selectedYear?.let { yearRangeMs(it) } ?: (null to null)

        val boundingBox = if (locationFilterEnabled && savedLocation != null) {
            LocationBoundingBoxCalculator.fromPointAndRadiusKm(
                latitude = savedLocation.latitude,
                longitude = savedLocation.longitude,
                radiusKm = savedLocation.radiusKm,
            )
        } else {
            null
        }

        return PhotoSearchFilter(
            personIds = selectedPersonIds,
            startDateMs = startDateMs,
            endDateMs = endDateMs,
            locationBoundingBox = boundingBox,
        )
    }

    private fun yearRangeMs(year: Int): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year, 12, 31).atTime(23, 59, 59)
            .atZone(zone).toInstant().toEpochMilli()
        return start to end
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    personRepository: PersonRepository,
    settingsRepository: SettingsRepository,
    private val searchPhotosUseCase: SearchPhotosUseCase,
) : ViewModel() {

    val people: StateFlow<List<PersonWithStats>> = personRepository.observePeopleWithStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedLocation: StateFlow<SavedSearchLocation?> = settingsRepository.observeSavedSearchLocation()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val filterState = MutableStateFlow(SearchFilterState())
    val filterUiState: StateFlow<SearchFilterState> = filterState.asStateFlow()

    val results: Flow<PagingData<Photo>> = combine(filterState, savedLocation) { state, saved ->
        state.toDomainFilterOrNull(saved)
    }
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            if (filter == null) {
                flowOf(PagingData.empty())
            } else {
                when (val result = searchPhotosUseCase(filter)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> flowOf(PagingData.empty())
                }
            }
        }
        .cachedIn(viewModelScope)

    fun onPersonToggled(personId: Long) {
        filterState.update { current ->
            val newSelection = if (personId in current.selectedPersonIds) {
                current.selectedPersonIds - personId
            } else {
                current.selectedPersonIds + personId
            }
            current.copy(selectedPersonIds = newSelection)
        }
    }

    fun onYearSelected(year: Int?) {
        filterState.update { it.copy(selectedYear = year) }
    }

    fun onLocationFilterToggled(enabled: Boolean) {
        filterState.update { it.copy(locationFilterEnabled = enabled) }
    }
}
```

- [ ] **Step 2: Replace `SearchScreen.kt`**

```kotlin
package com.localphotoai.photomanager.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.Photo
import java.time.Year

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val people by viewModel.people.collectAsState()
    val savedLocation by viewModel.savedLocation.collectAsState()
    val filterState by viewModel.filterUiState.collectAsState()
    val results = viewModel.results.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (people.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No people found yet. Search needs at least one person discovered " +
                            "in the People tab first.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Scaffold
            }

            PersonPickerRow(
                people = people,
                selectedPersonIds = filterState.selectedPersonIds,
                onPersonToggled = viewModel::onPersonToggled,
            )
            YearPickerRow(
                selectedYear = filterState.selectedYear,
                onYearSelected = viewModel::onYearSelected,
            )
            if (savedLocation != null) {
                LocationFilterRow(
                    enabled = filterState.locationFilterEnabled,
                    onToggled = viewModel::onLocationFilterToggled,
                )
            }

            if (filterState.selectedPersonIds.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Select at least one person above to search.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (results.itemCount == 0) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No photos match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(results.itemCount) { index ->
                        val photo = results[index]
                        if (photo != null) {
                            SearchResultThumbnail(photo)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonPickerRow(
    people: List<PersonWithStats>,
    selectedPersonIds: Set<Long>,
    onPersonToggled: (Long) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(people, key = { it.id }) { person ->
            FilterChip(
                selected = person.id in selectedPersonIds,
                onClick = { onPersonToggled(person.id) },
                label = { Text(person.name ?: "Unnamed") },
            )
        }
    }
}

@Composable
private fun YearPickerRow(selectedYear: Int?, onYearSelected: (Int?) -> Unit) {
    val currentYear = Year.now().value
    val years = (currentYear downTo currentYear - 4).toList()

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(listOf<Int?>(null) + years) { year ->
            FilterChip(
                selected = selectedYear == year,
                onClick = { onYearSelected(year) },
                label = { Text(year?.toString() ?: "All time") },
            )
        }
    }
}

@Composable
private fun LocationFilterRow(enabled: Boolean, onToggled: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Near saved location", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onToggled)
    }
}

@Composable
private fun SearchResultThumbnail(photo: Photo) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.filename,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manually verify in the running app**

Using the existing Phase 5 test data (named/unnamed people already clustered):
1. Open the Search tab. Confirm the person chip row shows the same people as the People tab.
2. Tap one person's chip. Confirm the results grid shows only photos containing that person (spot-check against the People tab's detail screen for that person).
3. Tap a second person's chip. Confirm the results narrow to only photos containing both (fewer or equal results than step 2, never more).
4. Tap a year chip. Confirm results narrow further to that year (or show empty if no test photos have `dateTakenMs` in that year — expected, since Phases 2–5's synthetic test photos may not span real years).
5. If a search location was saved in Task 5, toggle "Near saved location" on/off and confirm the result count changes accordingly.
6. Deselect all people. Confirm the "select at least one person" prompt reappears and the grid clears.

- [ ] **Step 5: Commit**

```bash
git add feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search
git commit -m "feat(search): add person/year/location filter UI with paged results"
```

---

### Task 7: Performance verification at scale

**Files:**
- Modify: `docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md` (append Phase 6 verification results)

No source files change in this task — it is a manual on-device measurement pass, per this plan's testing-scope note, using the same "insert synthetic rows directly into Room" technique as Phases 4/5.

- [ ] **Step 1: Generate ~10,000 synthetic photos and ~5,000 synthetic faces on-device**

With the app installed (from Task 6) on the test emulator/device, pull the database path and run the following via `sqlite3` (adjust the package's data dir if different):

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db <<'SQL'
-- 10,000 synthetic photos spread across 2023-01-01..2025-12-31, with a lat/lon scatter
-- on every 5th photo (2,000 of them) so location filtering has real matches to find.
WITH RECURSIVE seq(x) AS (
    SELECT 1
    UNION ALL
    SELECT x + 1 FROM seq WHERE x < 10000
)
INSERT INTO photos (
    mediaStoreId, uri, filename, mimeType, sizeBytes, width, height,
    dateAddedMs, dateModifiedMs, dateTakenMs, orientationDegrees,
    latitude, longitude, lastIndexedAtMs, indexError
)
SELECT
    900000 + x,
    'content://media/external/images/media/' || (900000 + x),
    'synth_' || x || '.jpg',
    'image/jpeg',
    2000000,
    1920, 1080,
    1672531200000 + (x * 9460000),
    1672531200000 + (x * 9460000),
    1672531200000 + (x * 9460000),
    0,
    CASE WHEN x % 5 = 0 THEN 12.9 + (x % 100) * 0.001 ELSE NULL END,
    CASE WHEN x % 5 = 0 THEN 77.6 + (x % 100) * 0.001 ELSE NULL END,
    1672531200000,
    NULL
FROM seq;

-- 5,000 synthetic faces, one per photo for the first 5,000 synthetic photos.
WITH RECURSIVE seq(x) AS (
    SELECT 1
    UNION ALL
    SELECT x + 1 FROM seq WHERE x < 5000
)
INSERT INTO faces (photoId, left, top, right, bottom, confidence, rotationDegrees, markedIncorrect)
SELECT 900000 + x, 0.1, 0.1, 0.5, 0.5, 1.0, 0, 0
FROM seq;

-- 20 synthetic person clusters; assign each of the 5,000 faces to one of them round-robin,
-- and additionally assign every 7th face to a second person too (so multi-person AND queries
-- have real intersecting matches).
WITH RECURSIVE seq(x) AS (
    SELECT 1
    UNION ALL
    SELECT x + 1 FROM seq WHERE x < 20
)
INSERT INTO people (name, representativeFaceId, createdAt, clusterAlgoVersion, centroidSum, memberCount)
SELECT 'SynthPerson' || x, NULL, 1672531200000, 1, x'00', 0
FROM seq;

INSERT INTO person_faces (faceId, personId, clusterConfidence)
SELECT id, 1 + ((id - 1) % 20), 1.0 FROM faces WHERE id BETWEEN 1 AND 5000;
SQL
```

- [ ] **Step 2: Add a second person to every 7th face for multi-person AND coverage**

`person_faces.faceId` is a primary key (one person per face), so a genuine second-person-per-photo test needs a second face row on the same photo pointing at a different person:

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db <<'SQL'
INSERT INTO faces (photoId, left, top, right, bottom, confidence, rotationDegrees, markedIncorrect)
SELECT photoId, 0.5, 0.5, 0.9, 0.9, 1.0, 0, 0
FROM faces
WHERE id BETWEEN 1 AND 5000 AND id % 7 = 0;

INSERT INTO person_faces (faceId, personId, clusterConfidence)
SELECT id, 1 + (id % 20), 1.0
FROM faces
WHERE id > 5000;
SQL
```

- [ ] **Step 3: Measure query latency WITH indexes (current state, post-Task 2)**

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db <<'SQL'
.timer on

-- Single person
SELECT COUNT(*) FROM photos p WHERE p.mediaStoreId IN (
    SELECT joined.photoId FROM (
        SELECT f.photoId AS photoId, pf.personId AS personId
        FROM person_faces pf INNER JOIN faces f ON f.id = pf.faceId
        WHERE pf.personId IN (1)
    ) joined GROUP BY joined.photoId HAVING COUNT(DISTINCT joined.personId) = 1
);

-- Multi-person AND (2 people)
SELECT COUNT(*) FROM photos p WHERE p.mediaStoreId IN (
    SELECT joined.photoId FROM (
        SELECT f.photoId AS photoId, pf.personId AS personId
        FROM person_faces pf INNER JOIN faces f ON f.id = pf.faceId
        WHERE pf.personId IN (1, 2)
    ) joined GROUP BY joined.photoId HAVING COUNT(DISTINCT joined.personId) = 2
);

-- Person + date range
SELECT COUNT(*) FROM photos p WHERE p.mediaStoreId IN (
    SELECT joined.photoId FROM (
        SELECT f.photoId AS photoId, pf.personId AS personId
        FROM person_faces pf INNER JOIN faces f ON f.id = pf.faceId
        WHERE pf.personId IN (1)
    ) joined GROUP BY joined.photoId HAVING COUNT(DISTINCT joined.personId) = 1
) AND p.dateTakenMs >= 1704067200000 AND p.dateTakenMs <= 1735689600000;

-- Person + location bounding box
SELECT COUNT(*) FROM photos p WHERE p.mediaStoreId IN (
    SELECT joined.photoId FROM (
        SELECT f.photoId AS photoId, pf.personId AS personId
        FROM person_faces pf INNER JOIN faces f ON f.id = pf.faceId
        WHERE pf.personId IN (1)
    ) joined GROUP BY joined.photoId HAVING COUNT(DISTINCT joined.personId) = 1
) AND p.latitude BETWEEN 12.0 AND 13.0 AND p.longitude BETWEEN 77.0 AND 78.0;

-- First page vs. a deep page (pagination cost should not grow noticeably with offset
-- for this dataset size)
SELECT * FROM photos ORDER BY dateTakenMs DESC, dateAddedMs DESC LIMIT 30 OFFSET 0;
SELECT * FROM photos ORDER BY dateTakenMs DESC, dateAddedMs DESC LIMIT 30 OFFSET 9900;

.timer off
SQL
```

Record each query's wall time (sqlite3's `.timer on` prints real/CPU time after every statement) in the plan doc (Step 5 below).

- [ ] **Step 4: Measure the same queries WITHOUT the indexes, to prove they matter**

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db <<'SQL'
DROP INDEX index_photos_dateTakenMs;
DROP INDEX index_photos_latitude_longitude;
SQL
```

Re-run the exact same `.timer on` block from Step 3 and record the new timings. Then restore the indexes so the on-device database matches what Room expects (otherwise the app will crash on next open with a schema-mismatch error):

```bash
adb shell run-as com.localphotoai.photomanager sqlite3 databases/photo-manager.db <<'SQL'
CREATE INDEX IF NOT EXISTS index_photos_dateTakenMs ON photos(dateTakenMs);
CREATE INDEX IF NOT EXISTS index_photos_latitude_longitude ON photos(latitude, longitude);
SQL
```

- [ ] **Step 5: Verify correctness, not just speed**

Spot-check the multi-person AND query is actually excluding non-matches: pick a `personId` pair where you know (from Step 2's `% 7` assignment) only some faces have both, and confirm the returned `COUNT(*)` is smaller than either single-person count from Step 3, and greater than 0.

- [ ] **Step 6: Verify in the live app UI too**

Reopen the app (Search tab) and confirm it still loads without a crash (schema matches after Step 4's index restore) and that scrolling through ~10,000 synthetic results in the grid stays smooth (no visible jank/ANR) when a broad filter (e.g. just one person, no date/location) is selected.

- [ ] **Step 7: Record results in the plan doc**

Open `docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md`, find the `### Phase 6: Search by people` section, and replace its current two lines (`**Deliverable:** ...` / `**Verification gate:** ...`) with:

```markdown
### Phase 6: Search by people
**Status:** Done

**Deliverable:** Deterministic (non-LLM) database/vector queries. Support: photos of a selected person, photos with multiple selected people, person + date filtering, person + folder/location filtering where metadata exists. Pagination/lazy loading, appropriate indexes.

**What was built:** [fill in: SearchDao's single parameterized query with the AND-intersection subquery, SearchRepository/SearchPhotosUseCase in :domain, Jetpack Paging 3 wiring through SearchViewModel, the Search screen's person/year/location filter UI, the saved-search-location Settings addition, and the two new photos indexes added in migration 4->5 — summarize in the same style as the Phase 2-5 entries above, referencing this plan's task numbers.]

**Verification performed:** [fill in the actual measured numbers from Steps 3-4 above: query latency with vs. without indexes for single-person/multi-person-AND/person+date/person+location, and first-page vs. deep-page pagination latency, on a ~10,000-photo/~5,000-face synthetic dataset generated per Task 7's sqlite3 scripts. State plainly whether the indexes measurably helped or not — do not claim a performance win without the numbers to back it.]

**Known limitations (non-blocking):** [fill in any real limitations discovered during this pass, e.g. UI date filtering is year-granularity only even though the query layer supports arbitrary millisecond ranges; folder-based filtering is out of scope (no folder metadata exists — see spec §10).]
```

Fill in the bracketed prose with what was actually observed/built — do not leave the brackets in the committed file.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md
git commit -m "docs: record Phase 6 search-by-people verification results"
```

---

## Self-Review Notes

**Spec coverage:** §2 multi-person AND → Task 4's `SearchDao` subquery + Task 7 Step 5. §2 location-bbox-only → Task 3/4. §2 pagination via Paging 3 → Tasks 1/4/6. §2 query-composition pattern → Task 4. §2 10k/5k dataset target → Task 7. §3 index additions → Task 2 (with the `FaceEntity` correction noted). §4 query design → Task 4 (matches spec's SQL near-verbatim). §5 domain layer → Task 3. §6 UI → Task 6 (year-granularity date filter is a documented, explicit scope-cut from the spec's "custom from/to" mention, called out in Task 7 Step 7's known-limitations prompt). §7 testing → covered by this plan's testing-scope deviation note (Task 3's unit tests + manual verification elsewhere, replacing spec §7's instrumented-DAO-test suggestion, which would have required new test infrastructure this project deliberately avoids). §8 performance gate → Task 7. §9 migration → Task 2. §10 deferred items → not implemented, correctly absent from every task.

**Type consistency:** `PhotoSearchFilter`/`BoundingBox` (Task 3) match their usage in `SearchRepositoryImpl` (Task 4) and `SearchFilterState.toDomainFilterOrNull` (Task 6). `SearchPhotosUseCase.invoke` returns `AppResult<Flow<PagingData<Photo>>>` consistently between Task 3's test and Task 6's `SearchViewModel.results`. `SavedSearchLocation` (Task 5) fields (`latitude`, `longitude`, `radiusKm`) match `LocationBoundingBoxCalculator.fromPointAndRadiusKm`'s parameter names (Task 3) and `SettingsViewModel.onSaveSearchLocation`'s signature (Task 5).
