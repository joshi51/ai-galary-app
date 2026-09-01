package com.localphotoai.photomanager.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.localphotoai.photomanager.domain.settings.SavedSearchLocation
import com.localphotoai.photomanager.domain.settings.SettingsRepository
import com.localphotoai.photomanager.domain.settings.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val SEARCH_LOCATION_LAT_KEY = doublePreferencesKey("search_location_lat")
private val SEARCH_LOCATION_LON_KEY = doublePreferencesKey("search_location_lon")
private val SEARCH_LOCATION_RADIUS_KM_KEY = doublePreferencesKey("search_location_radius_km")

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override fun observeThemeMode(): Flow<ThemeMode> =
        dataStore.data.map { prefs ->
            prefs[THEME_MODE_KEY]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    override fun observeSavedSearchLocation(): Flow<SavedSearchLocation?> =
        dataStore.data.map { prefs ->
            val lat = prefs[SEARCH_LOCATION_LAT_KEY]
            val lon = prefs[SEARCH_LOCATION_LON_KEY]
            val radius = prefs[SEARCH_LOCATION_RADIUS_KM_KEY]
            if (lat != null && lon != null && radius != null) {
                SavedSearchLocation(latitude = lat, longitude = lon, radiusKm = radius)
            } else {
                null
            }
        }

    override suspend fun setSavedSearchLocation(latitude: Double, longitude: Double, radiusKm: Double) {
        dataStore.edit { prefs ->
            prefs[SEARCH_LOCATION_LAT_KEY] = latitude
            prefs[SEARCH_LOCATION_LON_KEY] = longitude
            prefs[SEARCH_LOCATION_RADIUS_KM_KEY] = radiusKm
        }
    }

    override suspend fun clearSavedSearchLocation() {
        dataStore.edit { prefs ->
            prefs.remove(SEARCH_LOCATION_LAT_KEY)
            prefs.remove(SEARCH_LOCATION_LON_KEY)
            prefs.remove(SEARCH_LOCATION_RADIUS_KM_KEY)
        }
    }
}
