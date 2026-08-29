package com.localphotoai.photomanager.feature.photos

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.localphotoai.photomanager.core.ui.component.PlaceholderScreen

@Composable
fun PhotosScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Photos",
        message = "Photo indexing arrives in Phase 2. Once implemented, your device's " +
            "photos will appear here.",
        modifier = modifier,
    )
}
