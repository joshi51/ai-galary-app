package com.localphotoai.photomanager.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.localphotoai.photomanager.core.ui.component.PlaceholderScreen

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "Search",
        message = "Structured search arrives in Phase 6, natural-language search in " +
            "Phase 8. Both run entirely on-device.",
        modifier = modifier,
    )
}
