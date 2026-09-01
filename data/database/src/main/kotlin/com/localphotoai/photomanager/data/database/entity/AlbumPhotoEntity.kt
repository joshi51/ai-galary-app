package com.localphotoai.photomanager.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index

@Entity(
    tableName = "album_photos",
    primaryKeys = ["albumId", "photoId"],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["mediaStoreId"], childColumns = ["photoId"], onDelete = CASCADE),
    ],
    indices = [Index("albumId"), Index("photoId")],
)
data class AlbumPhotoEntity(val albumId: Long, val photoId: Long)
