package com.localphotoai.photomanager.domain.face

import com.localphotoai.photomanager.core.common.AppError
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState

private const val TAG = "GenerateFaceEmbeddingsUseCase"
private const val CHUNK_SIZE = 20

/**
 * Runs one embedding-generation pass over every face not yet embedded by the current model
 * version. Each face is processed one at a time (one bitmap crop/tensor alive at a time — never
 * batched in memory), independently: a bad crop is caught, flagged, and skipped, never aborting
 * the batch. A face marked failed at the current model version is not retried until the model
 * version changes (see [FaceEmbeddingRepository.fetchFacesNeedingEmbedding]). If the model isn't
 * downloaded yet, this is a no-op that reports why, rather than marking every pending face as
 * failed (which would incorrectly block them from being retried once the model becomes ready).
 */
class GenerateFaceEmbeddingsUseCase(
    private val repository: FaceEmbeddingRepository,
    private val embeddingGenerator: EmbeddingGenerator,
    private val logger: Logger,
) {

    suspend operator fun invoke(): AppResult<IndexingProgress> {
        return try {
            val startedAt = System.currentTimeMillis()

            if (!embeddingGenerator.isReady()) {
                val progress = IndexingProgress(
                    state = IndexingState.IDLE,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = "Embedding model not downloaded yet",
                )
                repository.updateEmbeddingProgress(progress)
                return AppResult.Success(progress)
            }

            val pending = repository.fetchFacesNeedingEmbedding(embeddingGenerator.modelVersion)

            if (pending.isEmpty()) {
                val progress = IndexingProgress(
                    state = IndexingState.COMPLETE,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = startedAt,
                    lastError = null,
                )
                repository.updateEmbeddingProgress(progress)
                return AppResult.Success(progress)
            }

            repository.updateEmbeddingProgress(
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
                for (face in chunk) {
                    processFace(face)
                    processed++
                }
                repository.updateEmbeddingProgress(
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
            repository.updateEmbeddingProgress(finalProgress)
            logger.info(TAG, "Embedding generation complete: $processed face(s) processed")
            AppResult.Success(finalProgress)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown embedding error"
            logger.error(TAG, "Embedding generation run failed", t)
            repository.updateEmbeddingProgress(
                IndexingProgress(
                    state = IndexingState.ERROR,
                    itemsProcessed = 0,
                    itemsTotal = 0,
                    lastRunAtMs = System.currentTimeMillis(),
                    lastError = message,
                ),
            )
            AppResult.Failure(AppError.Io(message = "Embedding generation failed: $message", cause = t))
        }
    }

    private suspend fun processFace(face: FaceForEmbedding) {
        try {
            val rawVector = embeddingGenerator.generateEmbedding(
                photoUri = face.photoUri,
                photoWidthPx = face.photoWidthPx,
                photoHeightPx = face.photoHeightPx,
                orientationDegrees = face.orientationDegrees,
                left = face.left,
                top = face.top,
                right = face.right,
                bottom = face.bottom,
            )
            val normalized = l2Normalize(rawVector)
            repository.saveEmbedding(FaceEmbedding(face.faceId, embeddingGenerator.modelVersion, normalized))
        } catch (t: Throwable) {
            val message = t.message ?: t::class.simpleName ?: "Unknown error"
            logger.warn(TAG, "Embedding generation failed for face ${face.faceId}", t)
            repository.markEmbeddingFailed(face.faceId, embeddingGenerator.modelVersion, message)
        }
    }
}
