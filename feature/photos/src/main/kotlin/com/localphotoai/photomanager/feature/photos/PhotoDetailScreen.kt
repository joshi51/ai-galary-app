package com.localphotoai.photomanager.feature.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.localphotoai.photomanager.domain.face.Face
import com.localphotoai.photomanager.domain.photo.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_DISPLAY_DIMENSION_PX = 1600

/**
 * Debug screen: shows one photo with its detected face bounding boxes overlaid, for sanity-
 * checking the detection pipeline. Boxes are drawn in the same (pre-rotation) coordinate space
 * ML Kit detected them in — the photo is displayed unrotated to match, so a face box always
 * lines up with the face even for photos whose EXIF orientation isn't 0°. [photo.orientationDegrees]
 * is shown as a label rather than applied visually, to keep detection output and display in the
 * same coordinate space without an extra transform.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photo: Photo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoDetailViewModel = hiltViewModel(),
) {
    val faces by viewModel.observeFaces(photo.mediaStoreId).collectAsState(initial = emptyList())
    val context = LocalContext.current
    var bitmap by remember(photo.mediaStoreId) { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember(photo.mediaStoreId) { mutableStateOf(false) }

    LaunchedEffect(photo.mediaStoreId) {
        val decoded = withContext(Dispatchers.IO) {
            decodeBitmapForDisplay(context, photo.uri, photo.width, photo.height)
        }
        bitmap = decoded
        decodeFailed = decoded == null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(photo.filename) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                decodeFailed -> Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Unable to decode this photo.")
                }
                bitmap == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                else -> {
                    val bmp = bitmap!!
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = photo.filename,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            faces.forEach { face -> drawFaceBox(face, size.width, size.height) }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${faces.size} face(s) detected",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Source EXIF orientation: ${photo.orientationDegrees}° " +
                        "(shown unrotated — boxes are in the same coordinate space as detection)",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (photo.faceDetectionError != null) {
                    Text(
                        text = "Detection error: ${photo.faceDetectionError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawFaceBox(face: Face, width: Float, height: Float) {
    drawRect(
        color = Color.Red,
        topLeft = Offset(face.left * width, face.top * height),
        size = Size((face.right - face.left) * width, (face.bottom - face.top) * height),
        style = Stroke(width = 4f),
    )
}

private fun decodeBitmapForDisplay(context: Context, uri: String, sourceWidth: Int, sourceHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(sourceWidth, sourceHeight, MAX_DISPLAY_DIMENSION_PX)
    }
    return try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (t: Throwable) {
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
