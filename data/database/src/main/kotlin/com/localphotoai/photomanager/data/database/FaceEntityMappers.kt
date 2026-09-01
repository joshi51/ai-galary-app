package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.entity.FaceEntity
import com.localphotoai.photomanager.domain.face.DetectedFace
import com.localphotoai.photomanager.domain.face.Face

fun DetectedFace.toEntity(photoId: Long, rotationDegrees: Int): FaceEntity = FaceEntity(
    photoId = photoId,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    confidence = confidence,
    rotationDegrees = rotationDegrees,
)

fun FaceEntity.toDomain(): Face = Face(
    id = id,
    photoId = photoId,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    confidence = confidence,
    rotationDegrees = rotationDegrees,
    markedIncorrect = markedIncorrect,
)
