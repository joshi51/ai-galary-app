package com.localphotoai.photomanager.core.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's top-level (bottom-nav) destinations. Shared between `:app`'s NavHost/bottom bar
 * and anything else that needs to enumerate or route between the five main screens.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(route = "home", label = "Home", icon = Icons.Filled.Home),
    PHOTOS(route = "photos", label = "Photos", icon = Icons.Filled.Photo),
    PEOPLE(route = "people", label = "People", icon = Icons.Filled.Person),
    SEARCH(route = "search", label = "Search", icon = Icons.Filled.Search),
    SETTINGS(route = "settings", label = "Settings", icon = Icons.Filled.Settings),
}
