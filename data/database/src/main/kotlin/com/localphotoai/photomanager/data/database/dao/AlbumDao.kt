package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity

@Dao
interface AlbumDao {
    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    suspend fun insertAlbumPhotos(photos: List<AlbumPhotoEntity>)
}
