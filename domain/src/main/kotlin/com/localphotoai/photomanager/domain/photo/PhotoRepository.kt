package com.localphotoai.photomanager.domain.photo

import kotlinx.coroutines.flow.Flow

/**
 * Access to indexed photo data and the indexing pipeline. Implemented in `:data:media` on top
 * of MediaStore (remote snapshot) and Room (local persisted state) — this interface is the only
 * seam `:domain` sees, keeping it free of Android dependencies.
 */
interface PhotoRepository {

    fun observePhotos(): Flow<List<Photo>>

    fun observeIndexingProgress(): Flow<IndexingProgress>

    /** MediaStore's current change-detection token (API 30+), or null if unsupported. */
    suspend fun fetchGeneration(): Long?

    /** Cheap MediaStore query (id + dateModified only) — no EXIF/file reads. */
    suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord>

    /** What's currently persisted locally, in the same light shape, for diffing. */
    suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord>

    /** Full metadata (dimensions, EXIF, location) for the given MediaStore ids only. */
    suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata>

    suspend fun upsert(photos: List<PhotoMetadata>)

    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    suspend fun updateIndexingProgress(progress: IndexingProgress)

    suspend fun saveGeneration(generation: Long)

    suspend fun lastSavedGeneration(): Long?

    /** A single photo by its MediaStore id, or null if it doesn't exist / was deleted. */
    suspend fun fetchById(mediaStoreId: Long): Photo?

    /** Every photo matching the given ids, in no particular order — ids with no match are omitted. */
    suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo>
}
