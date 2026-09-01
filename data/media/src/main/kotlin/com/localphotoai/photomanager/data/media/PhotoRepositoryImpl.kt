package com.localphotoai.photomanager.data.media

import com.localphotoai.photomanager.data.database.dao.IndexingStatusDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.entity.IndexingStatusEntity
import com.localphotoai.photomanager.data.database.toDomain
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.photo.LightPhotoRecord
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.photo.PhotoMetadata
import com.localphotoai.photomanager.domain.photo.PhotoRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PhotoRepositoryImpl @Inject constructor(
    private val mediaStoreDataSource: MediaStoreDataSource,
    private val photoDao: PhotoDao,
    private val indexingStatusDao: IndexingStatusDao,
) : PhotoRepository {

    override fun observePhotos(): Flow<List<Photo>> =
        photoDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeIndexingProgress(): Flow<IndexingProgress> =
        indexingStatusDao.observe().map { it?.toDomain() ?: IndexingProgress.IDLE }

    override suspend fun fetchGeneration(): Long? = mediaStoreDataSource.queryGeneration()

    override suspend fun fetchRemoteLightSnapshot(): List<LightPhotoRecord> =
        mediaStoreDataSource.queryLightSnapshot()

    override suspend fun fetchLocalLightSnapshot(): List<LightPhotoRecord> =
        photoDao.getLightSnapshot().map { LightPhotoRecord(it.mediaStoreId, it.dateModifiedMs) }

    override suspend fun fetchFullMetadata(mediaStoreIds: List<Long>): List<PhotoMetadata> =
        mediaStoreDataSource.queryFullMetadata(mediaStoreIds)

    override suspend fun upsert(photos: List<PhotoMetadata>) {
        val now = System.currentTimeMillis()
        photoDao.upsertAll(photos.map { it.toEntity(lastIndexedAtMs = now) })
    }

    override suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>) {
        photoDao.deleteByMediaStoreIds(mediaStoreIds)
    }

    override suspend fun updateIndexingProgress(progress: IndexingProgress) {
        val existingGeneration = indexingStatusDao.getLastGeneration()
        indexingStatusDao.upsert(
            IndexingStatusEntity(
                state = progress.state.name,
                itemsProcessed = progress.itemsProcessed,
                itemsTotal = progress.itemsTotal,
                lastRunAtMs = progress.lastRunAtMs,
                lastError = progress.lastError,
                lastGeneration = existingGeneration,
            ),
        )
    }

    override suspend fun saveGeneration(generation: Long) {
        val existing = photoDao.count()
        indexingStatusDao.upsert(
            IndexingStatusEntity(
                state = IndexingState.COMPLETE.name,
                itemsProcessed = existing,
                itemsTotal = existing,
                lastRunAtMs = System.currentTimeMillis(),
                lastError = null,
                lastGeneration = generation,
            ),
        )
    }

    override suspend fun lastSavedGeneration(): Long? = indexingStatusDao.getLastGeneration()

    override suspend fun fetchById(mediaStoreId: Long): Photo? = photoDao.getById(mediaStoreId)?.toDomain()

    override suspend fun fetchByIds(mediaStoreIds: List<Long>): List<Photo> =
        photoDao.getByIds(mediaStoreIds).map { it.toDomain() }

    private fun IndexingStatusEntity.toDomain(): IndexingProgress = IndexingProgress(
        state = runCatching { IndexingState.valueOf(state) }.getOrDefault(IndexingState.IDLE),
        itemsProcessed = itemsProcessed,
        itemsTotal = itemsTotal,
        lastRunAtMs = lastRunAtMs,
        lastError = lastError,
    )
}
