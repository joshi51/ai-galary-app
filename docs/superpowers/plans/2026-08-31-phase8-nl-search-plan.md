# Phase 8 — Natural-Language AI Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. **Deviation from the standard writing-plans format**, per this project's standing "basic-level testing only, scoped to business logic" preference (see Global Constraints): pure-Kotlin business-logic tasks (domain use cases, tool validation, grammar/JSON parsing, orchestration retry logic) use full TDD red/green/commit steps. Tasks touching native code (llama.cpp/JNI/CMake), Room schema-free DAO wiring, or Compose UI use an implement-then-manually-verify structure instead — consistent with how Phases 3/4/7's ML/native work was actually executed in this project.

**Goal:** Let a user type a natural-language photo query ("Show me photos of Rahul from 2025") and have a fully on-device LLM (llama.cpp) translate it into exactly one validated, structured tool call against existing search/duplicate/stats logic — never touching the filesystem or database directly.

**Architecture:** Two new pure-Kotlin modules (`:tools`, `:llm:orchestration`) hold everything unit-testable — tool validation, GBNF grammar generation, LLM-output JSON parsing, retry/fallback orchestration. One new Android module (`:llm:runtime`) isolates the native llama.cpp JNI bridge and is the only piece that can't be exercised on the JVM. `:domain`/`:data:database` get small, additive extensions (optional-person search, `get_photo_metadata`, `get_storage_statistics`) that the new tools call into, matching the pattern every prior phase used.

**Tech Stack:** Kotlin, llama.cpp (vendored source, built via CMake/NDK), GBNF grammar-constrained decoding, kotlinx.serialization (JSON), Room, Jetpack Compose.

**Spec:** [docs/superpowers/specs/2026-08-31-phase8-nl-search-design.md](../specs/2026-08-31-phase8-nl-search-design.md)

## Global Constraints

- No paid/mandatory cloud AI APIs — the LLM runs fully on-device (llama.cpp, GGUF, no Google-branded model).
- The LLM never gets direct filesystem/database access — only validated `:tools` calls, which call exactly one `:domain` use case each.
- Never commit to git unless explicitly asked in the current request.
- **Basic-level testing only, scoped to business logic** — unit tests for `:domain`/`:tools`/`:llm:orchestration` pure logic; no UI/Compose/ViewModel/DI tests; no tests for the native JNI bridge itself (verified manually on-device).
- Model choice: **Llama-3.2-1B-Instruct GGUF, Q4_K_M** — license/provenance formally verified during Task 9, documented in ARCHITECTURE.md with the same rigor as Phase 4 §33.
- Structured output via **GBNF grammar-constrained decoding**, not free-form JSON the model is merely asked to produce.
- Single-turn only, no conversation memory; no second LLM call for a natural-language summary (deterministic template instead).
- Tracing is logcat-only this phase (via `core.common.Logger`) — never filenames/file paths/GPS coordinates, only counts/IDs; no new persisted trace table (deferred to Phase 11).
- Every phase-8 module addition/extension must not regress the existing 81 domain/core unit tests or break `:app:assembleDebug`.

---

## File structure overview

```
domain/src/main/kotlin/.../domain/search/PhotoSearchFilter.kt      — MODIFY (optional people, sortBy)
domain/src/main/kotlin/.../domain/search/SearchRepository.kt       — MODIFY (+fetchOnce)
domain/src/main/kotlin/.../domain/search/SearchPhotosUseCase.kt    — MODIFY (+searchOnce, loosened validation)
domain/src/main/kotlin/.../domain/photo/PhotoRepository.kt         — MODIFY (+fetchById/fetchByIds)
domain/src/main/kotlin/.../domain/photo/GetPhotoMetadataUseCase.kt — NEW
domain/src/main/kotlin/.../domain/statistics/StorageStatistics.kt  — NEW (model + repo interface + use case)
domain/src/main/kotlin/.../domain/tool/*.kt                        — NEW (ToolName, ToolCall, ToolOutcome, Tool)

data/database/src/main/kotlin/.../dao/SearchDao.kt                 — MODIFY (optional person, sortBy, +searchPhotosOnce)
data/database/src/main/kotlin/.../SearchRepositoryImpl.kt          — MODIFY
data/database/src/main/kotlin/.../dao/PhotoDao.kt                  — MODIFY (+getById/getByIds)
data/database/src/main/kotlin/.../dao/StatisticsDao.kt             — NEW
data/database/src/main/kotlin/.../StorageStatisticsRepositoryImpl.kt — NEW
data/database/src/main/kotlin/.../AppDatabase.kt                   — MODIFY (register StatisticsDao)
data/database/src/main/kotlin/.../DatabaseModule.kt                — MODIFY (DAO/UseCase providers)
data/media/src/main/kotlin/.../PhotoRepositoryImpl.kt               — MODIFY (+fetchById/fetchByIds)

tools/build.gradle.kts                                              — NEW module (kotlin.jvm)
tools/src/main/kotlin/.../tools/ToolRegistry.kt                     — NEW
tools/src/main/kotlin/.../tools/ToolValidator.kt                    — NEW
tools/src/main/kotlin/.../tools/SearchPhotosTool.kt                 — NEW
tools/src/main/kotlin/.../tools/FindDuplicatesTool.kt               — NEW
tools/src/main/kotlin/.../tools/FindSimilarPhotosTool.kt            — NEW
tools/src/main/kotlin/.../tools/GetPhotoMetadataTool.kt             — NEW
tools/src/main/kotlin/.../tools/GetStorageStatisticsTool.kt         — NEW

llm/orchestration/build.gradle.kts                                  — NEW module (kotlin.jvm)
llm/orchestration/src/main/kotlin/.../orchestration/GrammarBuilder.kt — NEW
llm/orchestration/src/main/kotlin/.../orchestration/ToolCallParser.kt — NEW
llm/orchestration/src/main/kotlin/.../orchestration/ToolCallLoop.kt  — NEW
llm/orchestration/src/main/kotlin/.../orchestration/TraceLogger.kt   — NEW

llm/runtime/build.gradle.kts                                        — NEW module (android.library)
llm/runtime/src/main/cpp/CMakeLists.txt                             — NEW
llm/runtime/src/main/cpp/llama.cpp/                                 — NEW (vendored, pinned commit)
llm/runtime/src/main/cpp/llm_jni.cpp                                — NEW
llm/runtime/src/main/kotlin/.../runtime/NativeLlamaBridge.kt        — NEW
llm/runtime/src/main/kotlin/.../runtime/LlamaCppEngine.kt           — NEW
llm/runtime/src/main/kotlin/.../runtime/ModelFileStore.kt           — NEW
llm/runtime/src/main/kotlin/.../runtime/Llama32ModelSpec.kt         — NEW
llm/runtime/src/main/kotlin/.../runtime/LlmModelDownloader.kt       — NEW
llm/runtime/src/main/kotlin/.../runtime/RuntimeModule.kt            — NEW

feature/settings/src/main/kotlin/.../SettingsViewModel.kt           — MODIFY
feature/settings/src/main/kotlin/.../SettingsScreen.kt              — MODIFY
feature/search/src/main/kotlin/.../SearchViewModel.kt               — MODIFY
feature/search/src/main/kotlin/.../SearchScreen.kt                  — MODIFY

settings.gradle.kts, gradle/libs.versions.toml                      — MODIFY
```

---

### Task 1: Domain search layer — optional person filter, sort order, bounded one-shot fetch

**Files:**
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/PhotoSearchFilter.kt`
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/SearchRepository.kt`
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/search/SearchPhotosUseCase.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/search/SearchPhotosUseCaseTest.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/SearchDao.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/SearchRepositoryImpl.kt`

**Interfaces:**
- Produces: `PhotoSearchFilter(personIds: Set<Long> = emptySet(), startDateMs: Long? = null, endDateMs: Long? = null, locationBoundingBox: BoundingBox? = null, sortBy: PhotoSortOrder = PhotoSortOrder.NEWEST)`, `enum class PhotoSortOrder { NEWEST, LARGEST, SMALLEST }`, `SearchPhotosUseCase.searchOnce(filter: PhotoSearchFilter, limit: Int): AppResult<List<Photo>>` — Task 5's `SearchPhotosTool` calls this.
- Consumes: nothing new from earlier tasks.

The existing "must select at least one person" rule moves out of the domain layer (it becomes a deterministic-search-UI-only choice in `SearchViewModel`, which already returns `null` from `toDomainFilterOrNull` when nothing is selected — no UI behavior changes). The domain layer must support an empty `personIds` set as a legitimate "no person filter" query, since NL queries like "find my largest photos" have no person at all.

- [ ] **Step 1: Write the failing/updated test**

Replace the full contents of `SearchPhotosUseCaseTest.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSearchRepository : SearchRepository {
    var lastFilter: PhotoSearchFilter? = null
    var callCount = 0
    var onceResult: List<Photo> = emptyList()
    var lastOnceLimit: Int? = null

    override fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>> {
        lastFilter = filter
        callCount++
        return flowOf(PagingData.empty())
    }

    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        lastFilter = filter
        lastOnceLimit = limit
        callCount++
        return onceResult
    }
}

class SearchPhotosUseCaseTest {

    @Test
    fun `accepts a filter with no selected people`() {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase(PhotoSearchFilter(personIds = emptySet()))

        assertTrue(result is AppResult.Success)
        assertEquals(1, repository.callCount)
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

    @Test
    fun `searchOnce rejects an invalid date range without calling the repository`() = runBlocking {
        val repository = FakeSearchRepository()
        val useCase = SearchPhotosUseCase(repository)

        val result = useCase.searchOnce(
            PhotoSearchFilter(startDateMs = 2_000L, endDateMs = 1_000L),
            limit = 200,
        )

        assertTrue(result is AppResult.Failure)
        assertEquals(0, repository.callCount)
    }

    @Test
    fun `searchOnce passes the limit and sort order through to the repository`() = runBlocking {
        val repository = FakeSearchRepository()
        repository.onceResult = listOf(
            Photo(
                mediaStoreId = 1L, uri = "content://1", filename = "a.jpg", mimeType = "image/jpeg",
                sizeBytes = 100L, widthPx = 10, heightPx = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
                dateTakenMs = null, latitude = null, longitude = null, orientationDegrees = 0,
            ),
        )
        val useCase = SearchPhotosUseCase(repository)
        val filter = PhotoSearchFilter(sortBy = PhotoSortOrder.LARGEST)

        val result = useCase.searchOnce(filter, limit = 50)

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).value.size)
        assertEquals(50, repository.lastOnceLimit)
        assertEquals(PhotoSortOrder.LARGEST, repository.lastFilter?.sortBy)
    }
}
```

Check `Photo`'s exact constructor parameters first (`domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo/Photo.kt`) and adjust the fake `Photo(...)` call above to match field-for-field if they differ — this test must compile against the real model, not a guessed shape.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.search.SearchPhotosUseCaseTest"`
Expected: FAIL — `fetchOnce` doesn't exist on `SearchRepository`, `searchOnce` doesn't exist on `SearchPhotosUseCase`, `PhotoSearchFilter.sortBy` doesn't exist, `personIds` has no default.

- [ ] **Step 3: Implement the domain-layer changes**

`PhotoSearchFilter.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

enum class PhotoSortOrder { NEWEST, LARGEST, SMALLEST }

/**
 * A deterministic (non-LLM) photo search request. [personIds] may be empty — an empty set means
 * "no person filter" (e.g. Phase 8's "find my largest photos"), not "match nothing". The
 * deterministic Search UI still requires the user to pick at least one person before submitting
 * a filter (see `SearchViewModel.toDomainFilterOrNull`) — that's a UI choice, not a domain
 * invariant, now that Phase 8's tool layer needs person-less queries to be valid. Multi-person
 * selection is AND (intersection): a matching photo must contain every id in [personIds].
 */
data class PhotoSearchFilter(
    val personIds: Set<Long> = emptySet(),
    val startDateMs: Long? = null,
    val endDateMs: Long? = null,
    val locationBoundingBox: BoundingBox? = null,
    val sortBy: PhotoSortOrder = PhotoSortOrder.NEWEST,
)

/** A GPS bounding box used for location filtering, in decimal degrees. */
data class BoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
)
```

`SearchRepository.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

/** Access to deterministic search queries. Implemented in `:data:database` (Room only). */
interface SearchRepository {
    fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>>

    /** A bounded, non-paged snapshot — for Phase 8's tool-driven queries, which need a fixed
     * result set to summarize (a count for the templated response), not infinite scroll. */
    suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo>
}
```

