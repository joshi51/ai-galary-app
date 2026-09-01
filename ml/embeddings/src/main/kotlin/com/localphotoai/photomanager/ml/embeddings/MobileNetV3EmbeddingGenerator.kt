package com.localphotoai.photomanager.ml.embeddings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.similarity.ImageSimilarityEmbeddingGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

private const val TAG = "MobileNetV3EmbeddingGenerator"
private const val MAX_SOURCE_DIMENSION_PX = 1024

/**
 * Wraps the bundled MobileNetV3-Small TFLite model (see [MobileNetV3ModelSpec] and
 * ARCHITECTURE.md's Phase 7 notes for model choice/license/bundling rationale). Same
 * delegate-tier retry discipline as [FaceNetEmbeddingGenerator] (Phase 4), since MobileNetV3 is
 * subject to the same run-time delegate failure modes. No face crop here — the whole photo is
 * decoded and resized directly.
 */
@Singleton
class MobileNetV3EmbeddingGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : ImageSimilarityEmbeddingGenerator {

    override val modelVersion: Int = MobileNetV3ModelSpec.MODEL_VERSION

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentTier = DelegateTier.GPU

    private fun runInference(input: ByteBuffer, output: Array<FloatArray>) {
        try {
            interpreter().run(input, output)
        } catch (t: Throwable) {
            val nextTier = currentTier.next() ?: throw t
            logger.warn(TAG, "Delegate tier $currentTier failed at inference time, downgrading to $nextTier", t)
            closeInterpreter()
            currentTier = nextTier
            interpreter().run(input, output)
        }
    }

    override suspend fun generateEmbedding(
        photoUri: String,
        widthPx: Int,
        heightPx: Int,
        orientationDegrees: Int,
    ): FloatArray {
        val source = decodeSourceBitmap(photoUri, widthPx, heightPx) ?: error("Unable to decode bitmap for $photoUri")
        try {
            val resized = Bitmap.createScaledBitmap(source, MobileNetV3ModelSpec.INPUT_SIZE, MobileNetV3ModelSpec.INPUT_SIZE, true)
            try {
                val input = bitmapToInputBuffer(resized)
                val output = Array(1) { FloatArray(MobileNetV3ModelSpec.OUTPUT_SIZE) }
                runInference(input, output)
                return output[0]
            } finally {
                if (resized !== source) resized.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /** `include_preprocessing=True` was used during conversion (see [MobileNetV3ModelSpec]), so
     *  the model itself applies MobileNetV3's expected input scaling — raw [0,255] pixel values
     *  are fed directly, no manual normalization here. */
    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val size = MobileNetV3ModelSpec.INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
            buffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
            buffer.putFloat((pixel and 0xFF).toFloat())
        }
        buffer.rewind()
        return buffer
    }

    private fun decodeSourceBitmap(photoUri: String, sourceWidthPx: Int, sourceHeightPx: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(sourceWidthPx, sourceHeightPx, MAX_SOURCE_DIMENSION_PX)
        }
        return try {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (t: Throwable) {
            logger.warn(TAG, "Failed to decode bitmap for photo", t)
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = max(width, height)
        while (longestSide / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        return sampleSize
    }

    private fun interpreter(): Interpreter {
        interpreter?.let { return it }
        while (true) {
            try {
                val created = buildInterpreter(currentTier)
                interpreter = created
                logger.info(TAG, "Using delegate tier $currentTier for similarity-embedding inference")
                return created
            } catch (t: Throwable) {
                val nextTier = currentTier.next() ?: throw t
                logger.warn(TAG, "Delegate tier $currentTier failed to initialize, trying $nextTier", t)
                currentTier = nextTier
            }
        }
    }

    private fun buildInterpreter(tier: DelegateTier): Interpreter {
        val modelBuffer = loadAssetModel()
        return when (tier) {
            DelegateTier.GPU -> {
                if (!isGpuDelegateSupported()) error("GPU delegate not supported on this device")
                val delegate = GpuDelegate()
                gpuDelegate = delegate
                Interpreter(modelBuffer, Interpreter.Options().addDelegate(delegate))
            }
            DelegateTier.NNAPI -> Interpreter(modelBuffer, Interpreter.Options().setUseNNAPI(true))
            DelegateTier.CPU -> Interpreter(modelBuffer, Interpreter.Options().setUseNNAPI(false))
        }
    }

    private fun isGpuDelegateSupported(): Boolean = try {
        CompatibilityList().isDelegateSupportedOnThisDevice
    } catch (t: Throwable) {
        false
    }

    private fun closeInterpreter() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    private fun loadAssetModel(): ByteBuffer {
        context.assets.openFd(MobileNetV3ModelSpec.ASSET_FILENAME).use { fd ->
            val buffer = ByteBuffer.allocateDirect(fd.length.toInt()).order(ByteOrder.nativeOrder())
            fd.createInputStream().use { input ->
                buffer.put(input.readBytes())
            }
            buffer.rewind()
            return buffer
        }
    }

    private enum class DelegateTier {
        GPU, NNAPI, CPU;

        fun next(): DelegateTier? = when (this) {
            GPU -> NNAPI
            NNAPI -> CPU
            CPU -> null
        }
    }
}
