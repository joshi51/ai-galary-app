# Phase 6 — Search by People: Design Spec

Companion spec for [the plan](../plans/2026-08-29-local-ai-photo-manager.md)'s Phase 6 entry and [ARCHITECTURE.md](../../ARCHITECTURE.md). Produced 2026-08-30. Status: **Approved — ready for implementation planning.**

## 1. Scope

Deterministic (non-LLM) search for photos by the people in them, per the plan's Phase 6 deliverable:

- Photos containing a selected person.
- Photos containing multiple selected people (all of them together in the same photo).
- Person + date-range filtering.
- Person + location filtering (GPS bounding box), where location metadata exists on the photo.
- Paginated results, appropriate database indexes, verified against a simulated large photo library.

**Explicitly out of scope for this phase:** natural-language search (Phase 8), folder/album-based filtering (no folder/bucket column exists on `PhotoEntity` today — only `content://` URIs and EXIF GPS coordinates; adding folder capture is a separate, later change if ever needed), and any UI/query path that doesn't require at least one selected person (Phase 6 is people-search, not a generic "browse all photos" filter — the Photos tab already covers that).

## 2. Decisions locked by this spec

- **Multi-person semantics: AND (intersection).** "Show photos of Rahul and Priya" means a photo must contain every selected person, not any one of them.
- **Location filtering is GPS-bounding-box only**, using the existing nullable `latitude`/`longitude` columns on `PhotoEntity`. No folder/path-based filtering in this phase.
- **Pagination: Jetpack Paging 3.** New dependency (`androidx.paging:paging-runtime`, `androidx.paging:paging-compose`), using Room's built-in `PagingSource` support rather than manual `LIMIT`/`OFFSET` bookkeeping.
- **Query composition: one parameterized `@Query` per filter shape, using SQL's `(:param IS NULL OR column ...)` optional-filter pattern**, not `@RawQuery`/dynamic SQL strings and not a combinatorial set of per-filter-combination methods. This keeps every query compile-time validated by Room and unit/instrumented-testable.
- **Simulated large-dataset verification target: ~10,000 photos / ~5,000 faces**, synthetic rows inserted directly into Room (same technique as Phases 4/5), spread across a multi-year date range, multiple person clusters, and a scatter of lat/lon buckets.

## 3. Data model changes (`:data:database`)

No new tables. Index additions only — the existing `PhotoEntity`, `PersonEntity`, `PersonFaceEntity`, and `FaceEntity` schemas already carry every field this phase needs (`dateTakenMs`, `latitude`/`longitude` on `PhotoEntity`; `personId`/`faceId` on `PersonFaceEntity`; `photoId` on `FaceEntity`).

```kotlin
// PhotoEntity — currently has zero declared indexes
@Entity(
    tableName = "photos",
    indices = [
        Index("dateTakenMs"),
        Index("latitude", "longitude"),
    ]
)

// FaceEntity — add photoId index; it's the join key the multi-person
// subquery (below) actually filters/groups on, not just an FK
@Entity(
    tableName = "faces",
    indices = [Index("photoId")],
    foreignKeys = [/* existing photos FK unchanged */]
)

// PersonFaceEntity — already has Index("personId"); no change needed,
// since the multi-person subquery reads (personId, faceId) pairs and
// personId is already indexed for the IN (:personIds) filter
```

This requires a Room migration bump (current version 4 → 5) adding these three indexes — no data migration needed, index-only `ALTER`/`CREATE INDEX` statements.

## 4. Query design (`:data:database`)

New `SearchDao` (separate from `PhotoDao`/`PersonDao` — this is a distinct read-only query surface, not photo/person CRUD):

```kotlin
@Query("""
    SELECT p.* FROM photos p
    WHERE p.mediaStoreId IN (
        SELECT pf.photoId FROM (
            SELECT f.photoId AS photoId, pf.personId AS personId
            FROM person_faces pf
            INNER JOIN faces f ON f.id = pf.faceId
            WHERE pf.personId IN (:personIds)
        ) pf
        GROUP BY pf.photoId
        HAVING COUNT(DISTINCT pf.personId) = :personCount
    )
    AND (:startDateMs IS NULL OR p.dateTakenMs >= :startDateMs)
    AND (:endDateMs IS NULL OR p.dateTakenMs <= :endDateMs)
    AND (:minLat IS NULL OR p.latitude BETWEEN :minLat AND :maxLat)
    AND (:minLon IS NULL OR p.longitude BETWEEN :minLon AND :maxLon)
    ORDER BY p.dateTakenMs DESC, p.dateAddedMs DESC
""")
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
```

`personCount` is always `personIds.size`, passed explicitly since SQL can't call `COUNT()` on a bound list parameter directly. A photo with `dateTakenMs = NULL` never matches a date filter (correct: unknown date shouldn't satisfy a date-range predicate); same logic applies to `latitude`/`longitude` for the location filter.

## 5. Domain layer (`:domain`)

