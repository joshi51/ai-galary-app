package com.localphotoai.photomanager.ml.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.face.DetectedFace
import com.localphotoai.photomanager.domain.face.FaceDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

private const val TAG = "MlKitFaceDetectorImpl"

/** Longest-side cap for the bitmap handed to the detector — bounds peak memory on large photos. */
private const val MAX_DIMENSION_PX = 1024

/**
 * Wraps ML Kit's on-device face detector. The client is created lazily on first use and reused
 * (ML Kit's own model-loading cost is paid once), and every bitmap is decoded at the minimum
 * resolution needed and recycled immediately after inference, per the model-execution
 * constraints in ARCHITECTURE.md §18.
 */
@Singleton
class MlKitFaceDetectorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : FaceDetector {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        FaceDetection.getClient(options)
    }

    override suspend fun detectFaces(
        photoUri: String,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        orientationDegrees: Int,
    ): List<DetectedFace> {
        val bitmap = decodeBitmap(photoUri, sourceWidthPx, sourceHeightPx)
            ?: error("Unable to decode bitmap for $photoUri")
        try {
            val image = InputImage.fromBitmap(bitmap, orientationDegrees)
            val faces = detector.process(image).await()
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            return faces.map { face ->
                val box = face.boundingBox
                DetectedFace(
                    left = (box.left / width).coerceIn(0f, 1f),
                    top = (box.top / height).coerceIn(0f, 1f),
                    right = (box.right / width).coerceIn(0f, 1f),
                    bottom = (box.bottom / height).coerceIn(0f, 1f),
                    confidence = 1f,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmap(photoUri: String, sourceWidthPx: Int, sourceHeightPx: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(sourceWidthPx, sourceHeightPx, MAX_DIMENSION_PX)
        }
        return try {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (t: Throwable) {
            logger.warn(TAG, "Failed to decode bitmap for $photoUri", t)
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = maxOf(width, height)
        while (longestSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