`SearchPhotosUseCase.kt`:

```kotlin
package com.localphotoai.photomanager.domain.search

import androidx.paging.PagingData
import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

class SearchPhotosUseCase(
    private val searchRepository: SearchRepository,
) {
    operator fun invoke(filter: PhotoSearchFilter): AppResult<Flow<PagingData<Photo>>> {
        validate(filter)?.let { return AppResult.Failure(it) }
        return AppResult.Success(searchRepository.observeSearchResults(filter))
    }

    suspend fun searchOnce(filter: PhotoSearchFilter, limit: Int): AppResult<List<Photo>> {
        validate(filter)?.let { return AppResult.Failure(it) }
        return AppResult.Success(searchRepository.fetchOnce(filter, limit))
    }

    private fun validate(filter: PhotoSearchFilter): AppError? {
        val start = filter.startDateMs
        val end = filter.endDateMs
        if (start != null && end != null && start > end) {
            return AppError.Validation("Start date must be before end date.")
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.search.SearchPhotosUseCaseTest"`
Expected: PASS, 6/6.

- [ ] **Step 5: Update the Room query and repository implementation (manual verification — SQL, not unit-tested)**

`SearchDao.kt` — replace `searchPhotos` and add `searchPhotosOnce`:

```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import com.localphotoai.photomanager.data.database.entity.PhotoEntity

private const val SEARCH_WHERE_ORDER = """
    WHERE (:personCount = 0 OR p.mediaStoreId IN (
        SELECT joined.photoId FROM (
            SELECT f.photoId AS photoId, pf.personId AS personId
            FROM person_faces pf
            INNER JOIN faces f ON f.id = pf.faceId
            WHERE pf.personId IN (:personIds)
        ) joined
        GROUP BY joined.photoId
        HAVING COUNT(DISTINCT joined.personId) = :personCount
    ))
    AND (:startDateMs IS NULL OR p.dateTakenMs >= :startDateMs)
    AND (:endDateMs IS NULL OR p.dateTakenMs <= :endDateMs)
    AND (:minLat IS NULL OR p.latitude BETWEEN :minLat AND :maxLat)
    AND (:minLon IS NULL OR p.longitude BETWEEN :minLon AND :maxLon)
    ORDER BY
        CASE WHEN :sortBy = 'LARGEST' THEN p.sizeBytes END DESC,
        CASE WHEN :sortBy = 'SMALLEST' THEN p.sizeBytes END ASC,
        p.dateTakenMs DESC, p.dateAddedMs DESC
"""
// Room requires the literal SQL in each @Query annotation (no runtime string concatenation of
// query text), so SEARCH_WHERE_ORDER above is documentation of the shared shape only — copy it
// verbatim into both @Query strings below rather than referencing the constant.

@Dao
interface SearchDao {

    /** A photo with a null `dateTakenMs`/lat/lon never matches a date/location filter — an
     * unknown value shouldn't satisfy a range predicate. `:personCount = 0` means "no person
     * filter" (Phase 8's person-less queries), not "match nothing". */
    @Query(
        """
        SELECT p.* FROM photos p
        WHERE (:personCount = 0 OR p.mediaStoreId IN (
            SELECT joined.photoId FROM (
                SELECT f.photoId AS photoId, pf.personId AS personId
                FROM person_faces pf
                INNER JOIN faces f ON f.id = pf.faceId
                WHERE pf.personId IN (:personIds)
            ) joined
            GROUP BY joined.photoId
            HAVING COUNT(DISTINCT joined.personId) = :personCount
        ))
        AND (:startDateMs IS NULL OR p.dateTakenMs >= :startDateMs)
        AND (:endDateMs IS NULL OR p.dateTakenMs <= :endDateMs)
        AND (:minLat IS NULL OR p.latitude BETWEEN :minLat AND :maxLat)
        AND (:minLon IS NULL OR p.longitude BETWEEN :minLon AND :maxLon)
        ORDER BY
            CASE WHEN :sortBy = 'LARGEST' THEN p.sizeBytes END DESC,
            CASE WHEN :sortBy = 'SMALLEST' THEN p.sizeBytes END ASC,
            p.dateTakenMs DESC, p.dateAddedMs DESC
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
        sortBy: String,
    ): PagingSource<Int, PhotoEntity>

    @Query(
        """
        SELECT p.* FROM photos p
        WHERE (:personCount = 0 OR p.mediaStoreId IN (
            SELECT joined.photoId FROM (
                SELECT f.photoId AS photoId, pf.personId AS personId
                FROM person_faces pf
                INNER JOIN faces f ON f.id = pf.faceId
                WHERE pf.personId IN (:personIds)
            ) joined
            GROUP BY joined.photoId
            HAVING COUNT(DISTINCT joined.personId) = :personCount
        ))
        AND (:startDateMs IS NULL OR p.dateTakenMs >= :startDateMs)
        AND (:endDateMs IS NULL OR p.dateTakenMs <= :endDateMs)
        AND (:minLat IS NULL OR p.latitude BETWEEN :minLat AND :maxLat)
        AND (:minLon IS NULL OR p.longitude BETWEEN :minLon AND :maxLon)
        ORDER BY
            CASE WHEN :sortBy = 'LARGEST' THEN p.sizeBytes END DESC,
            CASE WHEN :sortBy = 'SMALLEST' THEN p.sizeBytes END ASC,
            p.dateTakenMs DESC, p.dateAddedMs DESC
        LIMIT :limit
        """,
    )
    suspend fun searchPhotosOnce(
        personIds: List<Long>,
        personCount: Int,
        startDateMs: Long?,
        endDateMs: Long?,
        minLat: Double?,
        maxLat: Double?,
        minLon: Double?,
        maxLon: Double?,
        sortBy: String,
        limit: Int,
    ): List<PhotoEntity>
}
```

`SearchRepositoryImpl.kt`:

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
                sortBy = filter.sortBy.name,
            )
        }.flow.map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        val box = filter.locationBoundingBox
        return searchDao.searchPhotosOnce(
            personIds = filter.personIds.toList(),
            personCount = filter.personIds.size,
            startDateMs = filter.startDateMs,
            endDateMs = filter.endDateMs,
            minLat = box?.minLatitude,
            maxLat = box?.maxLatitude,
            minLon = box?.minLongitude,
            maxLon = box?.maxLongitude,
            sortBy = filter.sortBy.name,
            limit = limit,
        ).map { it.toDomain() }
    }
}
```

- [ ] **Step 6: Manually verify**

Run: `./gradlew :domain:test :app:assembleDebug`
Expected: domain tests pass (including the 5 pre-existing `LocationBoundingBoxCalculatorTest` + other suites, no regressions), build succeeds (no schema change, no migration needed — confirm no new Room warning about a missing migration).

- [ ] **Step 7: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/search domain/src/test/kotlin/com/localphotoai/photomanager/domain/search data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/SearchDao.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/SearchRepositoryImpl.kt
git commit -m "feat: support person-less search filters and sort order for tool-driven queries"
```

---

### Task 2: `get_photo_metadata` support — `PhotoRepository.fetchById`/`fetchByIds`, `GetPhotoMetadataUseCase`

**Files:**
- Modify: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo/PhotoRepository.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo/GetPhotoMetadataUseCase.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/photo/GetPhotoMetadataUseCaseTest.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/PhotoDao.kt`
- Modify: `data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/PhotoRepositoryImpl.kt`

**Interfaces:**
- Produces: `PhotoRepository.fetchById(mediaStoreId: Long): Photo?`, `PhotoRepository.fetchByIds(mediaStoreIds: List<Long>): List<Photo>`, `GetPhotoMetadataUseCase.invoke(mediaStoreId: Long): AppResult<Photo>` — Task 5's `GetPhotoMetadataTool` and `FindDuplicatesTool`/`FindSimilarPhotosTool` call these.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePhotoRepository : PhotoRepository {
    var photoToReturn: Photo? = null

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
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photoToReturn
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = listOfNotNull(photoToReturn)
}

class GetPhotoMetadataUseCaseTest {

    @Test
    fun `returns the photo when it exists`() = runBlocking {
        val photo = Photo(
            mediaStoreId = 5L, uri = "content://5", filename = "a.jpg", mimeType = "image/jpeg",
            sizeBytes = 100L, widthPx = 10, heightPx = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
            dateTakenMs = null, latitude = null, longitude = null, orientationDegrees = 0,
        )
        val repository = FakePhotoRepository().apply { photoToReturn = photo }
        val useCase = GetPhotoMetadataUseCase(repository)

        val result = useCase(5L)

        assertTrue(result is AppResult.Success)
        assertEquals(photo, (result as AppResult.Success).value)
    }

    @Test
    fun `returns NotFound when no photo has that id`() = runBlocking {
        val repository = FakePhotoRepository()
        val useCase = GetPhotoMetadataUseCase(repository)

        val result = useCase(999L)

        assertTrue(result is AppResult.Failure)
    }
}
```

Check `Photo.kt`'s real constructor first and correct the fake `Photo(...)` calls to match exactly, same caveat as Task 1.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCaseTest"`
Expected: FAIL — `fetchById`/`fetchByIds` don't exist, `GetPhotoMetadataUseCase` doesn't exist.

- [ ] **Step 3: Implement**

Add to `PhotoRepository.kt` (inside the existing interface):

```kotlin
    /** A single photo by its MediaStore id, or null if it doesn't exist / was deleted. */
    suspend fun fetchById(mediaStoreId: Long): Photo?

    /** Every photo matching the given ids, in no particular order — ids with no match are omitted. */
    suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo>
```

`GetPhotoMetadataUseCase.kt`:

```kotlin
package com.localphotoai.photomanager.domain.photo

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult

class GetPhotoMetadataUseCase(
    private val photoRepository: PhotoRepository,
) {
    suspend operator fun invoke(mediaStoreId: Long): AppResult<Photo> {
        val photo = photoRepository.fetchById(mediaStoreId)
            ?: return AppResult.Failure(AppError.NotFound("No photo found with id $mediaStoreId"))
        return AppResult.Success(photo)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCaseTest"`
Expected: PASS, 2/2.

- [ ] **Step 5: Wire the Room/data-layer implementation (manual verification)**

Add to `PhotoDao.kt`:

```kotlin
    @Query("SELECT * FROM photos WHERE mediaStoreId = :mediaStoreId")
    suspend fun getById(mediaStoreId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun getByIds(mediaStoreIds: List<Long>): List<PhotoEntity>
```

Add to `PhotoRepositoryImpl.kt` (`:data:media`):

```kotlin
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photoDao.getById(mediaStoreId)?.toDomain()

    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> =
        photoDao.getByIds(mediaStoreIds).map { it.toDomain() }
```

- [ ] **Step 6: Manually verify**

Run: `./gradlew :domain:test :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL — every class implementing `PhotoRepository` (only `PhotoRepositoryImpl` and this test's fake) now compiles against the two new methods.

- [ ] **Step 7: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/photo data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/PhotoDao.kt data/media/src/main/kotlin/com/localphotoai/photomanager/data/media/PhotoRepositoryImpl.kt domain/src/test/kotlin/com/localphotoai/photomanager/domain/photo/GetPhotoMetadataUseCaseTest.kt
git commit -m "feat: add single/batch photo lookup by id for the get_photo_metadata tool"
```

---

### Task 3: `get_storage_statistics` support — `StorageStatistics`, `StorageStatisticsRepository`, Room implementation

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/statistics/StorageStatistics.kt`
- Test: `domain/src/test/kotlin/com/localphotoai/photomanager/domain/statistics/GetStorageStatisticsUseCaseTest.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/StatisticsDao.kt`
- Create: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/StorageStatisticsRepositoryImpl.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt`
- Modify: `data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt`

**Interfaces:**
- Produces: `data class StorageStatistics(photoCount: Int, totalSizeBytes: Long, peopleCount: Int, faceCount: Int, duplicateGroupCount: Int, similarGroupCount: Int)`, `interface StorageStatisticsRepository { suspend fun fetchStatistics(): StorageStatistics }`, `class GetStorageStatisticsUseCase(repository: StorageStatisticsRepository) { suspend operator fun invoke(): StorageStatistics }` — Task 5's `GetStorageStatisticsTool` calls this.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.localphotoai.photomanager.domain.statistics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeStorageStatisticsRepository(private val stats: StorageStatistics) : StorageStatisticsRepository {
    override suspend fun fetchStatistics(): StorageStatistics = stats
}

class GetStorageStatisticsUseCaseTest {

