package com.localphotoai.photomanager.domain.face

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.photo.Photo

private const val TAG = "DetectFacesUseCase"
private const val CHUNK_SIZE = 10

/**
 * Runs one face-detection pass over every photo not yet processed (or reprocessed after a
 * metadata change). Each photo is handled independently: a corrupted or unreadable image is
 * caught, flagged, and skipped — it never aborts the batch or crashes the worker. Progress is
 * committed photo-by-photo in chunks, so an interruption loses at most the in-flight chunk; the
 * next run simply re-queries for photos still missing detection and continues.
 */
class DetectFacesUseCase(
    private val repository: FaceRepository,
    private val faceDetector: FaceDetector,
    private val logger: Logger,
) {

    suspend operator fun invoke(): AppResult<IndexingProgress> {
        return try {
            val startedAt = System.currentTimeMillis()
            val pending = repository.fetchPhotosNeedingDetection()

            if (pending.isEmpty()) {
                val progress = IndexingProgress(
                    state = IndexingState.COMPLETE,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = null,
                )
                repository.updateDetectionProgress(progress)
                return AppResult.Success(progress)
            }

            repository.updateDetectionProgress(
                IndexingProgress(
                    state = IndexingState.RUNNING,
                    itemsProcessed = 0,
                    itemsTotal = pending.size,
                    lastRunAtMs = startedAt,
                    lastError = null,
                ),
            )

            var processed = 0
            for (chunk in pending.chunked(CHUNK_SIZE)) {
                for (photo in chunk) {
                    processPhoto(photo)
                    processed++
                }
                repository.updateDetectionProgress(
                    IndexingProgress(
                        state = IndexingState.RUNNING,
                        itemsProcessed = processed,
                        itemsTotal = pending.size,
                        lastRunAtMs = startedAt,
                        lastError = null,
                    ),
                )
            }

            val finalProgress = IndexingProgress(
                state = IndexingState.COMPLETE,
                itemsProcessed = processed,
                itemsTotal = pending.size,
                lastRunAtMs = startedAt,
                lastError = null,
            )
            repository.updateDetectionProgress(finalProgress)
            logger.info(TAG, "Face detection complete: $processed photo(s) processed")
            AppResult.Success(finalProgress)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown face detection error"
            logger.error(TAG, "Face detection run failed", t)
            repository.updateDetectionProgress(
                IndexingProgress(
                    state = IndexingState.ERROR,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = System.currentTimeMillis(),
                    lastError = message,
                ),
            )
            AppResult.Failure(AppError.Io(message = "Face detection failed: $message", cause = t))
        }
    }

    private suspend fun processPhoto(photo: Photo) {
        try {
            val faces = faceDetector.detectFaces(
                photoUri = photo.uri,
                sourceWidthPx = photo.width,
                sourceHeightPx = photo.height,
                orientationDegrees = photo.orientationDegrees,
            )
            repository.saveFaces(photo.mediaStoreId, photo.orientationDegrees, faces)
            repository.markDetectionComplete(photo.mediaStoreId, error = null)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown error"
            logger.warn(TAG, "Face detection failed for photo ${photo.mediaStoreId}", t)
            repository.markDetectionComplete(photo.mediaStoreId, error = message)
        }
    }
}
