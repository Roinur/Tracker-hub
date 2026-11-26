package com.roinur.trackerhub.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roinur.trackerhub.counter.CounterItem
import com.roinur.trackerhub.R
import com.roinur.trackerhub.counter.CounterMode
import com.roinur.trackerhub.counter.CounterRepository
import com.roinur.trackerhub.counter.CounterStats
import com.roinur.trackerhub.counter.CounterStatsCalculator
import com.roinur.trackerhub.counter.DailyEntry
import com.roinur.trackerhub.widget.CounterWidgetProvider
import com.roinur.trackerhub.widget.WidgetBackgroundStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SwedishLocale = Locale("sv", "SE")
private val ShortMonthDayFormatter = DateTimeFormatter.ofPattern("d MMM", SwedishLocale)
private val HistoryDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", SwedishLocale)
private val HistoryTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", SwedishLocale)
private val StarterSuggestions = listOf("Vatten", "Kaffe", "Träning", "Sidor", "Pauser")

@Composable
fun CountersTabScreen(
    repository: CounterRepository,
    resumeTick: Int,
    selectedCounterId: String?,
    onSelectCounter: (String?) -> Unit,
    onImportBackup: () -> Unit,
    backupBusy: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeOrder = remember { Mutex() }
    fun runStoreWrite(errorText: String = "Kunde inte spara räknaren.", block: () -> Unit) {
        scope.launch {
            try {
                writeOrder.withLock {
                    withContext(Dispatchers.IO) {
                        block()
                    }
                }
            } catch (_: java.io.IOException) {
                Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun adjust(id: String, delta: Int) {
        runStoreWrite {
            repository.adjustCounter(id, delta)
            CounterWidgetProvider.updateWidgetsForCounter(context, id)
        }
    }
    val storeState by repository.state.collectAsState()
    val today = remember(resumeTick, storeState.lastObservedDate) { repository.currentDate() }

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var createInitialName by rememberSaveable { mutableStateOf("") }
    var editingCounterId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingCounterId by rememberSaveable { mutableStateOf<String?>(null) }
    var resettingTodayCounterId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedCounter = storeState.counterById(selectedCounterId)
    val editingCounter = storeState.counterById(editingCounterId)
    val deletingCounter = storeState.counterById(deletingCounterId)
    val resettingTodayCounter = storeState.counterById(resettingTodayCounterId)

    val activeCounterId = selectedCounter?.id
    var showStatistics by rememberSaveable(activeCounterId) { mutableStateOf(false) }

    BackHandler(enabled = activeCounterId != null) {
        if (showStatistics) {
            showStatistics = false
        } else {
            onSelectCounter(null)
        }
    }

    AnimatedContent(
        targetState = Pair(activeCounterId, showStatistics),
        transitionSpec = {
            (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)))
                .using(null)
        },
        label = "CounterViewTransition"
    ) { (targetId, showingStats) ->
        val targetCounter = storeState.counterById(targetId)
        val retainedCounter = remember(targetId) { mutableStateOf(targetCounter) }
        if (targetCounter != null) {
            retainedCounter.value = targetCounter
        }
        val detailCounter = targetCounter ?: retainedCounter.value

        if (targetId != null && detailCounter != null && showingStats) {
            CounterStatisticsScreen(
                counter = detailCounter,
                today = today,
                onBack = { showStatistics = false }
            )
        } else if (targetId != null && detailCounter != null) {
            CounterDetailView(
                counter = detailCounter,
                today = today,
                onBack = { onSelectCounter(null) },
                onOpenStatistics = { showStatistics = true },
                onAdjust = { delta -> adjust(detailCounter.id, delta) },
                onResetToday = { resettingTodayCounterId = detailCounter.id },
                onToggleExcludeDate = { date, excluded ->
                    runStoreWrite {
                        repository.setDateExcludedFromStats(detailCounter.id, date, excluded)
                    }
                },
                onEdit = { editingCounterId = detailCounter.id },
                onDelete = { deletingCounterId = detailCounter.id },
                onPinWidget = {
                    val requested = CounterWidgetProvider.requestPinForCounter(context, detailCounter.id)
                    if (!requested) {
                        Toast.makeText(
                            context,
                            "Lägg till widgeten \"Räknare\" via hemskärmens widgetmeny.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } else {
            CountersListView(
                counters = storeState.counters,
                today = today,
                backupBusy = backupBusy,
                onOpenCounter = { onSelectCounter(it.id) },
                onAdjust = { counter, delta -> adjust(counter.id, delta) },
                onOpenCreate = { initialName ->
                    createInitialName = initialName
                    showCreateDialog = true
                },
                onImportBackup = onImportBackup
            )
        }
    }

    if (showCreateDialog) {
        CounterEditorDialog(
            title = tr("Ny räknare", "New counter"),
            confirmLabel = tr("Skapa", "Create"),
            initialName = createInitialName,
            initialMode = CounterMode.DAILY_RESET,
            initialColorIndex = storeState.counters.size % (CounterRepository.MAX_COLOR_INDEX + 1),
            initialWidgetBackgroundFile = null,
            showSuggestions = true,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, mode, colorIndex, bgFile ->
                runStoreWrite {
                    repository.createCounter(
                        name = name,
                        mode = mode,
                        colorIndex = colorIndex,
                        widgetBackgroundFile = bgFile
                    )
                    CounterWidgetProvider.updateAllWidgets(context)
                }
                showCreateDialog = false
            }
        )
    }

    editingCounter?.let { target ->
        CounterEditorDialog(
            title = tr("Redigera räknare", "Edit counter"),
            confirmLabel = tr("Spara", "Save"),
            initialName = target.name,
            initialMode = target.mode,
            initialColorIndex = target.colorIndex,
            initialWidgetBackgroundFile = target.widgetBackgroundFile,
            showSuggestions = false,
            onDismiss = { editingCounterId = null },
            onConfirm = { name, mode, colorIndex, bgFile ->
                val oldBg = target.widgetBackgroundFile
                runStoreWrite {
                    repository.updateCounter(
                        counterId = target.id,
                        name = name,
                        mode = mode,
                        colorIndex = colorIndex,
                        widgetBackgroundFile = bgFile,
                        updateWidgetBackground = true
                    )
                    if (oldBg != null && oldBg != bgFile) {
                        WidgetBackgroundStorage.deleteFile(context, oldBg)
                    }
                    CounterWidgetProvider.updateWidgetsForCounter(context, target.id)
                }
                editingCounterId = null
            }
        )
    }

    deletingCounter?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingCounterId = null },
            title = { Text(tr("Ta bort \"${target.name}\"?", "Delete \"${target.name}\"?")) },
            text = {
                Text(
                    tr(
                        "All historik och statistik för denna räknare tas bort permanent. Eventuella widgets på hemskärmen kan kopplas om till en annan räknare.",
                        "All history and statistics for this counter will be permanently deleted. Any home screen widgets can be reassigned to another counter."
                    )
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        val oldBg = target.widgetBackgroundFile
                        runStoreWrite {
                            repository.deleteCounter(target.id)
                            WidgetBackgroundStorage.deleteFile(context, oldBg)
                            CounterWidgetProvider.updateAllWidgets(context)
                        }
                        if (selectedCounterId == target.id) {
                            onSelectCounter(null)
                        }
                        deletingCounterId = null
                    }
                ) {
                    Text(tr("Ta bort", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCounterId = null }) {
                    Text(tr("Avbryt", "Cancel"))
                }
            }
        )
    }

    resettingTodayCounter?.let { target ->
        val todayAmount = target.countForDate(today)
        AlertDialog(
            onDismissRequest = { resettingTodayCounterId = null },
            title = { Text(tr("Nollställ idag för \"${target.name}\"?", "Reset today for \"${target.name}\"?")) },
            text = {
                Text(
                    tr(
                        "Dagens värde ($todayAmount) och alla registrerade klockslag från idag tas bort. Tidigare dagar påverkas inte.",
                        "Today's count ($todayAmount) and all timestamps from today will be cleared. Earlier days are not affected."
                    )
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        runStoreWrite {
                            repository.resetToday(target.id)
                            CounterWidgetProvider.updateWidgetsForCounter(context, target.id)
                        }
                        resettingTodayCounterId = null
                    }
                ) {
                    Text(tr("Nollställ", "Reset"))
                }
            },
            dismissButton = {
                TextButton(onClick = { resettingTodayCounterId = null }) {
                    Text(tr("Avbryt", "Cancel"))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountersListView(
    counters: List<CounterItem>,
    today: LocalDate,
    backupBusy: Boolean,
    onOpenCounter: (CounterItem) -> Unit,
    onAdjust: (CounterItem, Int) -> Unit,
    onOpenCreate: (String) -> Unit,
    onImportBackup: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (counters.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = tr("Skapa din första räknare", "Create your first counter"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            StarterSuggestions.forEach { label ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onOpenCreate(label) },
                                    label = { Text("+ $label") }
                                )
                            }
                        }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !backupBusy,
                            onClick = onImportBackup
                        ) {
                            Text(tr("Importera säkerhetskopia (.json / .zip)", "Import backup (.json / .zip)"))
                        }
                    }
                }
            }
        } else {
            items(counters, key = { it.id }) { counter ->
                CounterOverviewCard(
                    counter = counter,
                    today = today,
                    onClick = { onOpenCounter(counter) },
                    onIncrement = { onAdjust(counter, +1) }
                )
            }
        }

        item(key = "add-counter-action") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { onOpenCreate("") }) {
                    Text(tr("+ Ny", "+ New"))
                }
            }
        }

        item { Spacer(modifier = Modifier.height(76.dp)) }
    }
}

