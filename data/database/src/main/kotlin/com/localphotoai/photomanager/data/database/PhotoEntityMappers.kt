package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.entity.PhotoEntity
import com.localphotoai.photomanager.domain.photo.Photo

fun PhotoEntity.toDomain(): Photo = Photo(
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
    facesDetectedAt = facesDetectedAt,
    faceDetectionError = faceDetectionError,
)
