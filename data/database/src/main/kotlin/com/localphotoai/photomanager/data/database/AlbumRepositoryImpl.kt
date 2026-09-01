package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.AlbumDao
import com.localphotoai.photomanager.data.database.entity.AlbumEntity
import com.localphotoai.photomanager.data.database.entity.AlbumPhotoEntity
import com.localphotoai.photomanager.domain.organization.AlbumRepository
import javax.inject.Inject

class AlbumRepositoryImpl @Inject constructor(
    private val albumDao: AlbumDao,
) : AlbumRepository {
    override suspend fun createAlbum(name: String, photoIds: List<Long>): Long {
        val albumId = albumDao.insertAlbum(AlbumEntity(name = name, createdAtMs = System.currentTimeMillis()))
        albumDao.insertAlbumPhotos(photoIds.map { AlbumPhotoEntity(albumId = albumId, photoId = it) })
        return albumId
    }

    override suspend fun deleteAlbum(albumId: Long) = albumDao.deleteAlbum(albumId)
}
