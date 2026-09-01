package com.localphotoai.photomanager.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity

@Dao
interface AlbumDao {
    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    suspend fun insertAlbumPhotos(photos: List<AlbumPhotoEntity>)

    /** `album_photos` cascades on `albums` delete, so this alone removes the album's membership too. */
    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbum(albumId: Long)
}
