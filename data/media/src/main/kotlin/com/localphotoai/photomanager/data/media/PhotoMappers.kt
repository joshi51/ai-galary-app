package com.localphotoai.photomanager.data.media

import com.localphotoai.photomanager.data.database.entity.PhotoEntity
import com.localphotoai.photomanager.domain.photo.PhotoMetadata

fun PhotoMetadata.toEntity(lastIndexedAtMs: Long): PhotoEntity = PhotoEntity(
    mediaStoreId = mediaStoreId,
    uri = uri,
    filename = filename,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    dateAddedMs = dateAddedMs,
    dateModifiedMs = dateModifiedMs,
    dateTakenMs = dateTakenMs,
    orientationDegrees = orientationDegrees,
    latitude = latitude,
    longitude = longitude,
    lastIndexedAtMs = lastIndexedAtMs,
    indexError = indexError,
    relativePath = relativePath,
)
