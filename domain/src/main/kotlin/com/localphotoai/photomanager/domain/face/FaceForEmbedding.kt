package com.localphotoai.photomanager.domain.face

/** A face plus enough of its source photo's info to crop, align, and embed it. */
data class FaceForEmbedding(
    val faceId: Long,
    val photoUri: String,
    val photoWidthPx: Int,
    val photoHeightPx: Int,
    val orientationDegrees: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
