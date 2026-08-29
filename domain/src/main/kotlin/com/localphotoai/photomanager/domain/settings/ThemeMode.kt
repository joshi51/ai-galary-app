package com.localphotoai.photomanager.domain.settings

/**
 * User's theme preference. [SYSTEM] defers to the device's dark-mode setting.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    /** Resolves this preference to a concrete light/dark choice given the device's current setting. */
    fun resolveIsDark(systemIsDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemIsDark
    }
}
