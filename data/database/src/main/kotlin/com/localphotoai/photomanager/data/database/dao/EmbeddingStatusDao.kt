package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.EmbeddingStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmbeddingStatusDao {

    @Query("SELECT * FROM embedding_status WHERE id = ${EmbeddingStatusEntity.SINGLETON_ID}")
    fun observe(): Flow<EmbeddingStatusEntity?>

    @Upsert
    suspend fun upsert(status: EmbeddingStatusEntity)
}
