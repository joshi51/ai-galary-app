package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos ORDER BY dateTakenMs DESC, dateAddedMs DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT mediaStoreId, dateModifiedMs FROM photos")
    suspend fun getLightSnapshot(): List<LightRow>

    @Query("SELECT * FROM photos WHERE mediaStoreId = :mediaStoreId")
    suspend fun getById(mediaStoreId: Long): PhotoEntity?

    @Query("SELECT * FROM photos WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun getByIds(mediaStoreIds: List<Long>): List<PhotoEntity>

    @Upsert
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("SELECT * FROM photos WHERE facesDetectedAt IS NULL")
    suspend fun getPhotosNeedingFaceDetection(): List<PhotoEntity>

    @Query(
        "UPDATE photos SET facesDetectedAt = :at, faceDetectionError = :error WHERE mediaStoreId = :photoId",
    )
    suspend fun markFaceDetectionComplete(photoId: Long, at: Long, error: String?)

    @Query("SELECT mediaStoreId, uri FROM photos WHERE contentHash IS NULL")
    suspend fun getPhotosNeedingHash(): List<HashPendingRow>

    @Query(
        "UPDATE photos SET contentHash = :contentHash, perceptualHash = :perceptualHash, hashError = NULL " +
            "WHERE mediaStoreId = :photoId",
    )
    suspend fun updateHashes(photoId: Long, contentHash: String, perceptualHash: Long)

    @Query("UPDATE photos SET hashError = :error WHERE mediaStoreId = :photoId")
    suspend fun markHashFailed(photoId: Long, error: String)

    @Query("SELECT mediaStoreId, contentHash, perceptualHash, dateTakenMs FROM photos WHERE contentHash IS NOT NULL")
    suspend fun getAllHashes(): List<PhotoHashRow>

    @Query(
        "SELECT mediaStoreId, uri, width AS widthPx, height AS heightPx, orientationDegrees FROM photos " +
            "WHERE contentHash IS NOT NULL AND " +
            "(similarityEmbeddingVersion IS NULL OR similarityEmbeddingVersion != :currentModelVersion)",
    )
    suspend fun getPhotosNeedingSimilarityEmbedding(currentModelVersion: Int): List<PhotoForEmbeddingRow>

    @Query(
        "UPDATE photos SET similarityEmbeddingVersion = :modelVersion, similarityEmbeddingError = NULL " +
            "WHERE mediaStoreId = :photoId",
    )
    suspend fun markSimilarityEmbeddingComplete(photoId: Long, modelVersion: Int)

    @Query(
        "UPDATE photos SET similarityEmbeddingVersion = :modelVersion, similarityEmbeddingError = :error " +
            "WHERE mediaStoreId = :photoId",
    )
    suspend fun markSimilarityEmbeddingFailed(photoId: Long, modelVersion: Int, error: String)

    data class LightRow(val mediaStoreId: Long, val dateModifiedMs: Long)
    data class HashPendingRow(val mediaStoreId: Long, val uri: String)
    data class PhotoHashRow(val mediaStoreId: Long, val contentHash: String, val perceptualHash: Long, val dateTakenMs: Long?)
    data class PhotoForEmbeddingRow(
        val mediaStoreId: Long,
        val uri: String,
        val widthPx: Int,
        val heightPx: Int,
        val orientationDegrees: Int,
    )
}
