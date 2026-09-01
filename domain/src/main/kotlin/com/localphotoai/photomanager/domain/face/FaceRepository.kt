package com.localphotoai.photomanager.domain.face

import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.flow.Flow

/**
 * Access to face-detection results and pipeline state. Implemented in `:data:database` (Room
 * only — no MediaStore access is needed for this stage).
 */
interface FaceRepository {

    /** Photos indexed but not yet run through face detection, or whose metadata changed since. */
    suspend fun fetchPhotosNeedingDetection(): List<Photo>

    /** Replaces all faces for [photoId] with [faces] (a re-run fully supersedes prior results). */
    suspend fun saveFaces(photoId: Long, rotationDegrees: Int, faces: List<DetectedFace>)

    /** Marks [photoId] as processed for this pipeline stage, with [error] set on failure. */
    suspend fun markDetectionComplete(photoId: Long, error: String?)

    fun observeFacesForPhoto(photoId: Long): Flow<List<Face>>

    fun observeDetectionProgress(): Flow<IndexingProgress>

    suspend fun updateDetectionProgress(progress: IndexingProgress)
}