- `PhotoSearchFilter` (new, `domain.photo` or a new `domain.search` package): `personIds: Set<Long>`, `startDateMs: Long?`, `endDateMs: Long?`, `locationBoundingBox: BoundingBox?` (`minLat`, `maxLat`, `minLon`, `maxLon`).
- `SearchRepository` (new interface): `fun observeSearchResults(filter: PhotoSearchFilter): Flow<PagingData<Photo>>`. Depends only on `androidx.paging:paging-common` (pure Kotlin/JVM, no Android framework dependency — keeps `:domain` Android-free per its existing constraint).
- `SearchPhotosUseCase` (new): validates `filter.personIds.isNotEmpty()` (rejects an all-empty filter — this phase is people-search, not generic browse) and validates `startDateMs <= endDateMs` when both are set, then delegates to `SearchRepository.observeSearchResults`.
- `SearchRepositoryImpl` (`:data:database`): builds a `Pager(PagingConfig(pageSize = 30)) { searchDao.searchPhotos(...) }.flow`, mapping `PhotoEntity` → `Photo` per-page (reusing the existing mapper from §29/§25 of ARCHITECTURE.md history).

## 6. UI (`:feature:search`)

Replaces the current placeholder `SearchScreen`:

- **Person picker**: a horizontal or grid chip selector sourced from `PersonRepository.observePeopleWithStats()` (already exists, used by the People tab) — tap to toggle a person in/out of the filter's `personIds` set. Selected chips show a checked state.
- **Date range**: a collapsible section with quick-pick chips (e.g. "2025", "2024", "All time") plus a custom from/to date picker, writing into `startDateMs`/`endDateMs`.
- **Location toggle**: "Near a saved location" — off by default; when enabled, uses a user-configured point + radius (stored in `:data:preferences`, a small new DataStore key — no new table) to compute the bounding box passed into the filter.
- **Results**: a `LazyVerticalGrid` fed by `LazyPagingItems<Photo>` (via `.collectAsLazyPagingItems()` on the ViewModel's `Flow<PagingData<Photo>>.cachedIn(viewModelScope)`), visually consistent with `PhotosScreen`'s existing grid/thumbnail rendering.
- Changing any filter (person selection, date range, location toggle) re-triggers the `Pager` by re-deriving the `PhotoSearchFilter` and re-collecting.
- Empty state: no people selected → prompt to pick at least one person (no query issued). No results for a valid filter → distinct "no photos match" empty state.

`SearchViewModel` (new) holds filter state (person selection, date range, location toggle) and exposes `Flow<PagingData<Photo>>` plus a small UI-state (filter validity, empty-state flags) — standard MVVM, no special testing needed per the project's ViewModel-testing exclusion.

## 7. Testing (per project's business-logic-only testing preference)

- **`:domain` unit tests** (`SearchPhotosUseCaseTest`, `PhotoSearchFilterTest`): empty-person-set filter rejected without touching the repository; invalid date range (`start > end`) rejected; a valid filter delegates to the repository unchanged — all against a fake `SearchRepository`, no Android/Room dependency.
- **`SearchDao` instrumented/Room test** (in-memory Room DB, same pattern as any existing DAO-level test in this project — this is genuine SQL correctness, not UI/ViewModel glue, so it fits the "business logic" carve-out): 
  - Single person → correct photo set.
  - Multi-person AND → a photo with only one of the two selected people is correctly excluded; a photo with both is included.
  - Person + date range → photos outside the range excluded, including a photo with `dateTakenMs = NULL`.
  - Person + location bounding box → photos outside the box or with null lat/lon excluded.
  - Pagination → requesting page 2 returns the next page's distinct rows, not a repeat of page 1, and total count matches the unpaged query.

## 8. Performance verification gate

Using the same synthetic-data-insertion technique as Phases 4/5 (direct Room inserts, not through the real indexing/detection/embedding pipeline):

1. Insert ~10,000 synthetic `PhotoEntity` rows spread across a multi-year `dateTakenMs` range, ~5,000 synthetic `FaceEntity` rows, and `PersonFaceEntity` assignments across a handful of distinct person clusters (including some photos with 2+ people, to exercise the AND path), plus a scatter of lat/lon values across a subset of photos.
2. Measure query latency for: single-person, multi-person AND (2 and 3 people), person+date, person+location, and first-page vs. subsequent-page load time — **before** adding the §3 indexes (drop them temporarily or measure on a pre-migration snapshot) and **after**, to demonstrate the indexes are load-bearing, not just assumed.
3. Record results in the plan doc's Phase 6 entry (`docs/superpowers/plans/2026-08-29-local-ai-photo-manager.md`) once implemented, following the same format as Phases 2–5's "Verification performed" sections.

## 9. Migration notes

Room version 4 → 5, `MIGRATION_4_5`, adding three `CREATE INDEX` statements (§3) — no destructive fallback, no data changes, so this migration is low-risk and should apply cleanly to any existing on-device database (consistent with every prior migration in this project).

## 10. Open items deliberately deferred (not blocking this phase)

- Folder/album-based filtering — would require capturing MediaStore's bucket/folder metadata during indexing (a `:data:media`/`PhotoEntity` schema change), not attempted here since no folder data currently exists to filter on.
- A dedicated "near me" (device's live current location) filter rather than a saved point — deferred; the saved-point-plus-radius approach avoids adding a location-permission dependency to this phase.
- `sqlite-vec` or any vector-similarity search — irrelevant to this phase (people-search here is exact person-ID matching via `person_faces`, not embedding similarity); remains the documented Phase 12 upgrade path per ARCHITECTURE.md if ever needed elsewhere.
