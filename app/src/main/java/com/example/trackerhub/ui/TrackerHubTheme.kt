package com.example.trackerhub.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.trackerhub.settings.AccentMode
import com.example.trackerhub.settings.AppLanguage
import com.example.trackerhub.settings.ThemeMode
import com.example.trackerhub.settings.TrackerHubSettings
import com.example.trackerhub.settings.TrackerHubSettingsRepository

val CounterAccentLightPalette = listOf(
    Color(0xFF176B5B),
    Color(0xFF4267D9),
    Color(0xFF9B5C2E),
    Color(0xFF53764B),
    Color(0xFF7A4E8E),
    Color(0xFFB14A4A)
)

val CounterAccentDarkPalette = listOf(
    Color(0xFF85DCC2),
    Color(0xFF9FB7FF),
    Color(0xFFE7B98E),
    Color(0xFFA8D39E),
    Color(0xFFD6AEEC),
    Color(0xFFF2A4A4)
)

internal val LocalTrackerHubDarkTheme = staticCompositionLocalOf { false }

private val ExtraDarkBackground = Color(0xFF0B0A12)

@Composable
fun counterAccentColor(colorIndex: Int): Color {
    val palette = if (LocalTrackerHubDarkTheme.current) CounterAccentDarkPalette else CounterAccentLightPalette
    return palette[colorIndex.floorMod(palette.size)]
}

private fun Int.floorMod(modulus: Int): Int {
    val r = this % modulus
    return if (r < 0) r + modulus else r
}

internal fun resolveDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

internal fun accentColorForMode(mode: AccentMode): Color? = when (mode) {
    AccentMode.AUTO -> null
    AccentMode.TEAL -> Color(0xFF00897B)
    AccentMode.BLUE -> Color(0xFF1E88E5)
    AccentMode.AMBER -> Color(0xFFF9A825)
    AccentMode.GREEN -> Color(0xFF43A047)
    AccentMode.INDIGO -> Color(0xFF5E35B1)
    AccentMode.ORANGE -> Color(0xFFFB8C00)
    AccentMode.RED -> Color(0xFFE53935)
    AccentMode.PINK -> Color(0xFFD81B60)
}

internal fun preferredOnAccent(color: Color): Color {
    val luminance = (0.299f * color.red) + (0.587f * color.green) + (0.114f * color.blue)
    return if (luminance >= 0.62f) Color(0xFF111111) else Color.White
}

internal fun applyExtraDarkMode(
    baseScheme: ColorScheme,
    isDark: Boolean,
    enabled: Boolean
): ColorScheme = if (isDark && enabled) {
    baseScheme.copy(
        background = ExtraDarkBackground,
        onBackground = Color(0xFFE5E1EF),
        surface = Color(0xFF14121E),
        onSurface = Color(0xFFE5E1EF),
        onSurfaceVariant = Color(0xFFB9B3CC),
        surfaceVariant = Color(0xFF201D2C),
        surfaceContainerLowest = Color(0xFF09080F),
        surfaceContainerLow = Color(0xFF11101A),
        surfaceContainer = Color(0xFF181620),
        surfaceContainerHigh = Color(0xFF211E2B),
        surfaceContainerHighest = Color(0xFF2A2735),
        outline = Color(0xFF716A83),
        outlineVariant = Color(0xFF302B40)
    )
} else {
    baseScheme
}

internal fun applyAccentMode(
    baseScheme: ColorScheme,
    accentMode: AccentMode,
    isDark: Boolean
): ColorScheme {
    val accent = accentColorForMode(accentMode) ?: return baseScheme
    val container = accent.copy(alpha = if (isDark) 0.34f else 0.18f)
    return baseScheme.copy(
        primary = accent,
        onPrimary = preferredOnAccent(accent),
        secondary = accent,
        tertiary = accent,
        primaryContainer = container,
        onPrimaryContainer = baseScheme.onSurface,
        secondaryContainer = container,
        onSecondaryContainer = baseScheme.onSurface,
        tertiaryContainer = container,
        onTertiaryContainer = baseScheme.onSurface
    )
}

@Composable
fun TrackerHubTheme(
    settings: TrackerHubSettings? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resolvedSettings = if (settings != null) {
        settings
    } else {
        val repo = remember(context) { TrackerHubSettingsRepository.getInstance(context) }
        val state by repo.state.collectAsState()
        state
    }

    val useDark = resolveDarkTheme(resolvedSettings.themeMode, isSystemInDarkTheme())
    val fallbackScheme = if (useDark) {
        darkColorScheme(
            primary = Color(0xFF85DCC2),
            secondary = Color(0xFF9FB7FF),
            tertiary = Color(0xFFE7B98E),
            background = Color(0xFF151916),
            surface = Color(0xFF202720),
            surfaceVariant = Color(0xFF29322A),
            surfaceContainerLowest = Color(0xFF101411),
            surfaceContainerLow = Color(0xFF1C221D),
            surfaceContainer = Color(0xFF222923),
            surfaceContainerHigh = Color(0xFF2A322B),
            surfaceContainerHighest = Color(0xFF323B33),
            onSurfaceVariant = Color(0xFFC1CAC1),
            outlineVariant = Color(0xFF3D473E)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF176B5B),
            secondary = Color(0xFF4267D9),
            tertiary = Color(0xFF9B5C2E),
            background = Color(0xFFF8FAF7),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE8EFE9),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFFFFFFF),
            surfaceContainer = Color(0xFFF1F5F0),
            surfaceContainerHigh = Color(0xFFE9EFE8),
            surfaceContainerHighest = Color(0xFFE1E8E0),
            onSurfaceVariant = Color(0xFF5F675F),
            outlineVariant = Color(0xFFD5DDD4)
        )
    }

    val baseScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && resolvedSettings.accentMode == AccentMode.AUTO) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        fallbackScheme
    }
    val colorScheme = applyAccentMode(
        baseScheme = applyExtraDarkMode(baseScheme, useDark, resolvedSettings.extraDark),
        accentMode = resolvedSettings.accentMode,
        isDark = useDark
    )

    val isSwedish = AppLanguage.isSwedish(resolvedSettings.language)

    CompositionLocalProvider(
        LocalTrackerHubDarkTheme provides useDark,
        LocalIsSwedish provides isSwedish
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            ApplySystemBars(darkContent = !useDark, barColor = MaterialTheme.colorScheme.background)
            content()
        }
    }
}

@Composable
fun ApplySystemBars(darkContent: Boolean, barColor: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        val argb = barColor.toArgb()
        @Suppress("DEPRECATION")
        window.statusBarColor = argb
        @Suppress("DEPRECATION")
        window.navigationBarColor = argb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(argb))
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkContent
            isAppearanceLightNavigationBars = darkContent
        }
    }
}
