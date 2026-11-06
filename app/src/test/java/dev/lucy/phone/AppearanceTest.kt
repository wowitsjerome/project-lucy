package dev.lucy.phone

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.lucy.phone.launcher.AppearanceMode
import dev.lucy.phone.launcher.lucyColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class AppearanceTest {
    @Test fun storedModeDefaultsSafelyAndSystemFollowsAndroid() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStored(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStored("UNKNOWN"))
        assertEquals(AppearanceMode.DARK, AppearanceMode.fromStored("DARK"))
        assertTrue(AppearanceMode.SYSTEM.usesDarkTheme(true))
        assertFalse(AppearanceMode.SYSTEM.usesDarkTheme(false))
        assertTrue(AppearanceMode.DARK.usesDarkTheme(false))
        assertFalse(AppearanceMode.LIGHT.usesDarkTheme(true))
    }

    @Test fun coreTextContrastMeetsWcagAa() {
        for (scheme in listOf(lucyColorScheme(false), lucyColorScheme(true))) {
            assertTrue(contrast(scheme.onBackground, scheme.background) >= 4.5)
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 4.5)
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = max(first.luminance(), second.luminance())
        val darker = min(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
