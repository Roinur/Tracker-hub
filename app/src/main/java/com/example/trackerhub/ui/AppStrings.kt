package com.example.trackerhub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

internal val LocalIsSwedish = staticCompositionLocalOf { true }

@Composable
internal fun tr(sv: String, en: String): String = if (LocalIsSwedish.current) sv else en

internal fun tr(isSwedish: Boolean, sv: String, en: String): String = if (isSwedish) sv else en

internal fun activeAppLocale(isSwedish: Boolean): Locale = if (isSwedish) Locale("sv", "SE") else Locale.ENGLISH