    @Test
    fun `returns exactly what the repository provides`() = runBlocking {
        val stats = StorageStatistics(
            photoCount = 328,
            totalSizeBytes = 1_200_000_000L,
            peopleCount = 5,
            faceCount = 12,
            duplicateGroupCount = 3,
            similarGroupCount = 7,
        )
        val useCase = GetStorageStatisticsUseCase(FakeStorageStatisticsRepository(stats))

        val result = useCase()

        assertEquals(stats, result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCaseTest"`
Expected: FAIL — package/classes don't exist.

- [ ] **Step 3: Implement**

`StorageStatistics.kt`:

```kotlin
package com.localphotoai.photomanager.domain.statistics

/** A snapshot of library-wide counts, for the `get_storage_statistics` tool (and, later, Phase
 * 11's diagnostics screen — this model is intentionally generic, not tool-specific). */
data class StorageStatistics(
    val photoCount: Int,
    val totalSizeBytes: Long,
    val peopleCount: Int,
    val faceCount: Int,
    val duplicateGroupCount: Int,
    val similarGroupCount: Int,
)

/** Access to aggregate library counts. Implemented in `:data:database` (Room `COUNT`/`SUM` queries only). */
interface StorageStatisticsRepository {
    suspend fun fetchStatistics(): StorageStatistics
}

class GetStorageStatisticsUseCase(
    private val repository: StorageStatisticsRepository,
) {
    suspend operator fun invoke(): StorageStatistics = repository.fetchStatistics()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:test --tests "com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCaseTest"`
Expected: PASS, 1/1.

- [ ] **Step 5: Wire the Room implementation (manual verification — no schema change, no migration needed)**

`StatisticsDao.kt`:

```kotlin
package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface StatisticsDao {
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun photoCount(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COUNT(*) FROM people")
    suspend fun peopleCount(): Int

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun faceCount(): Int

    @Query("SELECT COUNT(*) FROM duplicate_groups")
    suspend fun duplicateGroupCount(): Int

    @Query("SELECT COUNT(*) FROM similar_groups")
    suspend fun similarGroupCount(): Int
}
```

`StorageStatisticsRepositoryImpl.kt`:

```kotlin
package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.StatisticsDao
import com.localphotoai.photomanager.domain.statistics.StorageStatistics
import com.localphotoai.photomanager.domain.statistics.StorageStatisticsRepository
import javax.inject.Inject

class StorageStatisticsRepositoryImpl @Inject constructor(
    private val statisticsDao: StatisticsDao,
) : StorageStatisticsRepository {
    override suspend fun fetchStatistics(): StorageStatistics = StorageStatistics(
        photoCount = statisticsDao.photoCount(),
        totalSizeBytes = statisticsDao.totalSizeBytes(),
        peopleCount = statisticsDao.peopleCount(),
        faceCount = statisticsDao.faceCount(),
        duplicateGroupCount = statisticsDao.duplicateGroupCount(),
        similarGroupCount = statisticsDao.similarGroupCount(),
    )
}
```

In `AppDatabase.kt`: add `import com.localphotoai.photomanager.data.database.dao.StatisticsDao` and, inside the `abstract class AppDatabase`, add `abstract fun statisticsDao(): StatisticsDao`. No entity list change, no version bump, no migration — this DAO only reads existing tables.

In `DatabaseModule.kt`'s `object DatabaseModule`: add

```kotlin
    @Provides
    fun provideStatisticsDao(database: AppDatabase): StatisticsDao = database.statisticsDao()
```

In `DatabaseModule.kt`'s `abstract class RepositoryModule`: add

```kotlin
    @Binds
    @Singleton
    abstract fun bindStorageStatisticsRepository(impl: StorageStatisticsRepositoryImpl): StorageStatisticsRepository
```

and, in its `companion object`:

```kotlin
        @Provides
        fun provideGetStorageStatisticsUseCase(repository: StorageStatisticsRepository): GetStorageStatisticsUseCase =
            GetStorageStatisticsUseCase(repository)
```

(add the corresponding `import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase` / `StorageStatisticsRepository` at the top of the file).

- [ ] **Step 6: Manually verify**

Run: `./gradlew :domain:test :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL, no migration warning (schema unchanged).

- [ ] **Step 7: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/statistics domain/src/test/kotlin/com/localphotoai/photomanager/domain/statistics data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/dao/StatisticsDao.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/StorageStatisticsRepositoryImpl.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/AppDatabase.kt data/database/src/main/kotlin/com/localphotoai/photomanager/data/database/DatabaseModule.kt
git commit -m "feat: add storage statistics aggregation for the get_storage_statistics tool"
```

---

### Task 4: `:tools` module scaffold — domain tool models, `ToolRegistry`, `ToolValidator`

**Files:**
- Modify: `settings.gradle.kts`
- Create: `tools/build.gradle.kts`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool/ToolModels.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/ToolValidator.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/ToolRegistry.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/ToolValidatorTest.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/ToolRegistryTest.kt`

**Interfaces:**
- Produces: `enum class ToolName(val id: String)`, `data class ToolCall(tool: ToolName, people: List<String>, startDate: String?, endDate: String?, location: String?, sortBy: String?, photoId: Long?)`, `sealed class ToolOutcome { Photos, Metadata, Statistics, Error }`, `interface Tool { val name: ToolName; suspend fun execute(call: ToolCall): ToolOutcome }`, `class ToolRegistry(tools: List<Tool>) { suspend fun dispatch(call: ToolCall): ToolOutcome }`, `object ToolValidator { fun parseIsoDate(value: String?): AppResult<Long?>; fun parseSortOrder(value: String?): AppResult<PhotoSortOrder>; fun requirePhotoId(photoId: Long?): AppResult<Long> }` — Task 5's five `*Tool` implementations and Task 7's `ToolCallLoop` consume these.
- Consumes: `PhotoSortOrder` (Task 1), `AppResult`/`AppError` (`:core:common`, pre-existing).

- [ ] **Step 1: Add the `:tools` module**

In `settings.gradle.kts`, add `":tools",` to the `include(...)` list (alongside the existing modules).

`tools/build.gradle.kts` (pure Kotlin, no Android dependency — every tool only calls `:domain` interfaces):

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing tests**

`ToolValidatorTest.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolValidatorTest {

    @Test
    fun `parseIsoDate returns null for a null input`() {
        val result = ToolValidator.parseIsoDate(null)
        assertTrue(result is AppResult.Success)
        assertNull((result as AppResult.Success).value)
    }

    @Test
    fun `parseIsoDate parses a valid yyyy-MM-dd date to epoch millis`() {
        val result = ToolValidator.parseIsoDate("2025-01-01")
        assertTrue(result is AppResult.Success)
        assertEquals(1735689600000L, (result as AppResult.Success).value)
    }

    @Test
    fun `parseIsoDate rejects a malformed date`() {
        val result = ToolValidator.parseIsoDate("not-a-date")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseSortOrder defaults to NEWEST for a null input`() {
        val result = ToolValidator.parseSortOrder(null)
        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `parseSortOrder rejects an unrecognized value`() {
        val result = ToolValidator.parseSortOrder("biggest")
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `parseSortOrder accepts largest and smallest case-insensitively`() {
        assertTrue(ToolValidator.parseSortOrder("LARGEST") is AppResult.Success)
        assertTrue(ToolValidator.parseSortOrder("smallest") is AppResult.Success)
    }

    @Test
    fun `requirePhotoId rejects a null id`() {
        val result = ToolValidator.requirePhotoId(null)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `requirePhotoId accepts a non-null id`() {
        val result = ToolValidator.requirePhotoId(42L)
        assertTrue(result is AppResult.Success)
        assertEquals(42L, (result as AppResult.Success).value)
    }
}
```

`ToolRegistryTest.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTool(override val name: ToolName, private val outcome: ToolOutcome) : Tool {
    var callCount = 0
    override suspend fun execute(call: ToolCall): ToolOutcome {
        callCount++
        return outcome
    }
}

class ToolRegistryTest {

    @Test
    fun `dispatches to the tool matching the call's name`() = runBlocking {
        val statsOutcome = ToolOutcome.Error("unused")
        val statsTool = FakeTool(ToolName.GET_STORAGE_STATISTICS, statsOutcome)
        val dupTool = FakeTool(ToolName.FIND_DUPLICATES, ToolOutcome.Error("unused2"))
        val registry = ToolRegistry(listOf(statsTool, dupTool))

        val result = registry.dispatch(ToolCall(tool = ToolName.GET_STORAGE_STATISTICS))

        assertEquals(statsOutcome, result)
        assertEquals(1, statsTool.callCount)
        assertEquals(0, dupTool.callCount)
    }

    @Test
    fun `returns an error outcome when no tool matches`() = runBlocking {
        val registry = ToolRegistry(emptyList())

        val result = registry.dispatch(ToolCall(tool = ToolName.FIND_DUPLICATES))

        assertTrue(result is ToolOutcome.Error)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :tools:test`
Expected: FAIL to even compile — none of the production classes exist yet.

- [ ] **Step 4: Implement**

`domain/.../domain/tool/ToolModels.kt`:

```kotlin
package com.localphotoai.photomanager.domain.tool

import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.statistics.StorageStatistics

enum class ToolName(val id: String) {
    SEARCH_PHOTOS("search_photos"),
    FIND_DUPLICATES("find_duplicates"),
    FIND_SIMILAR_PHOTOS("find_similar_photos"),
    GET_PHOTO_METADATA("get_photo_metadata"),
    GET_STORAGE_STATISTICS("get_storage_statistics"),
    ;

    companion object {
        fun fromId(id: String): ToolName? = entries.find { it.id == id }
    }
}

/** A parsed, not-yet-validated tool invocation — every field beyond [tool] is optional because
 * the flat shape covers every tool uniformly (the grammar in `:llm:orchestration` only emits
 * fields relevant to the chosen [tool]; unused fields are simply absent/null). */
data class ToolCall(
    val tool: ToolName,
    val people: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String? = null,
    val sortBy: String? = null,
    val photoId: Long? = null,
)

sealed class ToolOutcome {
    data class Photos(val photos: List<Photo>, val message: String) : ToolOutcome()
    data class Metadata(val photo: Photo, val message: String) : ToolOutcome()
    data class Statistics(val statistics: StorageStatistics, val message: String) : ToolOutcome()
    data class Error(val message: String) : ToolOutcome()
}
```

`tools/.../tools/ToolValidator.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.search.PhotoSortOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Shared parameter validation for every `Tool` implementation — a hallucinated/malformed value
 * from the LLM must never reach a `:domain` use case, per ARCHITECTURE.md §19. */
object ToolValidator {

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    fun parseIsoDate(value: String?): AppResult<Long?> {
        if (value.isNullOrBlank()) return AppResult.Success(null)
        return try {
            AppResult.Success(isoFormat().parse(value)?.time)
        } catch (e: Exception) {
            AppResult.Failure(AppError.Validation("Invalid date \"$value\" — expected yyyy-MM-dd."))
        }
    }

    fun parseSortOrder(value: String?): AppResult<PhotoSortOrder> {
        if (value.isNullOrBlank()) return AppResult.Success(PhotoSortOrder.NEWEST)
        return when (value.uppercase(Locale.US)) {
            "NEWEST" -> AppResult.Success(PhotoSortOrder.NEWEST)
            "LARGEST" -> AppResult.Success(PhotoSortOrder.LARGEST)
            "SMALLEST" -> AppResult.Success(PhotoSortOrder.SMALLEST)
            else -> AppResult.Failure(AppError.Validation("Invalid sortBy \"$value\" — expected newest/largest/smallest."))
        }
    }

    fun requirePhotoId(photoId: Long?): AppResult<Long> {
        if (photoId == null) return AppResult.Failure(AppError.Validation("photoId is required."))
        return AppResult.Success(photoId)
    }
}
```

`tools/.../tools/ToolRegistry.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

interface Tool {
    val name: ToolName
    suspend fun execute(call: ToolCall): ToolOutcome
}

class ToolRegistry(tools: List<Tool>) {
    private val byName: Map<ToolName, Tool> = tools.associateBy { it.name }

    suspend fun dispatch(call: ToolCall): ToolOutcome =
        byName[call.tool]?.execute(call) ?: ToolOutcome.Error("Unknown tool: ${call.tool}")
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :tools:test`
Expected: PASS, all `ToolValidatorTest`/`ToolRegistryTest` cases green.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts tools domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool
git commit -m "feat: scaffold the :tools module with tool models, registry, and shared validation"
```

---

### Task 5: Tool implementations — `SearchPhotosTool`, `FindDuplicatesTool`, `FindSimilarPhotosTool`, `GetPhotoMetadataTool`, `GetStorageStatisticsTool`

**Files:**
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/SearchPhotosTool.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/FindDuplicatesTool.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/FindSimilarPhotosTool.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/GetPhotoMetadataTool.kt`
- Create: `tools/src/main/kotlin/com/localphotoai/photomanager/tools/GetStorageStatisticsTool.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/SearchPhotosToolTest.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/FindDuplicatesToolTest.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/GetPhotoMetadataToolTest.kt`
- Test: `tools/src/test/kotlin/com/localphotoai/photomanager/tools/GetStorageStatisticsToolTest.kt`

**Interfaces:**
- Produces: five `Tool` implementations, each a plain-constructor class over existing `:domain` interfaces (no `@Inject` — see Task 4's module note) — Task 9's `RuntimeModule` provides all five and constructs the `ToolRegistry` from them.
- Consumes: `SearchPhotosUseCase.searchOnce` (Task 1), `PersonRepository.observePeopleWithStats` (pre-existing), `PhotoGroupRepository.observeDuplicateGroups`/`observeSimilarGroups` (pre-existing, Phase 7), `PhotoRepository.fetchByIds`/`GetPhotoMetadataUseCase` (Task 2), `GetStorageStatisticsUseCase` (Task 3), `ToolValidator`/`ToolRegistry`/`Tool` (Task 4).

The one place free text can go silently wrong is resolving a person's name — `SearchPhotosTool` does a case-insensitive exact match against the current people list and returns an explicit `ToolOutcome.Error` (not an empty/wrong result) when a name doesn't resolve, per the spec's §4.

- [ ] **Step 1: Write the failing tests**

`SearchPhotosToolTest.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.search.SearchRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, widthPx = 10, heightPx = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = null, latitude = null, longitude = null, orientationDegrees = 0,
)

private class FakeSearchRepository(private val results: List<Photo>) : SearchRepository {
    var lastFilter: PhotoSearchFilter? = null
    override fun observeSearchResults(filter: PhotoSearchFilter) = flowOf(androidx.paging.PagingData.empty<Photo>())
    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        lastFilter = filter
        return results
    }
}

private class FakePersonRepository(private val people: List<PersonWithStats>) : PersonRepository {
    override fun observePeopleWithStats(): Flow<List<PersonWithStats>> = flowOf(people)
    override fun observeMembers(personId: Long) = flowOf(emptyList<com.localphotoai.photomanager.domain.person.PersonMember>())
    override suspend fun fetchFacesNeedingClustering() = emptyList<com.localphotoai.photomanager.domain.person.FaceEmbeddingForClustering>()
    override suspend fun fetchExistingClusters() = emptyList<com.localphotoai.photomanager.domain.person.ExistingClusterCentroid>()
    override suspend fun applyClusteringResult(faces: List<com.localphotoai.photomanager.domain.person.FaceEmbeddingForClustering>, result: com.localphotoai.photomanager.domain.person.ClusteringResult) {}
    override suspend fun namePerson(personId: Long, name: String?) {}
    override suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long) {}
    override suspend fun splitFaceIntoNewPerson(faceId: Long): Long = 0
    override suspend fun markFaceIncorrect(faceId: Long) {}
    override fun observeClusteringProgress() = flowOf(com.localphotoai.photomanager.domain.photo.IndexingProgress.IDLE)
    override suspend fun updateClusteringProgress(progress: com.localphotoai.photomanager.domain.photo.IndexingProgress) {}
}

private fun person(id: Long, name: String?) = PersonWithStats(
    id = id, name = name, representativePhotoUri = null, createdAt = 0L,
    clusterAlgoVersion = 1, photoCount = 0, faceCount = 0, averageConfidence = 1f,
)

class SearchPhotosToolTest {

    @Test
    fun `resolves a matching person name case-insensitively`() = runBlocking {
        val searchRepository = FakeSearchRepository(listOf(testPhoto(1)))
        val personRepository = FakePersonRepository(listOf(person(7L, "Rahul")))
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, people = listOf("rahul")))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(setOf(7L), searchRepository.lastFilter?.personIds)
    }

    @Test
    fun `returns an explicit error when a person name doesn't resolve`() = runBlocking {
        val searchRepository = FakeSearchRepository(emptyList())
        val personRepository = FakePersonRepository(listOf(person(7L, "Priya")))
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, people = listOf("Rahul")))

