package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.FaceDao
import com.localphotoai.photomanager.data.database.dao.FaceDetectionStatusDao
import com.localphotoai.photomanager.data.database.dao.PhotoDao
import com.localphotoai.photomanager.data.database.entity.FaceDetectionStatusEntity
import com.localphotoai.photomanager.domain.face.DetectedFace
import com.localphotoai.photomanager.domain.face.Face
import com.localphotoai.photomanager.domain.face.FaceRepository
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.photo.Photo
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FaceRepositoryImpl @Inject constructor(
    private val photoDao: PhotoDao,
    private val faceDao: FaceDao,
    private val faceDetectionStatusDao: FaceDetectionStatusDao,
) : FaceRepository {

    override suspend fun fetchPhotosNeedingDetection(): List<Photo> =
        photoDao.getPhotosNeedingFaceDetection().map { it.toDomain() }

    override suspend fun saveFaces(photoId: Long, rotationDegrees: Int, faces: List<DetectedFace>) {
        faceDao.replaceFacesForPhoto(photoId, faces.map { it.toEntity(photoId, rotationDegrees) })
    }

    override suspend fun markDetectionComplete(photoId: Long, error: String?) {
        photoDao.markFaceDetectionComplete(photoId, at = System.currentTimeMillis(), error = error)
    }

    override fun observeFacesForPhoto(photoId: Long): Flow<List<Face>> =
        faceDao.observeForPhoto(photoId).map { entities -> entities.map { it.toDomain() } }

    override fun observeDetectionProgress(): Flow<IndexingProgress> =
        faceDetectionStatusDao.observe().map { it?.toDomain() ?: IndexingProgress.IDLE }

    override suspend fun updateDetectionProgress(progress: IndexingProgress) {
        faceDetectionStatusDao.upsert(
            FaceDetectionStatusEntity(
                state = progress.state.name,
                itemsProcessed = progress.itemsProcessed,
                itemsTotal = progress.itemsTotal,
                lastRunAtMs = progress.lastRunAtMs,
                lastError = progress.lastError,
            ),
        )
    }

    private fun FaceDetectionStatusEntity.toDomain(): IndexingProgress = IndexingProgress(
        state = runCatching { IndexingState.valueOf(state) }.getOrDefault(IndexingState.IDLE),
        itemsProcessed = itemsProcessed,
        itemsTotal = itemsTotal,
        lastRunAtMs = lastRunAtMs,
        lastError = lastError,
    )
}
