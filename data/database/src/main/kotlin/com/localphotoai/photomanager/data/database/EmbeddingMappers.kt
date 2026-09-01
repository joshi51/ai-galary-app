package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.EmbeddingDao
import com.localphotoai.photomanager.data.database.entity.EmbeddingEntity
import com.localphotoai.photomanager.domain.face.FaceEmbedding
import com.localphotoai.photomanager.domain.face.FaceForEmbedding
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun FaceEmbedding.toEntity(): EmbeddingEntity = EmbeddingEntity(
    faceId = faceId,
    modelVersion = modelVersion,
    vector = floatArrayToBytes(vector),
)

fun EmbeddingDao.FaceForEmbeddingRow.toDomain(): FaceForEmbedding = FaceForEmbedding(
    faceId = faceId,
    photoUri = photoUri,
    photoWidthPx = photoWidthPx,
    photoHeightPx = photoHeightPx,
    orientationDegrees = orientationDegrees,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

internal fun floatArrayToBytes(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach { buffer.putFloat(it) }
    return buffer.array()
}

internal fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buffer.getFloat() }
}
