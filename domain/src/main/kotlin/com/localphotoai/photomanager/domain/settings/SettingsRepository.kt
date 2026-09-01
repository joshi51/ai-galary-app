package com.localphotoai.photomanager.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persists user-facing app preferences. Implemented in `:data:preferences` on top of DataStore.
 */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Null if the user hasn't saved a search location yet. */
    fun observeSavedSearchLocation(): Flow<SavedSearchLocation?>
    suspend fun setSavedSearchLocation(latitude: Double, longitude: Double, radiusKm: Double)
    suspend fun clearSavedSearchLocation()
}
