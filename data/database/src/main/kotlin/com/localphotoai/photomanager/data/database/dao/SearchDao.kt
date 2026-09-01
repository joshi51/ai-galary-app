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
     * `:personCount = 0` means "no person filter" (Phase 8's person-less queries, e.g. "largest
     * photos"), not "match nothing". [sortBy] is one of `PhotoSortOrder`'s names.
     */
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

    /** Bounded, non-paged variant of [searchPhotos] for Phase 8's tool-driven queries — same
     * WHERE/ORDER shape, plus a hard `LIMIT`. */
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
