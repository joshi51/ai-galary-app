package com.localphotoai.photomanager.ml.embeddings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.localphotoai.photomanager.core.common.Logger
import com.localphotoai.photomanager.domain.face.EmbeddingGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

private const val TAG = "FaceNetEmbeddingGenerator"

/** Padding added around a detected face's tight bounding box before crop, as a fraction of its size. */
private const val CROP_MARGIN_FRACTION = 0.2f

/** Longest-side cap for the source bitmap decoded before cropping — bounds peak memory on large photos. */
private const val MAX_SOURCE_DIMENSION_PX = 1024

/**
 * Wraps a local TFLite FaceNet model (see [FaceNetModelSpec] and ARCHITECTURE.md's Phase 4 notes
 * for model choice/license). The interpreter is created lazily on first use and reused (paying
 * model-load cost once per process), attempting a GPU delegate first and falling back to the
 * default CPU (XNNPACK) path if GPU delegate creation fails — probed once, never retried that
 * session, per the model-execution constraints in ARCHITECTURE.md §18.
 *
 * Alignment here is a padded crop + resize to the model's input size, not landmark-based affine
 * alignment — ML Kit's detector (Phase 3) isn't configured to return facial landmarks, and a
 * fixed-margin crop is standard practice for FaceNet-family models trained on loosely-cropped
 * faces. A future `FaceAligner` using detected landmarks is a documented possible enhancement,
 * not a Phase 4 requirement.
 */
@Singleton
class FaceNetEmbeddingGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelFileStore: ModelFileStore,
    private val logger: Logger,
) : EmbeddingGenerator {

    override val modelVersion: Int = FaceNetModelSpec.MODEL_VERSION

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentTier = DelegateTier.GPU

    override suspend fun isReady(): Boolean = modelFileStore.isModelPresent()

    /**
     * Runs inference on [interpreter], and if the currently-selected delegate tier fails *at run
     * time* (not just at creation time — a real failure mode: NNAPI can create successfully but
     * throw on first actual inference, as observed on some emulator images), downgrades to the
     * next tier (GPU → NNAPI → plain CPU) and retries once. A tier that fails is never retried
     * within this process's lifetime, per ARCHITECTURE.md §18.
     */
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
        photoWidthPx: Int,
        photoHeightPx: Int,
        orientationDegrees: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): FloatArray {
        val source = decodeSourceBitmap(photoUri, photoWidthPx, photoHeightPx)
            ?: error("Unable to decode bitmap for $photoUri")
        try {
            val faceCrop = cropFace(source, left, top, right, bottom)
            try {
                val resized = Bitmap.createScaledBitmap(
                    faceCrop,
                    FaceNetModelSpec.INPUT_SIZE,
                    FaceNetModelSpec.INPUT_SIZE,
                    true,
                )
                try {
                    val input = bitmapToInputBuffer(resized)
                    val output = Array(1) { FloatArray(FaceNetModelSpec.OUTPUT_SIZE) }
                    runInference(input, output)
                    return output[0]
                } finally {
                    if (resized !== faceCrop) resized.recycle()
                }
            } finally {
                if (faceCrop !== source) faceCrop.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun cropFace(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val boxWidth = (right - left) * source.width
        val boxHeight = (bottom - top) * source.height
        val marginX = boxWidth * CROP_MARGIN_FRACTION
        val marginY = boxHeight * CROP_MARGIN_FRACTION

        val x1 = max(0, ((left * source.width) - marginX).toInt())
        val y1 = max(0, ((top * source.height) - marginY).toInt())
        val x2 = min(source.width, ((right * source.width) + marginX).toInt())
        val y2 = min(source.height, ((bottom * source.height) + marginY).toInt())

        val width = max(1, x2 - x1)
        val height = max(1, y2 - y1)
        return Bitmap.createBitmap(source, x1, y1, width, height)
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val size = FaceNetModelSpec.INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        for (pixel in pixels) {
            buffer.putFloat((((pixel shr 16) and 0xFF) - 127.5f) / 128f)
            buffer.putFloat((((pixel shr 8) and 0xFF) - 127.5f) / 128f)
            buffer.putFloat(((pixel and 0xFF) - 127.5f) / 128f)
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
            logger.warn(TAG, "Failed to decode bitmap for $photoUri", t)
            null
        }
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = max(width, height)
        while (longestSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun interpreter(): Interpreter {
        interpreter?.let { return it }
        while (true) {
            try {
                val created = buildInterpreter(currentTier)
                interpreter = created
                logger.info(TAG, "Using delegate tier $currentTier for embedding inference")
                return created
            } catch (t: Throwable) {
                val nextTier = currentTier.next() ?: throw t
                logger.warn(TAG, "Delegate tier $currentTier failed to initialize, trying $nextTier", t)
                currentTier = nextTier
            }
        }
    }

    private fun buildInterpreter(tier: DelegateTier): Interpreter {
        val modelBuffer = mapModelFile()
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

    private fun mapModelFile(): ByteBuffer {
        FileInputStream(modelFileStore.modelFile).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
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
