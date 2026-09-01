package com.localphotoai.photomanager.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.localphotoai.photomanager.domain.similarity.PerceptualHashCalculator
import com.localphotoai.photomanager.domain.similarity.PhotoHashResult
import com.localphotoai.photomanager.domain.similarity.PhotoHasher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Computes both hashes per photo from two independent decode passes: a raw byte stream for
 * SHA-256 (must see every byte, can't downsample) and a tiny 9x8 decode for dHash (deliberately
 * downsampled at decode time via `inSampleSize`, never a full-resolution bitmap). Two decodes
 * cost more I/O than one, but SHA-256 needs the untouched bytes while dHash wants them tiny — no
 * single decode serves both.
 */
class PhotoHasherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoHasher {

    override suspend fun hash(photoUri: String): PhotoHashResult {
        val uri = Uri.parse(photoUri)
        val contentHash = computeContentHash(uri)
        val perceptualHash = computePerceptualHash(uri)
        return PhotoHashResult(contentHash, perceptualHash)
    }

    private fun computeContentHash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        } ?: error("Unable to open stream for $uri")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computePerceptualHash(uri: Uri): Long {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8 // any large downsample is fine — the final scaled-down read below is what matters
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Unable to decode bitmap for $uri")
        try {
            val small = Bitmap.createScaledBitmap(
                bitmap, PerceptualHashCalculator.HASH_WIDTH, PerceptualHashCalculator.HASH_HEIGHT, true,
            )
            try {
                val pixels = IntArray(PerceptualHashCalculator.HASH_WIDTH * PerceptualHashCalculator.HASH_HEIGHT)
                small.getPixels(
                    pixels, 0, PerceptualHashCalculator.HASH_WIDTH, 0, 0,
                    PerceptualHashCalculator.HASH_WIDTH, PerceptualHashCalculator.HASH_HEIGHT,
                )
                val grayscale = IntArray(pixels.size) { i ->
                    val p = pixels[i]
                    (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                }
                return PerceptualHashCalculator.dHash(grayscale)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