        assertTrue(result is ToolOutcome.Error)
        assertTrue((result as ToolOutcome.Error).message.contains("Rahul"))
    }

    @Test
    fun `supports a person-less query for size sorting`() = runBlocking {
        val searchRepository = FakeSearchRepository(listOf(testPhoto(1), testPhoto(2)))
        val personRepository = FakePersonRepository(emptyList())
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, sortBy = "largest"))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(2, (result as ToolOutcome.Photos).photos.size)
        assertEquals(com.localphotoai.photomanager.domain.search.PhotoSortOrder.LARGEST, searchRepository.lastFilter?.sortBy)
    }

    @Test
    fun `rejects an invalid sortBy value`() = runBlocking {
        val tool = SearchPhotosTool(SearchPhotosUseCase(FakeSearchRepository(emptyList())), FakePersonRepository(emptyList()))

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, sortBy = "biggest"))

        assertTrue(result is ToolOutcome.Error)
    }
}
```

Check `PersonRepository`'s exact interface and the exact constructor fields of `PersonWithStats`/`FaceEmbeddingForClustering`/`ExistingClusterCentroid`/`ClusteringResult` first (`domain/src/main/kotlin/com/localphotoai/photomanager/domain/person/`) and correct the fake above to match — it must implement every member of the real interface.

`GetPhotoMetadataToolTest.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePhotoRepository(private val photo: Photo?) : PhotoRepository {
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
    override suspend fun fetchById(mediaStoreId: Long): Photo? = photo
    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> = listOfNotNull(photo)
}

class GetPhotoMetadataToolTest {

    @Test
    fun `rejects a missing photoId`() = runBlocking {
        val tool = GetPhotoMetadataTool(GetPhotoMetadataUseCase(FakePhotoRepository(null)))
        val result = tool.execute(ToolCall(tool = ToolName.GET_PHOTO_METADATA))
        assertTrue(result is ToolOutcome.Error)
    }

    @Test
    fun `reports a not-found photoId as an error, not a crash`() = runBlocking {
        val tool = GetPhotoMetadataTool(GetPhotoMetadataUseCase(FakePhotoRepository(null)))
        val result = tool.execute(ToolCall(tool = ToolName.GET_PHOTO_METADATA, photoId = 999L))
        assertTrue(result is ToolOutcome.Error)
    }
}
```

`FindDuplicatesToolTest.kt` and `GetStorageStatisticsToolTest.kt` follow the same fake-repository pattern against `PhotoGroupRepository`/`PhotoRepository` and `StorageStatisticsRepository` respectively — write one success-path test per tool asserting the returned `ToolOutcome` variant and that the underlying repository/use-case was actually invoked, matching the two tests above in shape.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :tools:test`
Expected: FAIL — the five `Tool` implementations don't exist yet.

- [ ] **Step 3: Implement**

`SearchPhotosTool.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

private const val SEARCH_RESULT_LIMIT = 200

/** Plain constructor, not `@Inject` — `:tools` is a plain-Kotlin module with no Hilt plugin
 * applied (matching `:domain`'s existing convention); `:llm:runtime`'s `RuntimeModule` (Task 9)
 * wires this via `@Provides`. */
class SearchPhotosTool(
    private val searchPhotosUseCase: SearchPhotosUseCase,
    private val personRepository: PersonRepository,
) : Tool {
    override val name = ToolName.SEARCH_PHOTOS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val personIds = mutableSetOf<Long>()
        if (call.people.isNotEmpty()) {
            val people = personRepository.observePeopleWithStats().first()
            for (queryName in call.people) {
                val match = people.firstOrNull { it.name?.equals(queryName, ignoreCase = true) == true }
                    ?: return ToolOutcome.Error("No person found matching \"$queryName\".")
                personIds += match.id
            }
        }

        val startDateMs = when (val r = ToolValidator.parseIsoDate(call.startDate)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        val endDateMs = when (val r = ToolValidator.parseIsoDate(call.endDate)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        val sortOrder = when (val r = ToolValidator.parseSortOrder(call.sortBy)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }

        val filter = PhotoSearchFilter(
            personIds = personIds,
            startDateMs = startDateMs,
            endDateMs = endDateMs,
            sortBy = sortOrder,
        )

        return when (val result = searchPhotosUseCase.searchOnce(filter, limit = SEARCH_RESULT_LIMIT)) {
            is AppResult.Success -> ToolOutcome.Photos(result.value, buildMessage(result.value.size, call))
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }

    private fun buildMessage(count: Int, call: ToolCall): String {
        val who = if (call.people.isNotEmpty()) " of ${call.people.joinToString(" and ")}" else ""
        return if (count == 0) "No photos found$who." else "Found $count photo${if (count == 1) "" else "s"}$who."
    }
}
```

Note: `call.location` is intentionally not consumed yet — per the spec's §9 scope cut, free-text `location` resolution against a saved point requires `SettingsRepository`, which is deliberately left out of `:tools`' dependency set for this task; if a later task wires it in, extend this tool then. For now a `location` value in the call is accepted but has no effect (not an error) so the LLM isn't forced into a retry loop over a field this phase doesn't act on.

`FindDuplicatesTool.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

class FindDuplicatesTool(
    private val photoGroupRepository: PhotoGroupRepository,
    private val photoRepository: PhotoRepository,
) : Tool {
    override val name = ToolName.FIND_DUPLICATES

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val groups = photoGroupRepository.observeDuplicateGroups().first()
        val photoIds = groups.flatMap { it.photoIds }.distinct()
        val photos = photoRepository.fetchByIds(photoIds)
        val message = if (groups.isEmpty()) {
            "No duplicate photos found."
        } else {
            "Found ${groups.size} duplicate group${if (groups.size == 1) "" else "s"} (${photos.size} photos)."
        }
        return ToolOutcome.Photos(photos, message)
    }
}
```

`FindSimilarPhotosTool.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.similarity.SimilarGroupKind
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.first

class FindSimilarPhotosTool(
    private val photoGroupRepository: PhotoGroupRepository,
    private val photoRepository: PhotoRepository,
) : Tool {
    override val name = ToolName.FIND_SIMILAR_PHOTOS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val groups = photoGroupRepository.observeSimilarGroups(SimilarGroupKind.VISUALLY_SIMILAR).first()
        val photoIds = groups.flatMap { it.photoIds }.distinct()
        val photos = photoRepository.fetchByIds(photoIds)
        val message = if (groups.isEmpty()) {
            "No visually similar photo groups found."
        } else {
            "Found ${groups.size} visually similar group${if (groups.size == 1) "" else "s"} (${photos.size} photos)."
        }
        return ToolOutcome.Photos(photos, message)
    }
}
```

`GetPhotoMetadataTool.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class GetPhotoMetadataTool(
    private val getPhotoMetadataUseCase: GetPhotoMetadataUseCase,
) : Tool {
    override val name = ToolName.GET_PHOTO_METADATA

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val photoId = when (val r = ToolValidator.requirePhotoId(call.photoId)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return ToolOutcome.Error(r.error.message)
        }
        return when (val result = getPhotoMetadataUseCase(photoId)) {
            is AppResult.Success -> ToolOutcome.Metadata(result.value, "Found photo ${result.value.filename}.")
            is AppResult.Failure -> ToolOutcome.Error(result.error.message)
        }
    }
}
```

`GetStorageStatisticsTool.kt`:

```kotlin
package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome

class GetStorageStatisticsTool(
    private val getStorageStatisticsUseCase: GetStorageStatisticsUseCase,
) : Tool {
    override val name = ToolName.GET_STORAGE_STATISTICS

    override suspend fun execute(call: ToolCall): ToolOutcome {
        val stats = getStorageStatisticsUseCase()
        val message = "${stats.photoCount} photos, ${stats.peopleCount} people, " +
            "${stats.duplicateGroupCount} duplicate group(s), ${stats.similarGroupCount} similar group(s)."
        return ToolOutcome.Statistics(stats, message)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :tools:test`
Expected: PASS, all cases green.

- [ ] **Step 5: Commit**

```bash
git add tools/src
git commit -m "feat: implement the five controlled tools over existing search/duplicate/stats use cases"
```

---

