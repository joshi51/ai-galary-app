package com.localphotoai.photomanager.feature.photos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.localphotoai.photomanager.domain.photo.IndexingState
import com.localphotoai.photomanager.domain.photo.Photo

private fun readMediaPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        listOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun PhotosScreen(
    modifier: Modifier = Modifier,
    viewModel: PhotosViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val permissions = remember { readMediaPermissions() }
    var hasPermission by rememberSaveable {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        hasPermission = granted
        permissionDenied = !granted
    }

    val uiState by viewModel.uiState.collectAsState()
    var selectedPhoto by remember { mutableStateOf<Photo?>(null) }
    var showDuplicates by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.onPhotoAccessGranted()
        }
    }

    val currentSelection = selectedPhoto
    if (showDuplicates) {
        DuplicatesScreen(onBack = { showDuplicates = false }, modifier = modifier)
    } else if (currentSelection != null) {
        PhotoDetailScreen(
            photo = currentSelection,
            onBack = { selectedPhoto = null },
            modifier = modifier,
        )
    } else {
        PhotosScreenContent(
            modifier = modifier,
            hasPermission = hasPermission,
            permissionDenied = permissionDenied,
            photos = uiState.photos,
            indexingState = uiState.indexingProgress.state,
            itemsProcessed = uiState.indexingProgress.itemsProcessed,
            itemsTotal = uiState.indexingProgress.itemsTotal,
            lastError = uiState.indexingProgress.lastError,
            onRequestPermission = { permissionLauncher.launch(permissions.toTypedArray()) },
            onRefresh = viewModel::onRefreshRequested,
            onPhotoClick = { selectedPhoto = it },
            onFindDuplicates = { showDuplicates = true },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosScreenContent(
    hasPermission: Boolean,
    permissionDenied: Boolean,
    photos: List<Photo>,
    indexingState: IndexingState,
    itemsProcessed: Int,
    itemsTotal: Int,
    lastError: String?,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onFindDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Photos") },
                actions = {
                    if (hasPermission) {
                        IconButton(onClick = onFindDuplicates) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Find duplicates")
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            !hasPermission -> PermissionRationale(
                permissionDenied = permissionDenied,
                onRequestPermission = onRequestPermission,
                modifier = Modifier.padding(innerPadding),
            )
            else -> Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                if (indexingState == IndexingState.RUNNING) {
                    IndexingProgressBar(itemsProcessed = itemsProcessed, itemsTotal = itemsTotal)
                }
                if (indexingState == IndexingState.ERROR && lastError != null) {
                    Text(
                        text = "Indexing error: $lastError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                if (photos.isEmpty() && indexingState != IndexingState.RUNNING) {
                    EmptyPhotosMessage()
                } else {
                    PhotoGrid(photos = photos, onPhotoClick = onPhotoClick)
                }
            }
        }
    }
}

@Composable
private fun PermissionRationale(
    permissionDenied: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (permissionDenied) {
                "Photo access was denied. Grant access in system settings, or tap below to try again."
            } else {
                "Local AI Photo Manager needs access to your photos to index them on-device. " +
                    "Nothing ever leaves your phone."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Grant photo access")
        }
    }
}

@Composable
private fun IndexingProgressBar(itemsProcessed: Int, itemsTotal: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (itemsTotal > 0) {
            LinearProgressIndicator(
                progress = { itemsProcessed / itemsTotal.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Indexing photos: $itemsProcessed / $itemsTotal",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "Checking for photo changes…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyPhotosMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No photos indexed yet. Pull down or tap refresh once photos are added " +
                "to your device.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PhotoGrid(photos: List<Photo>, onPhotoClick: (Photo) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
    ) {
        items(photos, key = { it.mediaStoreId }) { photo ->
            AsyncImage(
                model = photo.uri,
                contentDescription = photo.filename,
                modifier = Modifier
                    .padding(1.dp)
                    .aspectRatio(1f)
                    .fillMaxWidth()
                    .clickable { onPhotoClick(photo) },
            )
        }
    }
}
