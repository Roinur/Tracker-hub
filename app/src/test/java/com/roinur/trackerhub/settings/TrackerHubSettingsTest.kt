package com.roinur.trackerhub.settings

import com.roinur.trackerhub.ui.resolveDarkTheme
import com.roinur.trackerhub.ui.settings.SettingsCategory
import com.roinur.trackerhub.ui.settings.settingsSearchMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerHubSettingsTest {

    @Test
    fun storageKeysParseCaseInsensitivelyWithSafeDefaults() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStorageKey("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStorageKey("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey("unknown"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageKey(null))

        assertEquals(AccentMode.TEAL, AccentMode.fromStorageKey("teal"))
        assertEquals(AccentMode.INDIGO, AccentMode.fromStorageKey("INDIGO"))
        assertEquals(AccentMode.AUTO, AccentMode.fromStorageKey("invalid"))

        assertEquals(StartTab.COUNTERS, StartTab.fromStorageKey("counters"))
        assertEquals(StartTab.APPS, StartTab.fromStorageKey(null))
    }

    @Test
    fun resolveDarkThemeHonorsOverrideAndSystemFallback() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun settingsCategoryAndSearchMatchingWorkForMultiWordQueries() {
        assertEquals(SettingsCategory.BACKUP, SettingsCategory.fromNameOrNull("BACKUP"))
        assertNull(SettingsCategory.fromNameOrNull("UNKNOWN"))
        assertNull(SettingsCategory.fromNameOrNull(null))

        assertTrue(settingsSearchMatches("zip import", "Exportera och importera i ZIP-format"))
        assertTrue(settingsSearchMatches("  EXTRA   mörkt ", "Tema, accentfärg och extra mörkt"))
        assertFalse(settingsSearchMatches("molnbackup", "Lokal lagring och ZIP-format"))
    }
}
