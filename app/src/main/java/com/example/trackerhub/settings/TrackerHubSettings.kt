package com.example.trackerhub.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val storageKey: String) {
    SYSTEM("SYSTEM"),
    SV("SV"),
    EN("EN");

    fun label(isSwedish: Boolean): String = when (this) {
        SYSTEM -> "System"
        SV -> "Svenska"
        EN -> "English"
    }

    companion object {
        fun fromStorageKey(value: String?): AppLanguage {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: SYSTEM
        }

        fun isSwedish(language: AppLanguage, systemLocale: Locale = Locale.getDefault()): Boolean = when (language) {
            SV -> true
            EN -> false
            SYSTEM -> systemLocale.language.equals("sv", ignoreCase = true)
        }
    }
}

enum class ThemeMode(val storageKey: String, val labelSv: String, val labelEn: String) {
    SYSTEM("SYSTEM", "System", "System"),
    LIGHT("LIGHT", "Ljust", "Light"),
    DARK("DARK", "Mörkt", "Dark");

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn

    companion object {
        fun fromStorageKey(value: String?): ThemeMode {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: SYSTEM
        }
    }
}

enum class AccentMode(val storageKey: String, val labelSv: String, val labelEn: String) {
    AUTO("AUTO", "Bakgrund", "Wallpaper"),
    TEAL("TEAL", "Turkos", "Teal"),
    BLUE("BLUE", "Blå", "Blue"),
    AMBER("AMBER", "Bärnsten", "Amber"),
    GREEN("GREEN", "Grön", "Green"),
    INDIGO("INDIGO", "Indigo", "Indigo"),
    ORANGE("ORANGE", "Orange", "Orange"),
    RED("RED", "Röd", "Red"),
    PINK("PINK", "Rosa", "Pink");

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn

    companion object {
        fun fromStorageKey(value: String?): AccentMode {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class StartTab(val storageKey: String, val labelSv: String, val labelEn: String) {
    APPS("APPS", "Appar", "Apps"),
    COUNTERS("COUNTERS", "Räknare", "Counters");

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn

    companion object {
        fun fromStorageKey(value: String?): StartTab {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) } ?: APPS
        }
    }
}

data class TrackerHubSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentMode: AccentMode = AccentMode.AUTO,
    val extraDark: Boolean = false,
    val startTab: StartTab = StartTab.APPS,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val lastExportEpochMillis: Long? = null,
    val lastImportEpochMillis: Long? = null
)

class TrackerHubSettingsRepository(
    private val prefs: SharedPreferences
) {
    private val lock = Any()
    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<TrackerHubSettings> = _state.asStateFlow()

    fun current(): TrackerHubSettings = synchronized(lock) { _state.value }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setAccentMode(mode: AccentMode) = update { it.copy(accentMode = mode) }

    fun setExtraDark(enabled: Boolean) = update { it.copy(extraDark = enabled) }

    fun setStartTab(tab: StartTab) = update { it.copy(startTab = tab) }

    fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    fun recordExportSuccess(epochMillis: Long = System.currentTimeMillis()) = update {
        it.copy(lastExportEpochMillis = epochMillis)
    }

    fun recordImportSuccess(epochMillis: Long = System.currentTimeMillis()) = update {
        it.copy(lastImportEpochMillis = epochMillis)
    }

    private fun update(transform: (TrackerHubSettings) -> TrackerHubSettings) {
        synchronized(lock) {
            val next = transform(_state.value)
            if (next == _state.value) return
            persistLocked(next)
            _state.value = next
        }
    }

    private fun loadFromPrefs(): TrackerHubSettings {
        val lastExport = prefs.getLong(KEY_LAST_EXPORT_MS, -1L).takeIf { it > 0L }
        val lastImport = prefs.getLong(KEY_LAST_IMPORT_MS, -1L).takeIf { it > 0L }
        return TrackerHubSettings(
            themeMode = ThemeMode.fromStorageKey(prefs.getString(KEY_THEME_MODE, null)),
            accentMode = AccentMode.fromStorageKey(prefs.getString(KEY_ACCENT_MODE, null)),
            extraDark = prefs.getBoolean(KEY_EXTRA_DARK, false),
            startTab = StartTab.fromStorageKey(prefs.getString(KEY_START_TAB, null)),
            language = AppLanguage.fromStorageKey(prefs.getString(KEY_LANGUAGE, null)),
            lastExportEpochMillis = lastExport,
            lastImportEpochMillis = lastImport
        )
    }

    private fun persistLocked(settings: TrackerHubSettings) {
        prefs.edit()
            .putString(KEY_THEME_MODE, settings.themeMode.storageKey)
            .putString(KEY_ACCENT_MODE, settings.accentMode.storageKey)
            .putBoolean(KEY_EXTRA_DARK, settings.extraDark)
            .putString(KEY_START_TAB, settings.startTab.storageKey)
            .putString(KEY_LANGUAGE, settings.language.storageKey)
            .apply {
                if (settings.lastExportEpochMillis != null) {
                    putLong(KEY_LAST_EXPORT_MS, settings.lastExportEpochMillis)
                } else {
                    remove(KEY_LAST_EXPORT_MS)
                }
                if (settings.lastImportEpochMillis != null) {
                    putLong(KEY_LAST_IMPORT_MS, settings.lastImportEpochMillis)
                } else {
                    remove(KEY_LAST_IMPORT_MS)
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "trackerhub_settings_v1"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT_MODE = "accent_mode"
        private const val KEY_EXTRA_DARK = "extra_dark"
        private const val KEY_START_TAB = "start_tab"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_LAST_EXPORT_MS = "last_export_ms"
        private const val KEY_LAST_IMPORT_MS = "last_import_ms"

        @Volatile
        private var instance: TrackerHubSettingsRepository? = null

        fun getInstance(context: Context): TrackerHubSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: TrackerHubSettingsRepository(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
