package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.SimilarityEmbeddingEntity

@Dao
interface SimilarityEmbeddingDao {

    @Upsert
    suspend fun upsertEmbedding(embedding: SimilarityEmbeddingEntity)

    @Query("SELECT photoId, vector FROM similarity_embeddings")
    suspend fun getAllEmbeddings(): List<PhotoVectorRow>

    @Query("SELECT vector FROM similarity_embeddings WHERE photoId = :photoId")
    suspend fun getVector(photoId: Long): ByteArray?

    data class PhotoVectorRow(val photoId: Long, val vector: ByteArray)
}
