package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.ClusteringStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClusteringStatusDao {

    @Query("SELECT * FROM clustering_status WHERE id = ${ClusteringStatusEntity.SINGLETON_ID}")
    fun observe(): Flow<ClusteringStatusEntity?>

    @Upsert
    suspend fun upsert(status: ClusteringStatusEntity)
}
