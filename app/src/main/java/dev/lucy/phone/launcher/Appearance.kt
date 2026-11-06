package dev.lucy.phone.launcher

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class AppearanceMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    fun usesDarkTheme(systemIsDark: Boolean) = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

class AppearanceStore(context: Context) {
    private val preferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("appearance", Context.MODE_PRIVATE)

    fun get() = AppearanceMode.fromStored(preferences.getString("mode", null))
    fun set(mode: AppearanceMode) {
        check(preferences.edit().putString("mode", mode.name).commit()) { "Could not save appearance" }
    }
}

internal fun lucyColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFFB7CEB0),
    onPrimary = Color(0xFF243521),
    background = Color(0xFF141714),
    onBackground = Color(0xFFE5E9E2),
    surface = Color(0xFF141714),
    onSurface = Color(0xFFE5E9E2),
    surfaceVariant = Color(0xFF2A2F2A),
    onSurfaceVariant = Color(0xFFC4CBC1),
    outline = Color(0xFF8E968B),
    outlineVariant = Color(0xFF424841),
    error = Color(0xFFFFB4AB),
) else lightColorScheme(
    primary = Color(0xFF52654D),
    onPrimary = Color.White,
    background = Color(0xFFF5F4EF),
    onBackground = Color(0xFF242A25),
    surface = Color(0xFFF5F4EF),
    onSurface = Color(0xFF242A25),
    surfaceVariant = Color(0xFFE2E5DE),
    onSurfaceVariant = Color(0xFF555C53),
    outline = Color(0xFF747C72),
    outlineVariant = Color(0xFFC6CBC2),
    error = Color(0xFFBA1A1A),
)
