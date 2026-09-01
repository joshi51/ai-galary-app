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
