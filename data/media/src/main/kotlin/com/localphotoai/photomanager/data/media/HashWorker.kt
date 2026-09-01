package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.similarity.HashGroupingScheduler
import com.localphotoai.photomanager.domain.similarity.HashPhotosUseCase
import com.localphotoai.photomanager.domain.similarity.SimilarityEmbeddingScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one hashing pass. Chained off [IndexWorker], parallel to [FaceDetectionWorker] (hashing
 * doesn't depend on face detection). On success, triggers both downstream branches: hash-based
 * grouping (duplicate/near-dup/burst) and similarity-embedding generation.
 */
@HiltWorker
class HashWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val hashPhotosUseCase: HashPhotosUseCase,
    private val hashGroupingScheduler: HashGroupingScheduler,
    private val similarityEmbeddingScheduler: SimilarityEmbeddingScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = hashPhotosUseCase()
        return when (result) {
            is AppResult.Success -> {
                hashGroupingScheduler.scheduleImmediateGrouping()
                similarityEmbeddingScheduler.scheduleImmediateEmbedding()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "hash_immediate"
        const val WORK_NAME_PERIODIC = "hash_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
