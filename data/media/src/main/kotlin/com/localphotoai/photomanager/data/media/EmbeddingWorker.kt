package com.localphotoai.photomanager.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.localphotoai.photomanager.core.common.AppResult
import com.localphotoai.photomanager.domain.face.GenerateFaceEmbeddingsUseCase
import com.localphotoai.photomanager.domain.person.ClusteringScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one embedding-generation pass via [GenerateFaceEmbeddingsUseCase]. Safe to interrupt and
 * re-run: each run re-queries for faces still missing an embedding at the current model version,
 * so a job killed mid-run resumes cleanly on the next trigger. A no-op (model not downloaded) is
 * reported as success, not a failure — there's nothing to retry until the user downloads the model.
 * On success, triggers an immediate clustering pass so newly embedded faces get grouped without
 * waiting for clustering's own periodic reconciliation job.
 */
@HiltWorker
class EmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val generateFaceEmbeddingsUseCase: GenerateFaceEmbeddingsUseCase,
    private val clusteringScheduler: ClusteringScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = generateFaceEmbeddingsUseCase()
        return when (result) {
            is AppResult.Success -> {
                clusteringScheduler.scheduleImmediateClustering()
                Result.success()
            }
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_IMMEDIATE = "embedding_immediate"
        const val WORK_NAME_PERIODIC = "embedding_periodic"
        private const val MAX_ATTEMPTS = 3
    }
}
