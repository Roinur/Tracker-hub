package com.roinur.trackerhub.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.roinur.trackerhub.counter.CounterItem
import com.roinur.trackerhub.counter.CounterMode
import com.roinur.trackerhub.counter.CounterRepository
import com.roinur.trackerhub.ui.LocalIsSwedish
import com.roinur.trackerhub.ui.TrackerHubTheme
import com.roinur.trackerhub.ui.counterAccentColor
import com.roinur.trackerhub.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WidgetColorNames = listOf("Turkos", "Blå", "Orange", "Grön", "Lila", "Röd")

class CounterWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val repository = CounterRepository.getInstance(this)
        repository.syncDateRollover()

        setContent {
            TrackerHubTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetConfigureScreen(
                        repository = repository,
                        appWidgetId = appWidgetId,
                        onCancel = { finish() },
                        onConfirmCounter = { counterId ->
                            repository.bindWidget(appWidgetId, counterId)
                            val manager = AppWidgetManager.getInstance(this)
                            CounterWidgetProvider.updateWidget(this, manager, appWidgetId)
                            MidnightRolloverScheduler.scheduleIfNeeded(this)

                            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            setResult(RESULT_OK, resultValue)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private val QuickCounterSuggestions = listOf("Vatten", "Kaffe", "Träning", "Steg", "Sidor", "Pauser")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetConfigureScreen(
    repository: CounterRepository,
    appWidgetId: Int,
    onCancel: () -> Unit,
    onConfirmCounter: (String) -> Unit
) {
    val storeState by repository.state.collectAsState()
    val today = repository.currentDate()
    val existingBoundId = storeState.widgetBindings[appWidgetId]
    val existingCounters = storeState.counters

    var createNewMode by remember(existingCounters.isEmpty()) {
        mutableStateOf(existingCounters.isEmpty())
    }
    var selectedCounterId by remember(existingBoundId, existingCounters) {
        mutableStateOf(
            existingBoundId?.takeIf { storeState.counterById(it) != null }
                ?: existingCounters.firstOrNull()?.id
        )
    }
    var newName by rememberSaveable { mutableStateOf("") }
    var newMode by rememberSaveable { mutableStateOf(CounterMode.DAILY_RESET) }
    var colorIndex by rememberSaveable { mutableStateOf(existingCounters.size % WidgetColorNames.size) }
    var widgetBgFile by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSwedish = LocalIsSwedish.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { WidgetBackgroundStorage.importFromUri(context, uri) }
                if (saved != null) {
                    WidgetBackgroundStorage.deleteFile(context, widgetBgFile)
                    widgetBgFile = saved
                } else {
                    Toast.makeText(context, tr(isSwedish, "Kunde inte läsa vald bild.", "Could not load selected image."), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val previewBitmap by produceState<android.graphics.Bitmap?>(null, widgetBgFile) {
        value = if (widgetBgFile != null) {
            withContext(Dispatchers.IO) { WidgetBackgroundStorage.decodeThumbnail(context, widgetBgFile) }
        } else null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "HEMSKÄRMSWIDGET",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Konfigurera räknare",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Välj en befintlig räknare eller skapa en ny direkt till hemskärmen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (existingCounters.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = !createNewMode,
                        onClick = { createNewMode = false },
                        label = { Text("Befintlig (${existingCounters.size})") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = createNewMode,
                        onClick = { createNewMode = true },
                        label = { Text("Skapa ny") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (createNewMode || existingCounters.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Ny räknare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { if (it.length <= 40) newName = it },
                            label = { Text("Namn på räknare") },
                            placeholder = { Text("t.ex. Vatten, Kaffe, Pushups") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            QuickCounterSuggestions.forEach { suggestion ->
                                FilterChip(
                                    selected = newName.equals(suggestion, ignoreCase = true),
                                    onClick = { newName = suggestion },
                                    label = { Text(suggestion) }
                                )
                            }
                        }

                        Text("Räkneläge", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        ModeSelectionCards(
                            selectedMode = newMode,
                            onSelectMode = { newMode = it }
                        )
                        Text(tr("Widgetfärg", "Widget color"), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            WidgetColorNames.forEachIndexed { index, label ->
                                FilterChip(
                                    selected = colorIndex == index,
                                    onClick = { colorIndex = index },
                                    leadingIcon = {
                                        Box(Modifier.size(12.dp).clip(CircleShape).background(counterAccentColor(index)))
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Text(tr("Widgetbakgrund (valfri bild)", "Widget background (optional image)"), style = MaterialTheme.typography.titleSmall)
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
                                onClick = { imagePicker.launch("image/*") },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (widgetBgFile != null) tr("Byt bild", "Change image") else tr("Välj bild", "Pick image"))
                            }
                            if (widgetBgFile != null) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        WidgetBackgroundStorage.deleteFile(context, widgetBgFile)
                                        widgetBgFile = null
                                    }
                                ) {
                                    Text(tr("Ta bort", "Remove"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            items(existingCounters, key = { it.id }) { counter ->
                val isSelected = selectedCounterId == counter.id
                ExistingCounterPickCard(
                    counter = counter,
                    primaryValue = counter.primaryDisplayValue(today),
                    totalValue = counter.totalCount(),
                    selected = isSelected,
                    onClick = { selectedCounterId = counter.id }
                )
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = if (createNewMode || existingCounters.isEmpty()) {
                        newName.trim().isNotEmpty()
                    } else {
                        selectedCounterId != null
                    },
                    onClick = {
                        if (createNewMode || existingCounters.isEmpty()) {
                            val created = repository.createCounter(
                                name = newName,
                                mode = newMode,
                                colorIndex = colorIndex,
                                widgetBackgroundFile = widgetBgFile
                            )
                            onConfirmCounter(created.id)
                        } else {
                            selectedCounterId?.let(onConfirmCounter)
                        }
                    }
                ) {
                    Text(if (createNewMode || existingCounters.isEmpty()) "Skapa och placera widget" else "Använd vald räknare")
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancel
                ) {
                    Text("Avbryt")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ModeSelectionCards(
    selectedMode: CounterMode,
    onSelectMode: (CounterMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CounterMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Card(
                onClick = { onSelectMode(mode) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mode.labelSv, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        mode.descriptionSv,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ExistingCounterPickCard(
    counter: CounterItem,
    primaryValue: Int,
    totalValue: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = counterAccentColor(counter.colorIndex)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(counter.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${counter.mode.labelSv} · Totalt $totalValue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = primaryValue.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
