package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.localphotoai.photomanager.data.database.entity.FaceDetectionStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDetectionStatusDao {

    @Query("SELECT * FROM face_detection_status WHERE id = ${FaceDetectionStatusEntity.SINGLETON_ID}")
    fun observe(): Flow<FaceDetectionStatusEntity?>

    @Upsert
    suspend fun upsert(status: FaceDetectionStatusEntity)
}