### Task 6: `:llm:orchestration` — GBNF grammar generator and LLM-output JSON parser (pure, no native dependency)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml` (add kotlinx-serialization)
- Create: `llm/orchestration/build.gradle.kts`
- Create: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/GrammarBuilder.kt`
- Create: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallParser.kt`
- Test: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/GrammarBuilderTest.kt`
- Test: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallParserTest.kt`

**Interfaces:**
- Produces: `object GrammarBuilder { fun build(): String }` (the full GBNF grammar for all five tools), `object ToolCallParser { fun parse(rawJson: String): AppResult<ToolCall> }` — Task 7's `ToolCallLoop` consumes both.
- Consumes: `ToolName`/`ToolCall` (Task 4).

- [ ] **Step 1: Add the module and dependency**

In `gradle/libs.versions.toml`, add under `[versions]` (check Maven Central for the actual current stable release compatible with Kotlin 2.3.20 before pinning — do not assume the version below is still latest):

```toml
kotlinxSerializationJson = "1.9.0"
```

under `[libraries]`:

```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
```

under `[plugins]`:

```toml
kotlin-plugin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In `settings.gradle.kts`, add `":llm:orchestration",` and `":llm:runtime",` to `include(...)` (both are needed together since `AppDatabase`-style module registration in `settings.gradle.kts` happens once — add both now even though Task 8 implements `:llm:runtime`'s contents).

`llm/orchestration/build.gradle.kts` (pure Kotlin — no Android dependency; this is the whole point of keeping orchestration testable on the JVM):

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":tools"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing tests**

`GrammarBuilderTest.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    @Test
    fun `grammar references every tool id`() {
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.contains("\"search_photos\""))
        assertTrue(grammar.contains("\"find_duplicates\""))
        assertTrue(grammar.contains("\"find_similar_photos\""))
        assertTrue(grammar.contains("\"get_photo_metadata\""))
        assertTrue(grammar.contains("\"get_storage_statistics\""))
    }

    @Test
    fun `grammar declares a root rule`() {
        val grammar = GrammarBuilder.build()
        assertTrue(grammar.lineSequence().any { it.trim().startsWith("root ::=") })
    }

    @Test
    fun `grammar is stable across calls`() {
        assertTrue(GrammarBuilder.build() == GrammarBuilder.build())
    }
}
```

`ToolCallParserTest.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun `parses a valid search_photos call`() {
        val json = """{"tool":"search_photos","params":{"people":["Rahul"],"startDate":"2025-01-01","endDate":"2025-12-31"}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        val call = (result as AppResult.Success).value
        assertEquals(ToolName.SEARCH_PHOTOS, call.tool)
        assertEquals(listOf("Rahul"), call.people)
        assertEquals("2025-01-01", call.startDate)
    }

    @Test
    fun `parses a valid get_photo_metadata call with a numeric photoId`() {
        val json = """{"tool":"get_photo_metadata","params":{"photoId":42}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        assertEquals(42L, (result as AppResult.Success).value.photoId)
    }

    @Test
    fun `parses a valid no-parameter call`() {
        val json = """{"tool":"find_duplicates","params":{}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Success)
        assertEquals(ToolName.FIND_DUPLICATES, (result as AppResult.Success).value.tool)
    }

    @Test
    fun `rejects an unknown tool name`() {
        val json = """{"tool":"delete_everything","params":{}}"""
        val result = ToolCallParser.parse(json)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun `rejects malformed JSON without crashing`() {
        val result = ToolCallParser.parse("not json at all")
        assertTrue(result is AppResult.Failure)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :llm:orchestration:test`
Expected: FAIL — `GrammarBuilder`/`ToolCallParser` don't exist.

- [ ] **Step 4: Implement**

`GrammarBuilder.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

/**
 * Builds the GBNF grammar passed to llama.cpp's grammar-constrained sampler (`:llm:runtime`),
 * so the model's output is *structurally* guaranteed to be one of the five tool-call shapes —
 * see ARCHITECTURE.md §19 and the Phase 8 design spec §5. A malformed *value* inside a
 * syntactically-valid call (e.g. a hallucinated photoId) is still possible and is caught by
 * `:tools`' `ToolValidator`, not by this grammar.
 */
object GrammarBuilder {

    fun build(): String = """
        root ::= search-photos-call | find-duplicates-call | find-similar-photos-call | get-photo-metadata-call | get-storage-statistics-call

        search-photos-call ::= "{" ws "\"tool\":" ws "\"search_photos\"" "," ws "\"params\":" ws search-photos-params "}"
        search-photos-params ::= "{" ws (search-photos-field ("," ws search-photos-field)*)? ws "}"
        search-photos-field ::= people-field | start-date-field | end-date-field | location-field | sort-by-field
        people-field ::= "\"people\":" ws string-array
        start-date-field ::= "\"startDate\":" ws date-string
        end-date-field ::= "\"endDate\":" ws date-string
        location-field ::= "\"location\":" ws string
        sort-by-field ::= "\"sortBy\":" ws ("\"newest\"" | "\"largest\"" | "\"smallest\"")

        find-duplicates-call ::= "{" ws "\"tool\":" ws "\"find_duplicates\"" "," ws "\"params\":" ws "{" ws "}" ws "}"
        find-similar-photos-call ::= "{" ws "\"tool\":" ws "\"find_similar_photos\"" "," ws "\"params\":" ws "{" ws "}" ws "}"
        get-storage-statistics-call ::= "{" ws "\"tool\":" ws "\"get_storage_statistics\"" "," ws "\"params\":" ws "{" ws "}" ws "}"

        get-photo-metadata-call ::= "{" ws "\"tool\":" ws "\"get_photo_metadata\"" "," ws "\"params\":" ws "{" ws "\"photoId\":" ws number ws "}" ws "}"

        string-array ::= "[" ws (string ("," ws string)*)? ws "]"
        date-string ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] "\""
        string ::= "\"" ([^"\\])* "\""
        number ::= [0-9]+
        ws ::= [ \t\n]*
    """.trimIndent()
}
```

`ToolCallParser.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parses the grammar-constrained JSON `:llm:runtime` produces into a [ToolCall]. Grammar
 * constraints guarantee syntax, not tool-name/value correctness — both are still checked here. */
object ToolCallParser {

    fun parse(rawJson: String): AppResult<ToolCall> = try {
        val root = Json.parseToJsonElement(rawJson).jsonObjectOrNull()
            ?: return AppResult.Failure(AppError.Validation("Tool-call output was not a JSON object."))

        val toolId = root["tool"]?.jsonPrimitive?.content
            ?: return AppResult.Failure(AppError.Validation("Tool-call output had no \"tool\" field."))
        val tool = ToolName.fromId(toolId)
            ?: return AppResult.Failure(AppError.Validation("Unknown tool \"$toolId\"."))

        val params = (root["params"] as? JsonObject) ?: JsonObject(emptyMap())

        AppResult.Success(
            ToolCall(
                tool = tool,
                people = (params["people"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                startDate = params["startDate"]?.jsonPrimitive?.content,
                endDate = params["endDate"]?.jsonPrimitive?.content,
                location = params["location"]?.jsonPrimitive?.content,
                sortBy = params["sortBy"]?.jsonPrimitive?.content,
                photoId = params["photoId"]?.jsonPrimitive?.longOrNull,
            ),
        )
    } catch (e: Exception) {
        AppResult.Failure(AppError.Validation("Couldn't parse tool-call output: ${e.message}"))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :llm:orchestration:test`
Expected: PASS, all cases green.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml llm/orchestration
git commit -m "feat: add GBNF grammar generation and tool-call JSON parsing for local LLM output"
```

---

### Task 7: `:llm:orchestration` — `ToolCallLoop` (retry/fallback) and `TraceLogger`

**Files:**
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool/LlmEngine.kt`
- Create: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallLoop.kt`
- Create: `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/TraceLogger.kt`
- Test: `llm/orchestration/src/test/kotlin/com/localphotoai/photomanager/llm/orchestration/ToolCallLoopTest.kt`

**Interfaces:**
- Produces: `interface LlmEngine { suspend fun generate(prompt: String, grammar: String): String }` (`:domain`, implemented by Task 8's `LlamaCppEngine`), `sealed class SearchOutcome { Answered(outcome: ToolOutcome), Unavailable, Misunderstood }`, `class ToolCallLoop(engine: LlmEngine, toolRegistry: ToolRegistry, traceLogger: TraceLogger) { suspend fun run(query: String): SearchOutcome }` — Task 11's `SearchViewModel` calls `ToolCallLoop.run`.
- Consumes: `LlmEngine` (this task), `ToolRegistry`/`ToolOutcome` (Task 4), `GrammarBuilder`/`ToolCallParser` (Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import com.localphotoai.photomanager.tools.Tool
import com.localphotoai.photomanager.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedEngine(private val responses: List<String>) : LlmEngine {
    var callCount = 0
    override suspend fun generate(prompt: String, grammar: String): String {
        val response = responses[callCount.coerceAtMost(responses.size - 1)]
        callCount++
        return response
    }
}

private class FakeTraceLogger : TraceLogger {
    val events = mutableListOf<String>()
    override fun logQuery(query: String) { events += "query" }
    override fun logIntent(call: ToolCall) { events += "intent" }
    override fun logValidation(ok: Boolean, error: String?) { events += "validation" }
    override fun logToolResult(toolName: String, resultCount: Int, durationMs: Long) { events += "tool_result" }
    override fun logResponse(message: String, totalLatencyMs: Long) { events += "response" }
}

private class StatsTool : Tool {
    override val name = ToolName.GET_STORAGE_STATISTICS
    var callCount = 0
    override suspend fun execute(call: ToolCall): ToolOutcome {
        callCount++
        return ToolOutcome.Statistics(
            com.localphotoai.photomanager.domain.statistics.StorageStatistics(1, 1L, 1, 1, 0, 0),
            "1 photo",
        )
    }
}

class ToolCallLoopTest {

    @Test
    fun `a well-formed response is dispatched on the first try`() = runBlocking {
        val engine = ScriptedEngine(listOf("""{"tool":"get_storage_statistics","params":{}}"""))
        val tool = StatsTool()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), FakeTraceLogger())

        val outcome = loop.run("how many photos do I have")

        assertTrue(outcome is SearchOutcome.Answered)
        assertEquals(1, tool.callCount)
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `malformed output is retried exactly once before falling back`() = runBlocking {
        val engine = ScriptedEngine(listOf("not json", "still not json"))
        val loop = ToolCallLoop(engine, ToolRegistry(emptyList()), FakeTraceLogger())

        val outcome = loop.run("asdf")

        assertTrue(outcome is SearchOutcome.Misunderstood)
        assertEquals(2, engine.callCount)
    }

    @Test
    fun `a corrected response on retry succeeds`() = runBlocking {
        val engine = ScriptedEngine(listOf("not json", """{"tool":"get_storage_statistics","params":{}}"""))
        val tool = StatsTool()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(tool)), FakeTraceLogger())

        val outcome = loop.run("how many photos")

        assertTrue(outcome is SearchOutcome.Answered)
        assertEquals(2, engine.callCount)
        assertEquals(1, tool.callCount)
    }

    @Test
    fun `every stage is traced`() = runBlocking {
        val engine = ScriptedEngine(listOf("""{"tool":"get_storage_statistics","params":{}}"""))
        val traceLogger = FakeTraceLogger()
        val loop = ToolCallLoop(engine, ToolRegistry(listOf(StatsTool())), traceLogger)

        loop.run("how many photos")

        assertEquals(listOf("query", "intent", "validation", "tool_result", "response"), traceLogger.events)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :llm:orchestration:test --tests "*.ToolCallLoopTest"`
Expected: FAIL — `LlmEngine`, `SearchOutcome`, `ToolCallLoop`, `TraceLogger` don't exist.

- [ ] **Step 3: Implement**

`domain/.../domain/tool/LlmEngine.kt`:

```kotlin
package com.localphotoai.photomanager.domain.tool

/** The on-device LLM's inference surface — deliberately minimal (prompt + grammar in, raw text
 * out) so the engine implementation (`:llm:runtime`) stays fully swappable, per ARCHITECTURE.md
 * §2's "LLM engine/model must be replaceable later" requirement. */
interface LlmEngine {
    suspend fun generate(prompt: String, grammar: String): String
}
```

`TraceLogger.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.domain.tool.ToolCall

/** Structured query -> intent -> validation -> tool-result -> response tracing, per the Phase 8
 * spec §7 — counts/ids only in tool-result logs, never filenames/paths/coordinates. */
interface TraceLogger {
    fun logQuery(query: String)
    fun logIntent(call: ToolCall)
    fun logValidation(ok: Boolean, error: String?)
    fun logToolResult(toolName: String, resultCount: Int, durationMs: Long)
    fun logResponse(message: String, totalLatencyMs: Long)
}
```

`ToolCallLoop.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import com.localphotoai.photomanager.tools.ToolRegistry

sealed class SearchOutcome {
    data class Answered(val outcome: ToolOutcome) : SearchOutcome()
    object Misunderstood : SearchOutcome()
}

private const val SYSTEM_PROMPT = """
You are a photo search assistant. Given the user's request, respond with exactly one JSON tool
call matching the grammar. Tools: search_photos (params: people, startDate, endDate, location,
sortBy), find_duplicates (no params), find_similar_photos (no params),
get_photo_metadata (params: photoId), get_storage_statistics (no params).
""".trimIndent()

class ToolCallLoop(
    private val engine: LlmEngine,
    private val toolRegistry: ToolRegistry,
    private val traceLogger: TraceLogger,
) {
    suspend fun run(query: String): SearchOutcome {
        val startedAt = System.currentTimeMillis()
        traceLogger.logQuery(query)

        val grammar = GrammarBuilder.build()
        var prompt = "$SYSTEM_PROMPT\n\nUser: $query"

        repeat(2) { attempt ->
            val raw = engine.generate(prompt, grammar)
            when (val parsed = ToolCallParser.parse(raw)) {
                is AppResult.Success -> {
                    traceLogger.logIntent(parsed.value)
                    traceLogger.logValidation(true, null)
                    val toolStartedAt = System.currentTimeMillis()
                    val outcome = toolRegistry.dispatch(parsed.value)
                    val toolDurationMs = System.currentTimeMillis() - toolStartedAt

                    if (outcome is ToolOutcome.Error) {
                        traceLogger.logValidation(false, outcome.message)
                        if (attempt == 0) {
                            prompt = "$SYSTEM_PROMPT\n\nUser: $query\nYour last response was invalid: " +
                                "${outcome.message} Try again."
                            return@repeat
                        }
                        traceLogger.logResponse(outcome.message, System.currentTimeMillis() - startedAt)
                        return SearchOutcome.Misunderstood
                    }

                    val resultCount = when (outcome) {
                        is ToolOutcome.Photos -> outcome.photos.size
                        is ToolOutcome.Metadata -> 1
                        is ToolOutcome.Statistics -> 1
                        is ToolOutcome.Error -> 0
                    }
                    traceLogger.logToolResult(parsed.value.tool.id, resultCount, toolDurationMs)

                    val message = when (outcome) {
                        is ToolOutcome.Photos -> outcome.message
                        is ToolOutcome.Metadata -> outcome.message
                        is ToolOutcome.Statistics -> outcome.message
                        is ToolOutcome.Error -> outcome.message
                    }
                    traceLogger.logResponse(message, System.currentTimeMillis() - startedAt)
                    return SearchOutcome.Answered(outcome)
                }
                is AppResult.Failure -> {
                    traceLogger.logValidation(false, parsed.error.message)
                    if (attempt == 0) {
                        prompt = "$SYSTEM_PROMPT\n\nUser: $query\nYour last response was invalid: " +
                            "${parsed.error.message} Reply with valid JSON matching the grammar."
                        return@repeat
                    }
                }
            }
        }

        traceLogger.logResponse("Couldn't understand that — try the filters above.", System.currentTimeMillis() - startedAt)
        return SearchOutcome.Misunderstood
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :llm:orchestration:test --tests "*.ToolCallLoopTest"`
Expected: PASS, all 4 cases green.

- [ ] **Step 5: Implement the real `TraceLogger` (manual verification, logcat-only per spec §7)**

Create `llm/orchestration/src/main/kotlin/com/localphotoai/photomanager/llm/orchestration/LogcatTraceLogger.kt`:

```kotlin
package com.localphotoai.photomanager.llm.orchestration

import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.tool.ToolCall

private const val TAG = "LlmTrace"

/** Plain constructor, not `@Inject` — see the note on `SearchPhotosTool` in Task 5; wired via
 * `@Provides`/`@Binds` in `:llm:runtime`'s `RuntimeModule` (Task 9). */
class LogcatTraceLogger(private val logger: Logger) : TraceLogger {
    override fun logQuery(query: String) = logger.debug(TAG, "query=\"$query\"")
    override fun logIntent(call: ToolCall) = logger.debug(
        TAG,
        "intent=${call.tool.id} params={people=${call.people}, startDate=${call.startDate}, " +
            "endDate=${call.endDate}, sortBy=${call.sortBy}, photoId=${call.photoId}}",
    )
    override fun logValidation(ok: Boolean, error: String?) =
        logger.debug(TAG, if (ok) "validation=OK" else "validation=FAILED error=$error")
    override fun logToolResult(toolName: String, resultCount: Int, durationMs: Long) =
        logger.debug(TAG, "tool_result tool=$toolName count=$resultCount durationMs=$durationMs")
    override fun logResponse(message: String, totalLatencyMs: Long) =
        logger.debug(TAG, "response=\"$message\" totalLatencyMs=$totalLatencyMs")
}
```

This is intentionally not unit-tested (it's a one-line-per-call adapter over `Logger`, already covered indirectly by `ToolCallLoopTest`'s `FakeTraceLogger` exercising every call site) — verified in Task 12 by grepping `adb logcat` for the `LlmTrace` tag during a real query.

- [ ] **Step 6: Commit**

```bash
git add domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool/LlmEngine.kt llm/orchestration
git commit -m "feat: add the tool-call retry/fallback loop and logcat tracing"
```

---

### Task 8: `:llm:runtime` — vendor llama.cpp, CMake/NDK build, JNI bridge, `LlamaCppEngine`

**This task is native/build infrastructure, not unit-testable — implement, then verify manually on a real build + emulator, per this project's established treatment of Phases 3/4/7's ML pipelines.**

**Files:**
- Create: `llm/runtime/build.gradle.kts`
- Create: `llm/runtime/src/main/AndroidManifest.xml`
- Create: `llm/runtime/src/main/cpp/CMakeLists.txt`
- Create: `llm/runtime/src/main/cpp/llm_jni.cpp`
- Create (vendored, not authored): `llm/runtime/src/main/cpp/llama.cpp/` (git submodule)
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/NativeLlamaBridge.kt`
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/LlamaCppEngine.kt`
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/ModelFileStore.kt`

**Interfaces:**
- Produces: `class LlamaCppEngine : LlmEngine` (implements Task 7's `LlmEngine`) — Task 9's DI module binds this; Task 12's on-device verification exercises it.
- Consumes: `LlmEngine` (Task 7).

- [ ] **Step 1: Vendor llama.cpp**

```bash
git submodule add https://github.com/ggml-org/llama.cpp.git llm/runtime/src/main/cpp/llama.cpp
cd llm/runtime/src/main/cpp/llama.cpp
git log -1 --format=%H  # record this commit hash in ARCHITECTURE.md's Phase 8 notes
cd -
```

Pin to a specific tagged release commit (check the repo's Releases page for the latest stable tag at implementation time — do not use a moving branch HEAD) rather than `main`, so the native build is reproducible.

- [ ] **Step 2: Verify/install the NDK**

```bash
sdkmanager --sdk_root=~/Library/Android/sdk --list_installed | grep -i ndk
```

If none is installed: `sdkmanager --sdk_root=~/Library/Android/sdk "ndk;27.2.12479018"` (check `sdkmanager --list` for the current recommended stable NDK version first — do not assume this exact version string is still current).

- [ ] **Step 3: `llm/runtime/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localphotoai.photomanager.llm.runtime"
    compileSdk = 37
    ndkVersion = "27.2.12479018" // verify this matches the NDK actually installed in Step 2

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.0" // verify against `cmake --version` / the installed SDK CMake package
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":tools"))
    implementation(project(":llm:orchestration"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

`:tools` and `:llm:orchestration` are plain `kotlin.jvm` modules (no Hilt plugin applied, kept lightweight/testable) — matching this project's existing convention where `:domain`'s classes are never `@Inject`-annotated directly; instead a downstream Android+Hilt module wires them via `@Provides` (see `DatabaseModule.kt`'s `provideSearchPhotosUseCase`). `:llm:runtime` is that downstream wiring module for every Phase 8 type, which is why it depends on both.

- [ ] **Step 4: `llm/runtime/src/main/AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 5: `llm/runtime/src/main/cpp/CMakeLists.txt`**

```cmake
cmake_minimum_required(VERSION 3.22)
project(llm_jni)

# Trim llama.cpp's build to only what we need: no server, no CLI examples, no tests.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(llm_jni SHARED llm_jni.cpp)

find_library(log-lib log)

target_link_libraries(llm_jni
    llama
    ggml
    ${log-lib}
)

target_include_directories(llm_jni PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)
```

Adjust the `target_include_directories` paths if the vendored commit's actual header layout differs (verify with `find llm/runtime/src/main/cpp/llama.cpp -name "llama.h"` after Step 1) — the exact include paths can shift between llama.cpp releases.

- [ ] **Step 6: `llm/runtime/src/main/cpp/llm_jni.cpp`**

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "llm_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct EngineHandle {
    llama_model* model;
    llama_context* ctx;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeLoadModel(
    JNIEnv* env, jobject /* this */, jstring modelPath, jint contextSize) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params modelParams = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(contextSize);
    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto* handle = new EngineHandle{model, ctx};
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeGenerateWithGrammar(
    JNIEnv* env, jobject /* this */, jlong handlePtr, jstring prompt, jstring grammarText, jint maxTokens) {
    auto* handle = reinterpret_cast<EngineHandle*>(handlePtr);
    if (handle == nullptr) return env->NewStringUTF("");

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    const char* grammarChars = env->GetStringUTFChars(grammarText, nullptr);
    std::string promptStr(promptChars);
    std::string grammarStr(grammarChars);
    env->ReleaseStringUTFChars(prompt, promptChars);
    env->ReleaseStringUTFChars(grammarText, grammarChars);

    const llama_vocab* vocab = llama_model_get_vocab(handle->model);

    std::vector<llama_token> tokens(promptStr.size() + 16);
    int nTokens = llama_tokenize(vocab, promptStr.c_str(), promptStr.size(),
                                  tokens.data(), tokens.size(), true, true);
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return env->NewStringUTF("");
    }

    llama_sampler* grammarSampler = llama_sampler_init_grammar(vocab, grammarStr.c_str(), "root");
    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, grammarSampler);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    std::string result;
    for (int i = 0; i < maxTokens; i++) {
        llama_token nextToken = llama_sampler_sample(chain, handle->ctx, -1);
        if (llama_vocab_is_eog(vocab, nextToken)) break;

        char buf[256];
        int len = llama_token_to_piece(vocab, nextToken, buf, sizeof(buf), 0, true);
        if (len > 0) result.append(buf, len);

        llama_batch nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(handle->ctx, nextBatch) != 0) {
            LOGE("llama_decode failed mid-generation");
            break;
        }
    }

    llama_sampler_free(chain);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_localphotoai_photomanager_llm_runtime_NativeLlamaBridge_nativeFreeModel(
    JNIEnv* env, jobject /* this */, jlong handlePtr) {
    auto* handle = reinterpret_cast<EngineHandle*>(handlePtr);
    if (handle == nullptr) return;
    llama_free(handle->ctx);
    llama_model_free(handle->model);
    delete handle;
}
```

**Note for the implementer:** llama.cpp's public C API changes between releases (function names like `llama_model_load_from_file` vs. the older `llama_load_model_from_file`, `llama_sampler_init_greedy` availability, etc. have moved before). Cross-check every function signature above against the actual vendored commit's `llama.cpp/include/llama.h` before compiling, and adjust to match — this is expected integration work, not a sign the plan is wrong, the same way Phase 4's real NNAPI delegate bug required adapting to actual runtime behavior rather than the original design's assumption.

- [ ] **Step 7: `NativeLlamaBridge.kt`**

```kotlin
package com.localphotoai.photomanager.llm.runtime

internal object NativeLlamaBridge {
    init {
        System.loadLibrary("llm_jni")
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int): Long
    external fun nativeGenerateWithGrammar(handle: Long, prompt: String, grammar: String, maxTokens: Int): String
    external fun nativeFreeModel(handle: Long)
}
```

- [ ] **Step 8: `ModelFileStore.kt`** (mirrors `:ml:embeddings`'s `ModelFileStore` exactly)

```kotlin
package com.localphotoai.photomanager.llm.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelFileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    val modelFile: File get() = File(modelsDir, Llama32ModelSpec.FILENAME)

    fun tempFile(): File = File(modelsDir, "${Llama32ModelSpec.FILENAME}.download")

    fun isModelPresent(): Boolean = modelFile.exists() && modelFile.length() > 0
}
```

- [ ] **Step 9: `LlamaCppEngine.kt`**

```kotlin
package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.domain.tool.LlmEngine
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CONTEXT_SIZE = 2048
private const val MAX_RESPONSE_TOKENS = 256

/** Lazily loads the model on first use and serializes calls — native llama.cpp contexts aren't
 * safely shared across concurrent calls, per ARCHITECTURE.md §18. */
@Singleton
class LlamaCppEngine @Inject constructor(
    private val modelFileStore: ModelFileStore,
) : LlmEngine {

    private val mutex = Mutex()
    private val handle = AtomicLong(0)

    override suspend fun generate(prompt: String, grammar: String): String = mutex.withLock {
        ensureLoaded()
        val currentHandle = handle.get()
        if (currentHandle == 0L) return ""
        NativeLlamaBridge.nativeGenerateWithGrammar(currentHandle, prompt, grammar, MAX_RESPONSE_TOKENS)
    }

    private fun ensureLoaded() {
        if (handle.get() != 0L) return
        if (!modelFileStore.isModelPresent()) return
        handle.set(NativeLlamaBridge.nativeLoadModel(modelFileStore.modelFile.absolutePath, CONTEXT_SIZE))
    }
}
```

- [ ] **Step 10: Manually verify the native build compiles**

Run: `./gradlew :llm:runtime:assembleDebug`
Expected: BUILD SUCCESSFUL, and `llm/runtime/build/outputs/aar/runtime-debug.aar` (or the equivalent intermediate `.so` output directory) contains `libllm_jni.so` for `arm64-v8a` — verify with:

```bash
find llm/runtime/build -name "libllm_jni.so"
```

Fix any compile errors against the real `llama.h` API from Step 6's note before proceeding — this step will very likely need iteration; that's expected native-integration work, not a plan error.

- [ ] **Step 11: Manually verify on-device**

Run `./gradlew :app:assembleDebug` (after Task 9/10 wire this module into the app's dependency graph) and confirm `libllm_jni.so` is present in the built APK:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libllm_jni
```

Full inference verification (grammar producing a real tool call end-to-end) happens in Task 12 once a real model is downloaded — this task's gate is "the native library builds, links, and packages correctly."

- [ ] **Step 12: Commit**

```bash
git submodule status llm/runtime/src/main/cpp/llama.cpp  # confirm the pinned commit
git add .gitmodules llm/runtime
git commit -m "feat: vendor llama.cpp and build the JNI inference bridge with grammar-constrained decoding"
```

Record the vendored commit hash and any API-signature deviations you had to make in Step 6 in `ARCHITECTURE.md`'s Phase 8 notes section (following the existing per-phase "Implementation Notes" convention), the same way Phase 4 §36 documented its real NNAPI bug.

---

### Task 9: Model spec, download, and license verification (mirrors Phase 4's `HttpModelDownloader` pattern)

**Files:**
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/Llama32ModelSpec.kt`
- Create: `domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool/LlmModelDownloader.kt`
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/HttpLlmModelDownloader.kt`
- Create: `llm/runtime/src/main/kotlin/com/localphotoai/photomanager/llm/runtime/RuntimeModule.kt`
- Modify: `app/src/main/AndroidManifest.xml` (only if `INTERNET` isn't already unconditionally present — check first; Phase 4 already added it)

**Interfaces:**
- Produces: `interface LlmModelDownloader { fun observeDownloadState(): StateFlow<LlmModelDownloadState>; suspend fun downloadModel() }` (`:domain`), `LlmModelDownloadState` sealed class (NotDownloaded/Downloading/Ready/Failed) — Task 10's Settings UI consumes this.
- Consumes: `ModelFileStore` (Task 8).

- [ ] **Step 1: Formally verify the model's license and pin its download source**

Before writing any code, do the same web/API-based verification Phase 4 §33 did for FaceNet:

```bash
gh api repos/ggml-org/llama.cpp --jq .license  # sanity-check the runtime's own license (MIT), not the model's
```

Then, using a web search / the model host's own license page (Meta's official Llama 3.2 GGUF release, or a well-known permissively-mirrored GGUF conversion such as bartowski's or unsloth's on Hugging Face — check which mirrors currently host an unauthenticated, directly-downloadable `Llama-3.2-1B-Instruct-Q4_K_M.gguf`), record:
- The exact download URL (must support unauthenticated direct HTTP GET — Hugging Face's `resolve/main/...` URLs typically do for public repos, but confirm no login/token gate applies to this specific file at the time of implementation).
- The file's SHA-256 (compute it after downloading once locally: `shasum -a 256 <file>`).
- The Meta Llama 3.2 Community License's actual terms (attribution requirement, acceptable-use policy URL, any restriction relevant to redistributing the weights via download-on-first-run to end users — not training a competing model, not the >700M-MAU commercial clause, which doesn't apply to this app).

Document all of this in `ARCHITECTURE.md`'s Phase 8 notes, in the same table/writeup format as §33.

- [ ] **Step 2: `Llama32ModelSpec.kt`**

```kotlin
package com.localphotoai.photomanager.llm.runtime

/** Llama-3.2-1B-Instruct, GGUF, Q4_K_M quantization — see ARCHITECTURE.md's Phase 8 notes for the
 * full license/provenance record (mirrors Phase 4 §33's FaceNet writeup). URL/hash below must be
 * filled in from Task 9 Step 1's verification, not left as placeholders. */
object Llama32ModelSpec {
    const val MODEL_VERSION = 1
    const val FILENAME = "llama32_1b_instruct_q4.gguf"
    const val DOWNLOAD_URL = "" // fill in from Step 1's verified, pinned URL
    const val SHA256 = "" // fill in from Step 1's computed hash — never leave blank in the merged code
    const val CONTEXT_SIZE = 2048
}
```

- [ ] **Step 3: `LlmModelDownloader.kt`** (`:domain`)

```kotlin
package com.localphotoai.photomanager.domain.tool

import kotlinx.coroutines.flow.StateFlow

sealed class LlmModelDownloadState {
    object NotDownloaded : LlmModelDownloadState()
    data class Downloading(val percent: Int) : LlmModelDownloadState()
    object Ready : LlmModelDownloadState()
    data class Failed(val reason: String) : LlmModelDownloadState()
}

interface LlmModelDownloader {
    fun observeDownloadState(): StateFlow<LlmModelDownloadState>
    suspend fun downloadModel()
}
```

- [ ] **Step 4: `HttpLlmModelDownloader.kt`** (`:llm:runtime` — byte-for-byte the same shape as `:ml:embeddings`'s `HttpModelDownloader`, just against `Llama32ModelSpec` and `LlmModelDownloadState`)

```kotlin
package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.core.common.AppDispatchers
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.tool.LlmModelDownloadState
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val TAG = "HttpLlmModelDownloader"

@Singleton
class HttpLlmModelDownloader @Inject constructor(
    private val modelFileStore: ModelFileStore,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : LlmModelDownloader {

    private val state = MutableStateFlow<LlmModelDownloadState>(
        if (modelFileStore.isModelPresent()) LlmModelDownloadState.Ready else LlmModelDownloadState.NotDownloaded,
    )

    override fun observeDownloadState(): StateFlow<LlmModelDownloadState> = state

    override suspend fun downloadModel() {
        if (modelFileStore.isModelPresent()) {
            state.value = LlmModelDownloadState.Ready
            return
        }
        state.value = LlmModelDownloadState.Downloading(0)
        withContext(dispatchers.io) {
            val tempFile = modelFileStore.tempFile()
            try {
                val connection = URL(Llama32ModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode} downloading model")
                }

                val totalBytes = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                state.value = LlmModelDownloadState.Downloading(percent.coerceIn(0, 100))
                            }
                        }
                    }
                }
                connection.disconnect()

                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(Llama32ModelSpec.SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    error("Downloaded model checksum mismatch (got $actualHash)")
                }

                if (!tempFile.renameTo(modelFileStore.modelFile)) {
                    error("Failed to finalize downloaded model file")
                }
                state.value = LlmModelDownloadState.Ready
                logger.info(TAG, "LLM model downloaded and verified ($downloadedBytes bytes)")
            } catch (t: Throwable) {
                tempFile.delete()
                logger.error(TAG, "LLM model download failed", t)
                state.value = LlmModelDownloadState.Failed(t.message ?: "Download failed")
            }
        }
    }
}
```

- [ ] **Step 5: `RuntimeModule.kt`** — the single Hilt wiring point for every Phase 8 type

Every class in `:tools` and `:llm:orchestration` uses a plain constructor (no `@Inject`, per the notes on `SearchPhotosTool`/`LogcatTraceLogger`), so `RuntimeModule` provides all of them explicitly. This is the same pattern `DatabaseModule.kt`'s companion object already uses for `:domain` use cases (e.g. `provideSearchPhotosUseCase`) — nothing new, just applied to more types at once because Phase 8 introduces more plain-Kotlin classes than any single prior phase.

```kotlin
package com.localphotoai.photomanager.llm.runtime

import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.photo.GetPhotoMetadataUseCase
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.PhotoGroupRepository
import com.localphotoai.photomanager.domain.statistics.GetStorageStatisticsUseCase
import com.localphotoai.photomanager.domain.tool.LlmEngine
import com.localphotoai.photomanager.domain.tool.LlmModelDownloader
import com.localphotoai.photomanager.llm.orchestration.LogcatTraceLogger
import com.localphotoai.photomanager.llm.orchestration.ToolCallLoop
import com.localphotoai.photomanager.llm.orchestration.TraceLogger
import com.localphotoai.photomanager.tools.FindDuplicatesTool
import com.localphotoai.photomanager.tools.FindSimilarPhotosTool
import com.localphotoai.photomanager.tools.GetPhotoMetadataTool
import com.localphotoai.photomanager.tools.GetStorageStatisticsTool
import com.localphotoai.photomanager.tools.SearchPhotosTool
import com.localphotoai.photomanager.tools.ToolRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RuntimeModule {

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LlamaCppEngine): LlmEngine

    @Binds
    @Singleton
    abstract fun bindLlmModelDownloader(impl: HttpLlmModelDownloader): LlmModelDownloader

    @Binds
    @Singleton
    abstract fun bindTraceLogger(impl: LogcatTraceLogger): TraceLogger

    companion object {
        @Provides
        fun provideLogcatTraceLogger(logger: Logger): LogcatTraceLogger = LogcatTraceLogger(logger)

        @Provides
        fun provideSearchPhotosTool(
            searchPhotosUseCase: SearchPhotosUseCase,
            personRepository: PersonRepository,
        ): SearchPhotosTool = SearchPhotosTool(searchPhotosUseCase, personRepository)

        @Provides
        fun provideFindDuplicatesTool(
            photoGroupRepository: PhotoGroupRepository,
            photoRepository: PhotoRepository,
        ): FindDuplicatesTool = FindDuplicatesTool(photoGroupRepository, photoRepository)

        @Provides
        fun provideFindSimilarPhotosTool(
            photoGroupRepository: PhotoGroupRepository,
            photoRepository: PhotoRepository,
        ): FindSimilarPhotosTool = FindSimilarPhotosTool(photoGroupRepository, photoRepository)

        @Provides
        fun provideGetPhotoMetadataTool(useCase: GetPhotoMetadataUseCase): GetPhotoMetadataTool =
            GetPhotoMetadataTool(useCase)

        @Provides
        fun provideGetStorageStatisticsTool(useCase: GetStorageStatisticsUseCase): GetStorageStatisticsTool =
            GetStorageStatisticsTool(useCase)

        @Provides
        @Singleton
        fun provideToolRegistry(
            searchPhotosTool: SearchPhotosTool,
            findDuplicatesTool: FindDuplicatesTool,
            findSimilarPhotosTool: FindSimilarPhotosTool,
            getPhotoMetadataTool: GetPhotoMetadataTool,
            getStorageStatisticsTool: GetStorageStatisticsTool,
        ): ToolRegistry = ToolRegistry(
            listOf(searchPhotosTool, findDuplicatesTool, findSimilarPhotosTool, getPhotoMetadataTool, getStorageStatisticsTool),
        )

        @Provides
        fun provideToolCallLoop(
            engine: LlmEngine,
            toolRegistry: ToolRegistry,
            traceLogger: TraceLogger,
        ): ToolCallLoop = ToolCallLoop(engine, toolRegistry, traceLogger)
    }
}
```

(`GetPhotoMetadataUseCase`/`GetStorageStatisticsUseCase` are themselves provided by `DatabaseModule`'s companion object from Tasks 2/3 — Dagger resolves them from there automatically since they're on the same `SingletonComponent` graph; nothing extra to add for those two.)

- [ ] **Step 6: Add `:app`'s dependency on `:llm:runtime`**

`:app`'s `build.gradle.kts` lists every other module it needs directly (`:data:database`, `:data:media`, every `:feature:*`, etc.) rather than relying on deep transitivity — add `:llm:runtime` to that same list:

```kotlin
    implementation(project(":llm:runtime"))
```

This is what puts `RuntimeModule` (and the native `libllm_jni.so`) on `:app`'s classpath so Hilt's component aggregation finds it and AGP packages the native library into the APK — without this, `ToolCallLoop`/`LlmEngine`/`ToolRegistry` would fail to resolve at Hilt-component-build time even though every individual module compiles fine on its own.

- [ ] **Step 7: Manually verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — every Hilt binding resolves (this is where a missing `@Provides`/`@Binds` or a Dagger cycle would surface, per §28's WorkManager+Hilt gotcha precedent). If `Llama32ModelSpec.DOWNLOAD_URL`/`SHA256` are still blank from Step 2, fill them in now from Step 1's research before this step — an empty download URL isn't a build error but will fail at runtime in Task 12.

- [ ] **Step 8: Commit**

```bash
git add llm/runtime domain/src/main/kotlin/com/localphotoai/photomanager/domain/tool/LlmModelDownloader.kt app/build.gradle.kts
git commit -m "feat: add Llama-3.2-1B model spec, verified download, and DI wiring for the tool registry"
```

---

### Task 10: Settings UI — "Download search assistant model"

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/com/localphotoai/photomanager/feature/settings/SettingsScreen.kt`
- Modify: `feature/settings/build.gradle.kts` (add `:llm:runtime`'s domain-facing dependency — actually only `:domain`'s `LlmModelDownloader` interface is needed, already reachable if `:feature:settings` depends on `:domain`; add a dependency on `:llm:runtime` only if Hilt needs the concrete binding visible, which it doesn't — `:app`'s dependency graph resolves it. Confirm `:feature:settings` already depends on `:domain`; if not, add `implementation(project(":domain"))`.)

**Interfaces:**
- Consumes: `LlmModelDownloader`/`LlmModelDownloadState` (Task 9).

- [ ] **Step 1: Add the download state and action to `SettingsViewModel`**

Add to the constructor: `private val llmModelDownloader: LlmModelDownloader,` and the corresponding import. Add:

```kotlin
    val llmModelDownloadState: StateFlow<LlmModelDownloadState> = llmModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmModelDownloadState.NotDownloaded)

    fun onDownloadLlmModelClicked() {
        viewModelScope.launch {
            llmModelDownloader.downloadModel()
        }
    }
```

- [ ] **Step 2: Add the UI section to `SettingsScreen.kt`**

In the existing "AI Models" section (alongside the pre-existing face-embedding-model download block), add a self-contained composable:

```kotlin
@Composable
private fun LlmModelDownloadSection(
    state: LlmModelDownloadState,
    onDownloadClicked: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Search assistant model", style = MaterialTheme.typography.titleSmall)
        Text(
            "Enables natural-language photo search (e.g. \"Show me photos of Rahul from 2025\"). Runs fully on-device.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        when (state) {
            is LlmModelDownloadState.NotDownloaded -> Button(onClick = onDownloadClicked) { Text("Download") }
            is LlmModelDownloadState.Downloading -> LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            is LlmModelDownloadState.Ready -> Text("Ready", style = MaterialTheme.typography.bodyMedium)
            is LlmModelDownloadState.Failed -> Column {
                Text("Failed: ${state.reason}", color = MaterialTheme.colorScheme.error)
                Button(onClick = onDownloadClicked) { Text("Retry") }
            }
        }
    }
}
```

Call it from the screen's composition, passing `viewModel.llmModelDownloadState.collectAsStateWithLifecycle().value` and `viewModel::onDownloadLlmModelClicked` — match whatever state-collection pattern the existing embedding-model section already uses in this file (`collectAsStateWithLifecycle` vs. `collectAsState`) rather than introducing a second pattern.

- [ ] **Step 3: Manually verify**

Build and run (`./gradlew :app:assembleDebug`, install to the Phase 1 AVD), open Settings, confirm the new "Search assistant model" section renders below the existing AI Models content, tap Download, and — once Task 9's URL/hash are filled in — confirm it streams and verifies for real, transitioning `NotDownloaded → Downloading (0-100%) → Ready`, the same verification Phase 4 did for FaceNet.

- [ ] **Step 4: Commit**

```bash
git add feature/settings
git commit -m "feat: add search-assistant model download UI to Settings"
```

---

### Task 11: Search screen — natural-language query box

**Files:**
- Modify: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/localphotoai/photomanager/feature/search/SearchScreen.kt`
- Modify: `feature/search/build.gradle.kts` (add `implementation(project(":llm:orchestration"))`, `implementation(project(":tools"))` if not already transitively sufficient — `ToolCallLoop`/`SearchOutcome` types are referenced directly in the ViewModel, so the dependency must be explicit, not relied on transitively)

**Interfaces:**
- Consumes: `ToolCallLoop`, `SearchOutcome` (Task 7), `LlmModelDownloader`/`LlmModelDownloadState` (Task 9), `ToolOutcome` (Task 4).

- [ ] **Step 1: Extend `SearchViewModel`**

Add to the constructor: `private val toolCallLoop: ToolCallLoop,` `llmModelDownloader: LlmModelDownloader,` plus imports. Add:

```kotlin
sealed class NlSearchUiState {
    object Idle : NlSearchUiState()
    object Loading : NlSearchUiState()
    data class Results(val photos: List<Photo>, val message: String) : NlSearchUiState()
    data class Message(val text: String) : NlSearchUiState()
}
```

(place this alongside `SearchFilterState` at the top of the file). Inside the `SearchViewModel` class:

```kotlin
    val llmModelDownloadState: StateFlow<LlmModelDownloadState> = llmModelDownloader.observeDownloadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmModelDownloadState.NotDownloaded)

    private val nlState = MutableStateFlow<NlSearchUiState>(NlSearchUiState.Idle)
    val nlSearchUiState: StateFlow<NlSearchUiState> = nlState.asStateFlow()

    fun onNlQuerySubmitted(query: String) {
        if (query.isBlank()) return
        nlState.value = NlSearchUiState.Loading
        viewModelScope.launch {
            when (val outcome = toolCallLoop.run(query)) {
                is SearchOutcome.Answered -> nlState.value = when (val result = outcome.outcome) {
                    is ToolOutcome.Photos -> NlSearchUiState.Results(result.photos, result.message)
                    is ToolOutcome.Metadata -> NlSearchUiState.Results(listOf(result.photo), result.message)
                    is ToolOutcome.Statistics -> NlSearchUiState.Message(result.message)
                    is ToolOutcome.Error -> NlSearchUiState.Message(result.message)
                }
                is SearchOutcome.Misunderstood -> nlState.value =
                    NlSearchUiState.Message("Couldn't understand that — try the filters below.")
            }
        }
    }
```

- [ ] **Step 2: Add the query box to `SearchScreen.kt`**

Add, above the existing person/date/location filter UI:

```kotlin
@Composable
private fun NlSearchBar(
    downloadState: LlmModelDownloadState,
    onQuerySubmitted: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    if (downloadState !is LlmModelDownloadState.Ready) {
        Text(
            "Natural-language search needs the search assistant model — download it in Settings.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Ask in plain English…") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(onClick = { onQuerySubmitted(query) }) {
            Icon(Icons.Default.Send, contentDescription = "Search")
        }
    }
}

@Composable
private fun NlSearchResultSection(state: NlSearchUiState) {
    when (state) {
        is NlSearchUiState.Idle -> Unit
        is NlSearchUiState.Loading -> Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is NlSearchUiState.Message -> Text(state.text, modifier = Modifier.padding(8.dp))
        is NlSearchUiState.Results -> Column {
            Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth()) {
                items(state.photos, key = { it.mediaStoreId }) { photo ->
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = photo.filename,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.aspectRatio(1f).padding(1.dp),
                    )
                }
            }
        }
    }
}
```

Call both from the main `SearchScreen` composable, collecting `viewModel.llmModelDownloadState` / `viewModel.nlSearchUiState` and passing `viewModel::onNlQuerySubmitted`, placed above the existing deterministic filter UI — match this file's existing state-collection helper (`collectAsStateWithLifecycle`) and Coil `AsyncImage` usage pattern (check how the existing paged grid renders thumbnails and reuse the same image-loading call shape, not a different one).

- [ ] **Step 3: Manually verify**

With the model downloaded (Task 10), run the app, go to Search, and try each of the plan's example queries one at a time, confirming a plausible result or an honest error each time:
- "Show me photos of Rahul." (assuming a person named "Rahul" exists in the test data, per Phase 5/6's verification approach — if not, use whatever named person exists in the current test database)
- "Show me photos of Rahul from 2025."
- "Find photos with Rahul and Priya." (or two real named people)
- "Find my largest photos."
- "Find duplicate photos."
- "Show me photos taken in Delhi." (expected: no location resolution this phase per spec §9 — verify it degrades to an unfiltered/person-less search rather than crashing, and note this as an expected, documented limitation, not a bug)

Also verify: submitting a query while the model isn't yet downloaded shows the disabled message from Step 2, not a crash; the existing deterministic filters below still work unmodified.

- [ ] **Step 4: Commit**

```bash
git add feature/search
git commit -m "feat: add natural-language query box to the Search screen"
```

---

### Task 12: End-to-end on-device verification

**No new files — this task is verification only, per this project's phase-gate convention (every phase ends with a real build+run check, not just green unit tests).**

- [ ] **Step 1: Full regression + build**

Run: `./gradlew :domain:test :tools:test :llm:orchestration:test :core:common:test`
Expected: PASS, zero regressions in the pre-existing 81+ tests plus every new test from Tasks 1-7.

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install and exercise on the project's standard AVD**

Install to the existing Android 15 (API 35) AVD used by every prior phase. Download the search-assistant model via Settings (real download, real SHA-256 verification — same rigor as Phase 4's FaceNet verification). Run each example query from Task 11 Step 3 through the actual UI.

- [ ] **Step 3: Verify tracing**

```bash
adb logcat -s LlmTrace:D
```

While submitting a query, confirm all five expected lines appear (query, intent, validation, tool_result, response) and that the `tool_result`/`response` lines never contain a filename, file path, or GPS coordinate — only counts, tool names, and IDs, per spec §7.

- [ ] **Step 4: Measure and record latency**

Time at least 3 real queries end-to-end (query submit → results rendered) via `adb logcat`'s `totalLatencyMs` field or a stopwatch. Record the measured range in this plan's Phase 8 entry in `ARCHITECTURE.md`/the master plan doc — do not claim a specific number without having measured it, per this project's "do not claim a performance win without the numbers to back it" principle (established in Phase 6's verification writeup).

- [ ] **Step 5: Verify graceful degradation**

Force-stop and clear the app's data (or use a fresh AVD state) so the model isn't downloaded, open Search, confirm the NL query box shows the "download it in Settings" message and the existing deterministic person/date/location filters remain fully functional — the core requirement that AI unavailability never blocks manual search.

- [ ] **Step 6: Write up the Known Limitations section**

Update the master plan (`docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md`)'s Phase 8 entry with: Status → Done, a "What was built" summary mirroring Phases 1-7's format, the verification performed (Steps 1-5 above with real numbers/output), and a "Known limitations" section covering at minimum: no free-text geocoding for `location` (spec §9), no multi-turn memory, no LLM-generated summary, single-`arm64-v8a`-only native build, and any grammar/prompt-tuning gaps discovered during real-model testing (e.g. if the 1B model occasionally still needs the one retry — record the actual observed rate, don't guess). Add the corresponding "Phase 8 — Implementation Notes" section to `ARCHITECTURE.md` documenting the vendored llama.cpp commit, the verified model license/URL/hash from Task 9 Step 1, and any llama.cpp API deviations from Task 8 Step 6.

This step produces documentation only — do not commit it automatically; per this project's standing instruction, ask before committing anything, including this final doc update.
