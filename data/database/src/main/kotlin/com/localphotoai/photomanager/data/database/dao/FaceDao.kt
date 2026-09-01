package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.localphotoai.photomanager.data.database.entity.FaceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {

    @Query("SELECT * FROM faces WHERE photoId = :photoId")
    fun observeForPhoto(photoId: Long): Flow<List<FaceEntity>>

    @Query("DELETE FROM faces WHERE photoId = :photoId")
    suspend fun deleteForPhoto(photoId: Long)

    @Insert
    suspend fun insertAll(faces: List<FaceEntity>)

    @Query("UPDATE faces SET markedIncorrect = 1 WHERE id = :faceId")
    suspend fun markIncorrect(faceId: Long)

    /** Fully replaces a photo's faces — a re-run of detection supersedes prior results rather than merging. */
    @Transaction
    suspend fun replaceFacesForPhoto(photoId: Long, faces: List<FaceEntity>) {
        deleteForPhoto(photoId)
        if (faces.isNotEmpty()) insertAll(faces)
    }
}
