package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.EmbeddingDao
import com.localphotoai.photomanager.data.database.dao.EmbeddingStatusDao
import com.localphotoai.photomanager.data.database.entity.EmbeddingStatusEntity
import com.localphotoai.photomanager.domain.face.FaceEmbedding
import com.localphotoai.photomanager.domain.face.FaceEmbeddingRepository
import com.localphotoai.photomanager.domain.face.FaceForEmbedding
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FaceEmbeddingRepositoryImpl @Inject constructor(
    private val embeddingDao: EmbeddingDao,
    private val embeddingStatusDao: EmbeddingStatusDao,
) : FaceEmbeddingRepository {

    override suspend fun fetchFacesNeedingEmbedding(currentModelVersion: Int): List<FaceForEmbedding> =
        embeddingDao.getFacesNeedingEmbedding(currentModelVersion).map { it.toDomain() }

    override suspend fun saveEmbedding(embedding: FaceEmbedding) {
        embeddingDao.upsertEmbedding(embedding.toEntity())
        embeddingDao.markFaceEmbeddingComplete(embedding.faceId, embedding.modelVersion, error = null)
    }

    override suspend fun markEmbeddingFailed(faceId: Long, modelVersion: Int, error: String) {
        embeddingDao.markFaceEmbeddingComplete(faceId, modelVersion, error)
    }

    override fun observeEmbeddingProgress(): Flow<IndexingProgress> =
        embeddingStatusDao.observe().map { it?.toDomain() ?: IndexingProgress.IDLE }

    override suspend fun updateEmbeddingProgress(progress: IndexingProgress) {
        embeddingStatusDao.upsert(
            EmbeddingStatusEntity(
                state = progress.state.name,
                itemsProcessed = progress.itemsProcessed,
                itemsTotal = progress.itemsTotal,
                lastRunAtMs = progress.lastRunAtMs,
                lastError = progress.lastError,
            ),
        )
    }

    private fun EmbeddingStatusEntity.toDomain(): IndexingProgress = IndexingProgress(
        state = runCatching { IndexingState.valueOf(state) }.getOrDefault(IndexingState.IDLE),
        itemsProcessed = itemsProcessed,
        itemsTotal = itemsTotal,
        lastRunAtMs = lastRunAtMs,
        lastError = lastError,
    )
}
