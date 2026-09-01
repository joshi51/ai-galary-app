package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface StatisticsDao {
    @Query("SELECT COUNT(*) FROM photos")
    suspend fun photoCount(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM photos")
    suspend fun totalSizeBytes(): Long

    @Query("SELECT COUNT(*) FROM people")
    suspend fun peopleCount(): Int

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun faceCount(): Int

    @Query("SELECT COUNT(*) FROM duplicate_groups")
    suspend fun duplicateGroupCount(): Int

    @Query("SELECT COUNT(*) FROM similar_groups")
    suspend fun similarGroupCount(): Int
}
