package com.localphotoai.photomanager.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persists user-facing app preferences. Implemented in `:data:preferences` on top of DataStore.
 */
interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}
