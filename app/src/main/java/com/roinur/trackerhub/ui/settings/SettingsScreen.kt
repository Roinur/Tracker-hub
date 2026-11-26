package com.roinur.trackerhub.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.roinur.trackerhub.R
import com.roinur.trackerhub.counter.CounterRepository
import com.roinur.trackerhub.settings.AccentMode
import com.roinur.trackerhub.settings.AppLanguage
import com.roinur.trackerhub.settings.BackupFlowUiState
import com.roinur.trackerhub.settings.StartTab
import com.roinur.trackerhub.settings.ThemeMode
import com.roinur.trackerhub.settings.TrackerHubSettings
import com.roinur.trackerhub.ui.LocalIsSwedish
import com.roinur.trackerhub.ui.accentColorForMode
import com.roinur.trackerhub.ui.counterAccentColor
import com.roinur.trackerhub.ui.tr
import com.roinur.trackerhub.widget.CounterWidgetProvider

private data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String
)

private fun readAppVersionInfo(context: Context): AppVersionInfo {
    val pkg = context.packageName
    return try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(pkg, 0)
        AppVersionInfo(
            versionName = info.versionName ?: "–",
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            packageName = pkg
        )
    } catch (_: Exception) {
        AppVersionInfo(versionName = "–", versionCode = 0L, packageName = pkg)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: TrackerHubSettings,
    counterRepository: CounterRepository,
    backupState: BackupFlowUiState,
    activeCategory: SettingsCategory?,
    query: String,
    onSelectCategory: (SettingsCategory?) -> Unit,
    onQueryChange: (String) -> Unit,
    onBackFromRoot: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentModeChange: (AccentMode) -> Unit,
    onExtraDarkChange: (Boolean) -> Unit,
    onStartTabChange: (StartTab) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenCountersTab: () -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    val context = LocalContext.current
    val storeState by counterRepository.state.collectAsState()
    val versionInfo = remember(context) { readAppVersionInfo(context) }
    val pinSupported = remember(context) { CounterWidgetProvider.isPinWidgetSupported(context) }

    val countersCount = storeState.counters.size
    val totalActiveDays = remember(storeState.counters) {
        storeState.counters.sumOf { c -> c.dailyCounts.values.count { it != 0 } }
    }
    val totalCountSum = remember(storeState.counters) {
        storeState.counters.sumOf { c -> c.dailyCounts.values.sumOf { it.toLong() } }
    }
    val widgetsCount = storeState.widgetBindings.size

    fun handleBack() {
        when {
            query.isNotBlank() -> onQueryChange("")
            activeCategory != null -> onSelectCategory(null)
            else -> onBackFromRoot()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    fun categoryVisible(category: SettingsCategory): Boolean {
        if (query.isBlank()) return activeCategory == category
        val extraTerms = when (category) {
            SettingsCategory.APPEARANCE ->
                "språk language svenska english tema theme system ljust mörkt dark light accentfärg accent bakgrund extra mörkt startsida start appar apps räknare counters"
            SettingsCategory.BACKUP ->
                "säkerhetskopia backup exportera export importera import json zip manifest hive dc återställ restore slå ihop merge ersätt replace lokal"
            SettingsCategory.WIDGETS ->
                "widget hemskärm home uppdatera refresh lägg till pin storlek midnatt midnight nollställning guide"
            SettingsCategory.ABOUT ->
                "om appen about version bygge build paket package lokal lagring storage integritet privacy format sha-256"
        }
        return settingsSearchMatches(query, "${category.titleSv} ${category.titleEn} ${category.summarySv} ${category.summaryEn} $extraTerms")
    }

    val topBarTitle = when {
        query.isNotBlank() -> tr("Sök inställningar", "Search settings")
        activeCategory != null -> activeCategory.title(isSwedish)
        else -> tr("Inställningar", "Settings")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = topBarTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = { handleBack() }) {
                    Icon(
                        imageVector = SettingsBackIcon,
                        contentDescription = "Tillbaka"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        AnimatedContent(
            targetState = activeCategory,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)))
                    .using(null)
            },
            label = "SettingsCategoryTransition"
        ) { shownCategory ->
            val listState = rememberLazyListState()
            fun shownCategoryVisible(category: SettingsCategory): Boolean {
                if (query.isBlank()) return shownCategory == category
                return categoryVisible(category)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (shownCategory == null || query.isNotBlank()) {
                    item(key = "settings-search", contentType = "search") {
                        SettingsSearchBar(
                            query = query,
                            onQuery = onQueryChange
                        )
                    }
                }

                if (shownCategory == null && query.isBlank()) {
                    items(SettingsCategory.entries, key = { it.name }, contentType = { "category" }) { category ->
                        val hint = when (category) {
                            SettingsCategory.APPEARANCE -> {
                                val extra = if (settings.extraDark) tr(isSwedish, " · Extra mörkt", " · Extra dark") else ""
                                "${settings.language.label(isSwedish)} · ${settings.themeMode.label(isSwedish)} · ${settings.accentMode.label(isSwedish)}$extra"
                            }
                            SettingsCategory.BACKUP -> {
                                tr(
                                    isSwedish,
                                    "$countersCount räknare · $totalActiveDays aktiva dagar · JSON & ZIP-import",
                                    "$countersCount counters · $totalActiveDays active days · JSON & ZIP import"
                                )
                            }
                            SettingsCategory.WIDGETS -> {
                                if (widgetsCount == 0) {
                                    tr(isSwedish, "Inga aktiva hemskärmswidgets · guide och genvägar", "No active home widgets · guide and shortcuts")
                                } else {
                                    tr(isSwedish, "$widgetsCount aktiva på hemskärmen · uppdatera och lägg till", "$widgetsCount active on home screen · refresh and add")
                                }
                            }
                            SettingsCategory.ABOUT -> {
                                tr(
                                    isSwedish,
                                    "Version ${versionInfo.versionName} (${versionInfo.versionCode}) · lokal lagring",
                                    "Version ${versionInfo.versionName} (${versionInfo.versionCode}) · local storage"
                                )
                            }
                        }
                        SettingsCategoryRow(
                            category = category,
                            detailHint = hint,
                            onClick = { onSelectCategory(category) }
                        )
                    }
                } else {
                    if (shownCategoryVisible(SettingsCategory.APPEARANCE)) {
                        item(key = "appearance-language") {
                            SettingsOptionSection(
                                category = SettingsCategory.APPEARANCE,
                                title = tr("Språk", "Language"),
                                terms = "språk language svenska english system",
                                active = shownCategory,
                                query = query
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    AppLanguage.entries.forEach { lang ->
                                        FilterChip(
                                            selected = settings.language == lang,
                                            onClick = { onLanguageChange(lang) },
                                            label = { Text(lang.label(isSwedish)) }
                                        )
                                    }
                                }
                                Text(
                                    text = tr(
                                        "Byter språk direkt i appen och på dina hemskärmswidgetar. System följer telefonens språk.",
                                        "Switches language immediately in the app and on your home screen widgets. System follows your device language."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item(key = "appearance-theme") {
                            SettingsOptionSection(
                                category = SettingsCategory.APPEARANCE,
                                title = tr("Tema och färg", "Theme & color"),
                                terms = "tema theme system ljust mörkt dark light accentfärg accent bakgrund extra mörkt",
                                active = shownCategory,
                                query = query
                            ) {
                            Text(
                                text = tr("Temaläge", "Theme mode"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = settings.themeMode == mode,
                                        onClick = { onThemeModeChange(mode) },
                                        label = { Text(mode.label(isSwedish)) }
                                    )
                                }
                            }
                            Text(
                                text = tr(
                                    "System följer telefonens ljusa eller mörka tema automatiskt.",
                                    "System follows your device light or dark theme automatically."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Text(
                                text = tr("Accentfärg", "Accent color"),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AccentMode.entries.forEach { mode ->
                                    val swatch = accentColorForMode(mode)
                                    FilterChip(
                                        selected = settings.accentMode == mode,
                                        onClick = { onAccentModeChange(mode) },
                                        leadingIcon = swatch?.let { color ->
                                            {
                                                Box(
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                )
                                            }
                                        },
                                        label = { Text(mode.label(isSwedish)) }
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = tr("Extra mörkt", "Extra dark"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = tr(
                                            "Djupare bakgrunder i mörkt läge. Behåller din accentfärg.",
                                            "Deeper surfaces in dark mode while keeping your accent color."
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = settings.extraDark,
                                    onCheckedChange = onExtraDarkChange
                                )
                            }
                        }
                    }

                    item(key = "appearance-start-tab") {
                        SettingsOptionSection(
                            category = SettingsCategory.APPEARANCE,
                            title = tr("Startsida", "Start tab"),
                            terms = "startsida startflik start tab appar apps räknare counters navigering",
                            active = shownCategory,
                            query = query
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                StartTab.entries.forEach { tab ->
                                    FilterChip(
                                        selected = settings.startTab == tab,
                                        onClick = { onStartTabChange(tab) },
                                        label = { Text(tab.label(isSwedish)) }
                                    )
                                }
                            }
                            Text(
                                text = tr(
                                    "Välj vilken flik som öppnas när du startar Tracker Hub normalt. Widgets öppnar alltid rätt räknare direkt.",
                                    "Choose which tab opens when launching Tracker Hub. Widgets always open their counter directly."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (shownCategoryVisible(SettingsCategory.BACKUP)) {
                    item(key = "backup-actions") {
                            SettingsOptionSection(
                                category = SettingsCategory.BACKUP,
                                title = tr("Import och export", "Import & export"),
                                terms = "exportera export importera import säkerhetskopia backup json zip återställ restore migrera",
                                active = shownCategory,
                                query = query
                            ) {
                                Text(
                                    text = tr(
                                        "Exportera och importera räknare i Tracker Hubs eget JSON-format (inklusive klockslag och exkluderade dagar). Stöder även import av tidigare .zip-arkiv.",
                                        "Export and import counters in Tracker Hub's native JSON format (including timestamps and excluded days). Also supports importing legacy .zip archives."
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (backupState.busy) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = backupState.busyLabel ?: tr("Bearbetar säkerhetskopia…", "Processing backup…"),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }

                                SettingsActionButton(
                                    onClick = onExportBackup,
                                    enabled = !backupState.busy,
                                    primary = true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Exportera säkerhetskopia (.json)", "Export backup (.json)"))
                                }

                                SettingsActionButton(
                                    onClick = onImportBackup,
                                    enabled = !backupState.busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Importera säkerhetskopia (.json / .zip)", "Import backup (.json / .zip)"))
                                }

                                Text(
                                    text = tr(
                                        "Importen validerar alltid filen och visar en förhandsgranskning där du väljer Slå ihop eller Ersätt innan några data ändras.",
                                        "Import always validates the file and shows a preview where you choose Merge or Replace before changing any data."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                    }

                    item(key = "backup-status") {
                        SettingsOptionSection(
                            category = SettingsCategory.BACKUP,
                            title = tr("Lokal dataöversikt", "Local data overview"),
                            terms = "lokal local data räknare counters dagar days summa total senaste last export import status",
                            active = shownCategory,
                            query = query
                        ) {
                            val lastExportLabel = remember(settings.lastExportEpochMillis, isSwedish) {
                                formatEpochTimestampSv(settings.lastExportEpochMillis)
                                    ?: tr(isSwedish, "Ingen export sparad ännu", "No export saved yet")
                            }
                            val lastImportLabel = remember(settings.lastImportEpochMillis, isSwedish) {
                                formatEpochTimestampSv(settings.lastImportEpochMillis)
                                    ?: tr(isSwedish, "Ingen import gjord ännu", "No import made yet")
                            }
                            SettingsKeyValueRow(tr("Räknare lokalt", "Local counters"), countersCount.toString())
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Aktiva dagar", "Active days"), totalActiveDays.toString())
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Total summa", "Total count"), totalCountSum.toString())
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Senaste export", "Last export"), lastExportLabel)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Senaste import", "Last import"), lastImportLabel)
                        }
                    }
                }

                if (shownCategoryVisible(SettingsCategory.WIDGETS)) {
                    item(key = "widgets-active") {
                        SettingsOptionSection(
                            category = SettingsCategory.WIDGETS,
                            title = tr("Aktiva widgets", "Active widgets"),
                            terms = "aktiva active widgets hemskärm home uppdatera refresh kopplingar",
                            active = shownCategory,
                            query = query
                        ) {
                            val boundCounters = remember(storeState.counters, storeState.widgetBindings) {
                                storeState.counters
                                    .map { it to storeState.widgetCountForCounter(it.id) }
                                    .filter { it.second > 0 }
                            }
                            Text(
                                text = if (widgetsCount == 0) {
                                    tr("Inga aktiva hemskärmswidgets är kopplade ännu.", "No active home screen widgets yet.")
                                } else if (widgetsCount == 1) {
                                    tr("1 aktiv widget på hemskärmen.", "1 active widget on home screen.")
                                } else {
                                    tr("$widgetsCount aktiva widgets på hemskärmen.", "$widgetsCount active widgets on home screen.")
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (boundCounters.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    boundCounters.forEach { (counter, count) ->
                                        val accent = counterAccentColor(counter.colorIndex)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(accent)
                                            )
                                            Text(
                                                text = counter.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = if (count == 1) "1 widget" else "$count widgets",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            SettingsActionButton(
                                onClick = {
                                    counterRepository.syncDateRollover()
                                    CounterWidgetProvider.updateAllWidgets(context)
                                    Toast.makeText(
                                        context,
                                        if (widgetsCount > 0) {
                                            tr(isSwedish, "Alla widgets uppdaterades.", "All widgets refreshed.")
                                        } else {
                                            tr(isSwedish, "Datum och widgetstatus kontrollerade.", "Date and widget status checked.")
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Uppdatera alla widgets nu", "Refresh all widgets now"))
                            }
                        }
                    }

                    item(key = "widgets-pin") {
                        SettingsOptionSection(
                            category = SettingsCategory.WIDGETS,
                            title = tr("Lägg till på hemskärmen", "Add to home screen"),
                            terms = "lägg till add pin widget hemskärm home räknare counters",
                            active = shownCategory,
                            query = query
                        ) {
                            if (storeState.counters.isEmpty()) {
                                Text(
                                    text = tr(
                                        "Skapa minst en räknare under fliken Räknare först, så kan du fästa den direkt på hemskärmen härifrån.",
                                        "Create at least one counter in the Counters tab first, then you can pin it straight to your home screen here."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SettingsActionButton(
                                    onClick = onOpenCountersTab,
                                    primary = true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr("Öppna Räknare", "Open Counters"))
                                }
                            } else if (!pinSupported) {
                                Text(
                                    text = tr(
                                        "Din hemskärm stöder inte direkt fästning från appen. Lägg till widgeten \"Räknare\" via hemskärmens widgetmeny.",
                                        "Your launcher does not support pinning directly from the app. Add the \"Counter\" widget from your home screen widget picker."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = tr(
                                        "Välj en räknare för att lägga till en ny widget direkt på hemskärmen.",
                                        "Choose a counter to add a new widget directly to your home screen."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    storeState.counters.forEach { counter ->
                                        val accent = counterAccentColor(counter.colorIndex)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(accent)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = counter.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = counter.mode.label(isSwedish),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    val requested = CounterWidgetProvider.requestPinForCounter(context, counter.id)
                                                    if (!requested) {
                                                        Toast.makeText(
                                                            context,
                                                            tr(
                                                                isSwedish,
                                                                "Lägg till widgeten \"Räknare\" via hemskärmens widgetmeny.",
                                                                "Add the \"Counter\" widget from your home screen widget picker."
                                                            ),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            ) {
                                                Text(tr("Lägg till", "Add"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "widgets-guide") {
                        SettingsOptionSection(
                            category = SettingsCategory.WIDGETS,
                            title = tr("Så fungerar widgets", "How widgets work"),
                            terms = "guide storlek size midnatt midnight nollställning reset hemskärm home tryck tap öppna statistik stats",
                            active = shownCategory,
                            query = query
                        ) {
                            WidgetGuideBullet(
                                tr(
                                    "Långtryck på hemskärmen, öppna Widgets och välj Tracker Hub → Räknare för att lägga till manuellt.",
                                    "Long-press your home screen, open Widgets, and choose Tracker Hub → Counter to add manually."
                                )
                            )
                            WidgetGuideBullet(
                                tr(
                                    "Widgeten anpassar layouten automatiskt när du ändrar storlek (kvadratisk, bred, hög eller stor).",
                                    "The widget adapts its layout automatically when resized (square, wide, tall, or large)."
                                )
                            )
                            WidgetGuideBullet(
                                tr(
                                    "Räknare med daglig nollställning nollställs automatiskt vid midnatt medan all historik sparas.",
                                    "Daily reset counters automatically reset at midnight while preserving all history."
                                )
                            )
                            WidgetGuideBullet(
                                tr(
                                    "Tryck på räknarens namn eller siffra i widgeten för att öppna statistik, 14-dagarsgraf och historik direkt i appen.",
                                    "Tap the counter name or number on a widget to open statistics, the 14-day chart, and history directly in the app."
                                )
                            )
                        }
                    }
                }

                if (shownCategoryVisible(SettingsCategory.ABOUT)) {
                    item(key = "about-hero") {
                        if (query.isBlank() || settingsSearchMatches(query, "om appen about tracker hub version samling")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.tracker_hub_icon),
                                        contentDescription = tr("Tracker Hub-ikon", "Tracker Hub icon"),
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Tracker Hub",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = tr(
                                                "Dina samlingar, din vardag. Alla trackers och räknare på ett ställe.",
                                                "Your collections, your daily routine. All trackers and counters in one place."
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "about-version") {
                        SettingsOptionSection(
                            category = SettingsCategory.ABOUT,
                            title = tr("Appversion", "App version"),
                            terms = "version bygge build paketnamn package",
                            active = shownCategory,
                            query = query
                        ) {
                            SettingsKeyValueRow("Version", versionInfo.versionName)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Bygge", "Build"), versionInfo.versionCode.toString())
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            SettingsKeyValueRow(tr("Paket", "Package"), versionInfo.packageName)
                        }
                    }

                    item(key = "about-storage") {
                        SettingsOptionSection(
                            category = SettingsCategory.ABOUT,
                            title = tr("Lokal lagring och format", "Local storage & format"),
                            terms = "lokal local lagring storage integritet privacy konto account offline zip json sha-256",
                            active = shownCategory,
                            query = query
                        ) {
                            Text(
                                text = tr(
                                    "Alla räknare, historik och inställningar sparas lokalt på telefonen. Inget konto krävs.",
                                    "All counters, history, and settings are stored locally on your device. No account required."
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = tr(
                                    "Säkerhetskopior sparas i Tracker Hubs eget JSON-format. Import stöder även .zip-arkiv med SHA-256-validering före import.",
                                    "Backups are saved in Tracker Hub's native JSON format. Import also supports .zip archives with SHA-256 verification before import."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (query.isNotBlank() && SettingsCategory.entries.none { categoryVisible(it) }) {
                    item(key = "settings-empty-search") {
                        Text(
                            text = tr("Inga matchande inställningar för \"$query\".", "No matching settings for \"$query\"."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
                        )
                    }
                }
            }

                item(key = "settings-bottom-spacer") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WidgetGuideBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
