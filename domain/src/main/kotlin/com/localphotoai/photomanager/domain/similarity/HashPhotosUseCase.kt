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