@Composable
private fun CounterOverviewCard(
    counter: CounterItem,
    today: LocalDate,
    onClick: () -> Unit,
    onIncrement: () -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    val accent = counterAccentColor(counter.colorIndex)
    val secondary = when (counter.mode) {
        CounterMode.DAILY_RESET -> tr(isSwedish, "Igår ", "Yesterday ") + counter.countForDate(today.minusDays(1))
        CounterMode.CUMULATIVE -> tr(isSwedish, "Idag ", "Today ") + formatSignedCount(counter.todayCount(today))
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(width = 5.dp, height = 38.dp).clip(CircleShape).background(accent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(counter.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(counter.primaryDisplayValue(today).toString(), style = MaterialTheme.typography.headlineSmall)
            FilledTonalButton(
                onClick = onIncrement,
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = .18f))
            ) {
                Text("+", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun CounterDetailView(
    counter: CounterItem,
    today: LocalDate,
    onBack: () -> Unit,
    onOpenStatistics: () -> Unit,
    onAdjust: (Int) -> Unit,
    onResetToday: () -> Unit,
    onToggleExcludeDate: (LocalDate, Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPinWidget: () -> Unit
) {
    val context = LocalContext.current
    val accent = counterAccentColor(counter.colorIndex)
    val stats by produceState<CounterStats?>(null, counter, today) {
        value = withContext(Dispatchers.Default) { CounterStatsCalculator.calculate(counter, today) }
    }
    val primaryValue = counter.primaryDisplayValue(today)
    val pinSupported = remember { CounterWidgetProvider.isPinWidgetSupported(context) }
    var expandedDate by rememberSaveable(counter.id) { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("← Räknare", "← Counters"), fontWeight = FontWeight.SemiBold)
                }
                Row {
                    TextButton(onClick = onEdit) {
                        Text(tr("Redigera", "Edit"))
                    }
                    TextButton(onClick = onDelete) {
                        Text(tr("Ta bort", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Text(
                            text = counter.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = primaryValue.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val todayTime = stats?.todayEventsDescending?.firstOrNull()?.timestampEpochMillis
                    Text(
                        counter.mode.shortBadgeSv + (todayTime?.let { " · " + formatEventTime(it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onAdjust(-1) },
                            enabled = counter.canDecrement(today),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Text("− 1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onAdjust(+1) },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Text("+ 1", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pinSupported) {
                            OutlinedButton(
                                onClick = onPinWidget,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(tr("Lägg på hemskärmen", "Add to home screen"))
                            }
                        }
                        if (counter.countForDate(today) != 0) {
                            TextButton(onClick = onResetToday) {
                                Text(tr("Nollställ idag", "Reset today"))
                            }
                        }
                    }
                }
            }
        }

        stats?.let { summary ->
            item { StatsGridSection(stats = summary, today = today) }
            item {
                Last14DaysChartCard(
                    series = summary.last14DaysSeries,
                    accent = accent,
                    today = today,
                    onClick = onOpenStatistics
                )
            }
            item { Text(tr("Historik", "History"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            if (summary.historyDescending.isEmpty()) {
                item { Text(tr("Ingen historik", "No history yet"), style = MaterialTheme.typography.bodyMedium) }
            }
            val expanded = summary.historyDescending.firstOrNull { it.date.toEpochDay() == expandedDate }
            val split = expanded?.let { summary.historyDescending.indexOf(it) + 1 } ?: summary.historyDescending.size
            val daysBefore = summary.historyDescending.subList(0, split)
            val daysAfter = summary.historyDescending.subList(split, summary.historyDescending.size)
            fun toggle(date: Long) { expandedDate = if (expandedDate == date) null else date }
            items(daysBefore, key = { it.date.toEpochDay() }) { entry ->
                CounterHistoryDay(entry, today, expandedDate == entry.date.toEpochDay()) { toggle(entry.date.toEpochDay()) }
            }
            if (expanded != null) {
                item(key = "exclude:${expanded.date}") {
                    Surface(
                        onClick = { onToggleExcludeDate(expanded.date, !expanded.excludedFromStats) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Checkbox(
                                checked = expanded.excludedFromStats,
                                onCheckedChange = { checked -> onToggleExcludeDate(expanded.date, checked) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("Exkludera från statistik", "Exclude from statistics"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = tr("Döljs i grafer, snitt och svit (totalen behålls)", "Hidden from charts, averages, and streaks (total kept)"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                val newestFirst = expanded.events.asReversed()
                items(newestFirst.size, key = { "event:${expanded.date}:$it" }, contentType = { "event" }) { index ->
                    val event = newestFirst[index]
                    CounterHistoryTime(event.timestampEpochMillis, event.delta)
                }
                if (expanded.untimedCount != 0 || newestFirst.isEmpty()) {
                    item(key = "untimed:${expanded.date}") {
                        Text("Utan klockslag · ${formatSignedCount(expanded.untimedCount)}", Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(daysAfter, key = { it.date.toEpochDay() }) { entry ->
                CounterHistoryDay(entry, today, false) { toggle(entry.date.toEpochDay()) }
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun StatsGridSection(stats: CounterStats, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatMetricCard(
                label = "Totalt",
                value = stats.totalCount.toString(),
                subtitle = "${stats.activeDaysCount} aktiva dagar",
                modifier = Modifier.weight(1f)
            )
            StatMetricCard(
                label = "7 dagar",
                value = stats.last7DaysCount.toString(),
                subtitle = "Snitt ${formatOneDecimalSv(stats.averageLast7Days)}/dag",
                modifier = Modifier.weight(1f)
            )
            StatMetricCard(
                label = "30 dagar",
                value = stats.last30DaysCount.toString(),
                subtitle = "Månaden: ${stats.thisMonthCount}",
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bestLabel = stats.bestDay?.let { formatShortDateSv(it.date, today) } ?: "Ingen data"
            StatMetricCard(
                label = "Bästa dag",
                value = stats.bestDay?.count?.toString() ?: "–",
                subtitle = bestLabel,
                modifier = Modifier.weight(1f)
            )
            StatMetricCard(
                label = "Svit",
                value = "${stats.currentStreakDays} d",
                subtitle = "Bäst ${stats.bestStreakDays} d · snitt ${formatOneDecimalSv(stats.averagePerActiveDay)}",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatMetricCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Last14DaysChartCard(
    series: List<DailyEntry>,
    accent: Color,
    today: LocalDate,
    onClick: () -> Unit
) {
    val maxVal = (series.maxOfOrNull { kotlin.math.abs(it.count.toLong()) } ?: 0).coerceAtLeast(1)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr("Senaste 14 dagarna", "Last 14 days"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("Max $maxVal · Statistik ›", "Max $maxVal · Statistics ›"),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                series.forEach { entry ->
                    val magnitude = kotlin.math.abs(entry.count.toLong())
                    val fraction = if (magnitude == 0L) 0.05f else (magnitude.toFloat() / maxVal.toFloat()).coerceIn(0.14f, 1f)
                    val isToday = entry.date == today
                    val barColor = when {
                        magnitude == 0L -> MaterialTheme.colorScheme.surfaceVariant
                        entry.count < 0 -> MaterialTheme.colorScheme.error
                        isToday -> accent
                        else -> accent.copy(alpha = 0.55f)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (magnitude > 0L) {
                            Text(
                                text = entry.count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction * 0.78f)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(barColor)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val firstLabel = series.firstOrNull()?.date?.format(ShortMonthDayFormatter).orEmpty()
                val midLabel = series.getOrNull(series.size / 2)?.date?.format(ShortMonthDayFormatter).orEmpty()
                Text(firstLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(midLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Idag", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CounterHistoryDay(entry: DailyEntry, today: LocalDate, expanded: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatHistoryDateSv(entry.date, today), style = MaterialTheme.typography.bodyMedium)
                if (entry.excludedFromStats) {
                    Text(
                        text = "Exkluderad från statistik",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = entry.count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (entry.excludedFromStats) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Icon(painterResource(R.drawable.ic_history_expand), if (expanded) "Dölj klockslag" else "Visa klockslag",
                Modifier.size(20.dp).rotate(if (expanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CounterHistoryTime(epochMillis: Long, amount: Int) {
    val time = remember(epochMillis) { formatEventTime(epochMillis) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(time, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(formatSignedCount(amount), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatEventTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(HistoryTimeFormatter)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CounterEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialMode: CounterMode,
    initialColorIndex: Int,
    initialWidgetBackgroundFile: String?,
    showSuggestions: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, mode: CounterMode, colorIndex: Int, widgetBackgroundFile: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSwedish = LocalIsSwedish.current
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var modeName by rememberSaveable(initialMode.name) { mutableStateOf(initialMode.name) }
    var colorIndex by rememberSaveable(initialColorIndex) { mutableIntStateOf(initialColorIndex) }
    var widgetBgFile by rememberSaveable(initialWidgetBackgroundFile) { mutableStateOf(initialWidgetBackgroundFile) }
    var importingImage by remember { mutableStateOf(false) }
    val mode = CounterMode.entries.firstOrNull { it.name == modeName } ?: initialMode

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importingImage = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    WidgetBackgroundStorage.importFromUri(context, uri)
                }
                importingImage = false
                if (saved != null) {
                    if (widgetBgFile != null && widgetBgFile != initialWidgetBackgroundFile) {
                        WidgetBackgroundStorage.deleteFile(context, widgetBgFile)
                    }
                    widgetBgFile = saved
                } else {
                    Toast.makeText(
                        context,
                        tr(isSwedish, "Kunde inte läsa vald bild.", "Could not load selected image."),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val previewBitmap by produceState<android.graphics.Bitmap?>(null, widgetBgFile) {
        value = if (widgetBgFile != null) {
            withContext(Dispatchers.IO) { WidgetBackgroundStorage.decodeThumbnail(context, widgetBgFile) }
        } else {
            null
        }
    }

    fun handleDismiss() {
        if (widgetBgFile != null && widgetBgFile != initialWidgetBackgroundFile) {
            WidgetBackgroundStorage.deleteFile(context, widgetBgFile)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::handleDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text(tr("Namn", "Name")) },
                    placeholder = { Text(tr("t.ex. Vatten, Kaffe, Träning", "e.g. Water, Coffee, Workout")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (showSuggestions) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StarterSuggestions.forEach { suggestion ->
                            FilterChip(
                                selected = name.equals(suggestion, ignoreCase = true),
                                onClick = { name = suggestion },
                                label = { Text(suggestion) }
                            )
                        }
                    }
                }

                Text(tr("Räkneläge", "Counter mode"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CounterMode.entries.forEach { candidate ->
                        val selected = candidate == mode
                        Card(
                            onClick = { modeName = candidate.name },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(candidate.label(isSwedish), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    candidate.description(isSwedish),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Text(tr("Färg", "Color"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (idx in 0..CounterRepository.MAX_COLOR_INDEX) {
                        val swatch = counterAccentColor(idx)
                        val selected = idx == colorIndex
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .then(
                                    if (selected) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { colorIndex = idx }
                        )
                    }
                }

                Text(tr("Widgetbakgrund (valfri bild)", "Widget background (optional image)"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = tr("Vald widgetbakgrund", "Selected widget background"),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    OutlinedButton(
                        enabled = !importingImage,
                        onClick = { imagePicker.launch("image/*") },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (widgetBgFile != null) tr("Byt bild", "Change image")
                            else tr("Välj bild", "Pick image")
                        )
                    }
                    if (widgetBgFile != null) {
                        TextButton(
                            enabled = !importingImage,
                            onClick = {
                                if (widgetBgFile != initialWidgetBackgroundFile) {
                                    WidgetBackgroundStorage.deleteFile(context, widgetBgFile)
                                }
                                widgetBgFile = null
                            }
                        ) {
                            Text(tr("Ta bort", "Remove"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.trim().isNotEmpty() && !importingImage,
                onClick = { onConfirm(name, mode, colorIndex, widgetBgFile) }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = ::handleDismiss) {
                Text(tr("Avbryt", "Cancel"))
            }
        }
    )
}

private fun formatOneDecimalSv(value: Double): String {
    return String.format(SwedishLocale, "%.1f", value)
}

private fun formatSignedCount(value: Int): String {
    return when {
        value > 0 -> "+$value"
        else -> value.toString()
    }
}

private fun formatShortDateSv(date: LocalDate, today: LocalDate): String {
    return when (date) {
        today -> "Idag"
        today.minusDays(1) -> "Igår"
        else -> date.format(ShortMonthDayFormatter)
    }
}

private fun formatHistoryDateSv(date: LocalDate, today: LocalDate): String {
    return when (date) {
        today -> "Idag · ${date.format(ShortMonthDayFormatter)}"
        today.minusDays(1) -> "Igår · ${date.format(ShortMonthDayFormatter)}"
        else -> date.format(HistoryDateFormatter).replaceFirstChar { it.titlecase(SwedishLocale) }
    }
}
