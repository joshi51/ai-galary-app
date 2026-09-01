package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Query(
        """
        SELECT faces.id AS faceId, photos.uri AS photoUri, photos.width AS photoWidthPx,
               photos.height AS photoHeightPx, photos.orientationDegrees AS orientationDegrees,
               faces.`left` AS `left`, faces.`top` AS `top`, faces.`right` AS `right`, faces.`bottom` AS `bottom`
        FROM faces
        JOIN photos ON faces.photoId = photos.mediaStoreId
        WHERE faces.embeddingVersion IS NULL OR faces.embeddingVersion != :currentModelVersion
        """,
    )
    suspend fun getFacesNeedingEmbedding(currentModelVersion: Int): List<FaceForEmbeddingRow>

    @Upsert
    suspend fun upsertEmbedding(embedding: EmbeddingEntity)

    @Query("UPDATE faces SET embeddingVersion = :modelVersion, embeddingError = :error WHERE id = :faceId")
    suspend fun markFaceEmbeddingComplete(faceId: Long, modelVersion: Int, error: String?)

    @Query("SELECT vector FROM embeddings WHERE faceId = :faceId")
    suspend fun getVector(faceId: Long): ByteArray?

    data class FaceForEmbeddingRow(
        val faceId: Long,
        val photoUri: String,
        val photoWidthPx: Int,
        val photoHeightPx: Int,
        val orientationDegrees: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )
}
