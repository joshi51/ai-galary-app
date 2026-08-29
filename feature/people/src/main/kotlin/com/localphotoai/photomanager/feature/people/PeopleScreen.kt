package com.localphotoai.photomanager.feature.people

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.localphotoai.photomanager.core.ui.component.PlaceholderScreen

@Composable
fun PeopleScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "People",
        message = "Face detection, embeddings, and clustering arrive in Phases 3–5. " +
            "Once implemented, people discovered in your photos will appear here.",
        modifier = modifier,
    )
}
