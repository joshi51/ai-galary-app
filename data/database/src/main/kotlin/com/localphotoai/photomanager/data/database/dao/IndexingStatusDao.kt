package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.IndexingStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IndexingStatusDao {

    @Query("SELECT * FROM indexing_status WHERE id = ${IndexingStatusEntity.SINGLETON_ID}")
    fun observe(): Flow<IndexingStatusEntity?>

    @Query("SELECT lastGeneration FROM indexing_status WHERE id = ${IndexingStatusEntity.SINGLETON_ID}")
    suspend fun getLastGeneration(): Long?

    @Upsert
    suspend fun upsert(status: IndexingStatusEntity)
}
