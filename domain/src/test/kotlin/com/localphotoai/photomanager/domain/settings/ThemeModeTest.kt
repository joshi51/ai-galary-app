package com.localphotoai.photomanager.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `LIGHT always resolves to not dark, regardless of system setting`() {
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemIsDark = true))
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemIsDark = false))
    }

    @Test
    fun `DARK always resolves to dark, regardless of system setting`() {
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemIsDark = true))
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemIsDark = false))
    }

    @Test
    fun `SYSTEM follows the device setting`() {
        assertEquals(true, ThemeMode.SYSTEM.resolveIsDark(systemIsDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveIsDark(systemIsDark = false))
    }
}
