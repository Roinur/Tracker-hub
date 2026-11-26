package com.example.trackerhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.example.trackerhub.counter.CounterBackupCodec
import com.example.trackerhub.counter.CounterRepository
import com.example.trackerhub.settings.StartTab
import com.example.trackerhub.settings.TrackerHubBackupViewModel
import com.example.trackerhub.settings.TrackerHubSettingsRepository
import com.example.trackerhub.ui.CountersTabScreen
import com.example.trackerhub.ui.HubBottomBar
import com.example.trackerhub.ui.HubContentTab
import com.example.trackerhub.ui.HubTopBar
import com.example.trackerhub.ui.LocalIsSwedish
import com.example.trackerhub.ui.TrackerHubTheme
import com.example.trackerhub.ui.nextThemeMode
import com.example.trackerhub.ui.tr
import com.example.trackerhub.ui.settings.BackupErrorDialog
import com.example.trackerhub.ui.settings.ImportPreviewDialog
import com.example.trackerhub.ui.settings.SettingsCategory
import com.example.trackerhub.ui.settings.SettingsScreen
import com.example.trackerhub.widget.CounterWidgetProvider
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    private var requestedCounterId by mutableStateOf<String?>(null)
    private var navigationRequestTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (savedInstanceState == null) consumeOpenCounterIntent(intent)
        val repository = CounterRepository.getInstance(this)
        val settingsRepository = TrackerHubSettingsRepository.getInstance(this)
        val backupViewModel = ViewModelProvider(this)[TrackerHubBackupViewModel::class.java]

        setContent {
            TrackerHubApp(
                repository = repository,
                settingsRepository = settingsRepository,
                backupViewModel = backupViewModel,
                resumeTick = resumeTick,
                requestedCounterId = requestedCounterId,
                navigationRequestTick = navigationRequestTick
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenCounterIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val repository = CounterRepository.getInstance(this)
        if (repository.syncDateRollover()) {
            CounterWidgetProvider.updateAllWidgets(this)
        }
        resumeTick += 1
    }

    private fun consumeOpenCounterIntent(intent: Intent?) {
        val counterId = intent?.getStringExtra(EXTRA_OPEN_COUNTER_ID)
        if (!counterId.isNullOrBlank()) {
            requestedCounterId = counterId
            navigationRequestTick += 1
        }
    }

    companion object {
        const val ACTION_OPEN_COUNTER = "com.example.trackerhub.ACTION_OPEN_COUNTER"
        const val EXTRA_OPEN_COUNTER_ID = "com.example.trackerhub.EXTRA_OPEN_COUNTER_ID"
    }
}

private sealed interface HubScene {
    data object Main : HubScene
    data object Settings : HubScene
    data class CounterDetail(val counterId: String) : HubScene
}

private data class TrackerAppInfo(
    val name: String,
    val shortName: String,
    val assetName: String,
    val fileName: String,
    val packageCandidates: List<String>,
    val iconRes: Int,
    val accent: Color,
    val summary: String,
    val summaryEn: String = summary,
    val pitch: String,
    val features: List<String>,
    val apkSize: String,
    val githubUrl: String? = null
)

private val trackerApps = listOf(
    TrackerAppInfo(
        name = "Sauce Tracker",
        shortName = "Sauce",
        assetName = "apks/sauce_tracker.apk",
        fileName = "Sauce-Tracker-1.5.apk",
        packageCandidates = listOf("com.roinur.saucetracker", "com.roinur.saucetracker.rewrite", "com.example.saucetracker", "com.example.saucetracker.openclaw"),
        iconRes = R.drawable.hub_sauce_icon,
        accent = Color(0xFF176B5B),
        summary = "Bibliotek, taggar, läsare och sparade gallerier.",
        summaryEn = "Library, tags, reader, and saved galleries.",
        pitch = "Samla gallerier och manga, organisera med taggar och fortsätt läsa där du slutade.",
        features = listOf(
            "Taggar och samlingar",
            "Nedladdningar och läsare",
            "Prenumerationer och säkerhetskopior",
            "Inkognitoläge"
        ),
        apkSize = "8.8 MB",
        githubUrl = "https://github.com/Roinur/Sauce-tracker"
    ),
    TrackerAppInfo(
        name = "Stock Tracker",
        shortName = "Stock",
        assetName = "apks/stock_tracker.apk",
        fileName = "Stock-Tracker-1.5-dev.apk",
        packageCandidates = listOf("com.example.stocktracker", "com.example.saucestocktracker.dev", "com.example.saucestocktracker"),
        iconRes = R.drawable.hub_stock_icon,
        accent = Color(0xFF4267D9),
        summary = "Portfölj, bevakningar, fonder och transaktioner.",
        summaryEn = "Portfolio, watchlists, funds, and transactions.",
        pitch = "Följ innehav, bevakningar, fonder och likvida medel tillsammans med diagram och transaktionshistorik.",
        features = listOf(
            "Portfölj och bevakningar",
            "Köp, försäljningar och utdelningar",
            "Diagram och portföljöversikt",
            "CSV och lokal lagring"
        ),
        apkSize = "16.8 MB"
    ),
    TrackerAppInfo(
        name = "Book Tracker",
        shortName = "Book",
        assetName = "apks/book_tracker.apk",
        fileName = "Book-Tracker-1.5-openclaw.apk",
        packageCandidates = listOf("com.roinur.booktracker", "com.roinur.booktracker.debug", "com.example.booktracker.openclaw", "com.example.booktracker"),
        iconRes = R.drawable.hub_book_icon,
        accent = Color(0xFF9B5C2E),
        summary = "Bokbibliotek, läsning, skanning och statistik.",
        summaryEn = "Book library, reading progress, scanning, and stats.",
        pitch = "Bygg ditt bokbibliotek, skanna böcker och följ din läsning med framsteg, lässtatus och statistik.",
        features = listOf(
            "Bibliotek och bokdetaljer",
            "Streckkodsskanning",
            "Läsframsteg och status",
            "Statistik och säkerhetskopior"
        ),
        apkSize = "25.7 MB",
        githubUrl = "https://github.com/Roinur/Book-tracker"
    ),
    TrackerAppInfo(
        name = "Map Tracker", shortName = "Map",
        assetName = "apks/map_tracker.apk", fileName = "Map-Tracker-0.1.21.apk",
        packageCandidates = listOf("com.roinur.maptracker", "com.roinur.maptracker.qa"),
        iconRes = R.drawable.hub_map_icon, accent = Color(0xFF53764B),
        summary = "GPS-spår, besökta platser och vistelsetid.",
        summaryEn = "GPS tracks, visited places, and dwell time.",
        pitch = "Se dina rörelsemönster och platser på en karta. GPS-historiken sparas lokalt och kan säkerhetskopieras från appen.",
        features = listOf("GPS-spårning i bakgrunden", "Total och unik sträcka", "Namngivna platser och vistelsetid", "Lokal historik och säkerhetskopior"),
        apkSize = "2.3 MB"
    ),
    TrackerAppInfo(
        name = "Caffeine Tracker",
        shortName = "Caffeine",
        assetName = "apks/caffeine_tracker.apk",
        fileName = "Caffeine-Tracker-1.0-release.apk",
        packageCandidates = listOf("com.roinur.caffeinetracker"),
        iconRes = R.drawable.hub_caffeine_icon,
        accent = Color(0xFF7A4E3A),
        summary = "Koffeinintag, halveringstid, sömnfönster och widget.",
        summaryEn = "Caffeine intake, half-life curve, bedtime estimate, and widget.",
        pitch = "Logga kaffe, te och energidryck, se aktivt koffein i kroppen över dagen och följ statistik och favoriter.",
        features = listOf(
            "Aktivt koffein och halveringstid",
            "Snabbloggning och favoriter",
            "Hemskärmswidget",
            "Statistik och lokal säkerhetskopia"
        ),
        apkSize = "8.8 MB"
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerHubApp(
    repository: CounterRepository,
    settingsRepository: TrackerHubSettingsRepository,
    backupViewModel: TrackerHubBackupViewModel,
    resumeTick: Int,
    requestedCounterId: String?,
    navigationRequestTick: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.state.collectAsState()
    val counterState by repository.state.collectAsState()
    val backupState by backupViewModel.uiState.collectAsState()

    val initialTab = remember {
        when {
            requestedCounterId != null -> HubContentTab.COUNTERS
            settingsRepository.current().startTab == StartTab.COUNTERS -> HubContentTab.COUNTERS
            else -> HubContentTab.APPS
        }
    }
    var selectedTabName by rememberSaveable { mutableStateOf(initialTab.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedCounterId by rememberSaveable { mutableStateOf(requestedCounterId) }
    var settingsCategoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }

    val selectedTab = HubContentTab.entries.firstOrNull { it.name == selectedTabName } ?: HubContentTab.APPS
    val activeSettingsCategory = SettingsCategory.fromNameOrNull(settingsCategoryName)
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { HubContentTab.entries.size }
    )
    var navigationDragStart by remember { mutableIntStateOf(selectedTab.ordinal) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) backupViewModel.exportToUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) backupViewModel.inspectImportUri(uri)
    }

    val launchExport: () -> Unit = {
        if (!backupState.busy) {
            exportLauncher.launch(CounterBackupCodec.suggestedJsonFileName())
        }
    }

    val launchImport: () -> Unit = {
        if (!backupState.busy) {
            importLauncher.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*"))
        }
    }

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab.ordinal) {
            pagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }

    LaunchedEffect(navigationRequestTick) {
        if (navigationRequestTick > 0 && !requestedCounterId.isNullOrBlank()) {
            showSettings = false
            selectedTabName = HubContentTab.COUNTERS.name
            selectedCounterId = requestedCounterId
        }
    }

    LaunchedEffect(backupState.toastMessage?.id) {
        val toast = backupState.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, toast.text, Toast.LENGTH_LONG).show()
        backupViewModel.consumeToast(toast.id)
    }

    val currentScene: HubScene = when {
        showSettings -> HubScene.Settings
        selectedCounterId != null -> HubScene.CounterDetail(selectedCounterId!!)
        else -> HubScene.Main
    }

    TrackerHubTheme(settings = settings) {
        val isSwedish = LocalIsSwedish.current
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            AnimatedContent(
                targetState = currentScene,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)))
                        .using(null)
                },
                label = "HubSceneTransition"
            ) { scene ->
                when (scene) {
                    HubScene.Settings -> SettingsScreen(
                        settings = settings,
                        counterRepository = repository,
                        backupState = backupState,
                        activeCategory = activeSettingsCategory,
                        query = settingsQuery,
                        onSelectCategory = { category ->
                            settingsCategoryName = category?.name
                            if (category != null) settingsQuery = ""
                        },
                        onQueryChange = { settingsQuery = it },
                        onBackFromRoot = {
                            settingsCategoryName = null
                            settingsQuery = ""
                            showSettings = false
                        },
                        onThemeModeChange = settingsRepository::setThemeMode,
                        onAccentModeChange = settingsRepository::setAccentMode,
                        onExtraDarkChange = settingsRepository::setExtraDark,
                        onStartTabChange = settingsRepository::setStartTab,
                        onLanguageChange = { lang ->
                            settingsRepository.setLanguage(lang)
                            CounterWidgetProvider.updateAllWidgets(context)
                        },
                        onExportBackup = launchExport,
                        onImportBackup = launchImport,
                        onOpenCountersTab = {
                            showSettings = false
                            selectedTabName = HubContentTab.COUNTERS.name
                        }
                    )

                    is HubScene.CounterDetail -> CountersTabScreen(
                        repository = repository,
                        resumeTick = resumeTick,
                        selectedCounterId = scene.counterId,
                        onSelectCounter = { selectedCounterId = it },
                        onImportBackup = launchImport,
                        backupBusy = backupState.busy
                    )

                    HubScene.Main -> Column(modifier = Modifier.fillMaxSize()) {
                        key(settings.themeMode, settings.accentMode, MaterialTheme.colorScheme.background) {
                            HubTopBar(
                                title = selectedTab.label(isSwedish),
                                themeMode = settings.themeMode,
                                accentMode = settings.accentMode,
                                settingsActive = false,
                                onCycleThemeMode = {
                                    settingsRepository.setThemeMode(nextThemeMode(settings.themeMode))
                                },
                                onAccentModeSelected = settingsRepository::setAccentMode,
                                onSettings = {
                                    settingsCategoryName = null
                                    settingsQuery = ""
                                    showSettings = true
                                }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                userScrollEnabled = false,
                                beyondBoundsPageCount = 1,
                                pageSpacing = 14.dp,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                when (HubContentTab.entries[page]) {
                                    HubContentTab.APPS -> TrackerHubScreen(resumeTick = resumeTick)
                                    HubContentTab.COUNTERS -> CountersTabScreen(
                                        repository = repository,
                                        resumeTick = resumeTick,
                                        selectedCounterId = null,
                                        onSelectCounter = { selectedCounterId = it },
                                        onImportBackup = launchImport,
                                        backupBusy = backupState.busy
                                    )
                                }
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                HubBottomBar(
                                    selected = selectedTab,
                                    pagePosition = {
                                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                                    },
                                    onDragStart = { navigationDragStart = pagerState.currentPage },
                                    onDrag = { delta -> pagerState.dispatchRawDelta(-delta) },
                                    onDragEnd = { velocity ->
                                        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                                        val target = when {
                                            velocity < -350f -> 1
                                            velocity > 350f -> 0
                                            position - navigationDragStart >= 0.12f -> 1
                                            position - navigationDragStart <= -0.12f -> 0
                                            else -> navigationDragStart
                                        }
                                        selectedTabName = HubContentTab.entries[target].name
                                        scope.launch { pagerState.animateScrollToPage(target) }
                                    },
                                    onSelected = { tab -> selectedTabName = tab.name }
                                )
                            }
                        }
                    }
                }
            }
        }

        backupState.pendingImport?.let { inspected ->
            ImportPreviewDialog(
                inspection = inspected,
                existingCountersCount = counterState.counters.size,
                selectedStrategy = backupState.selectedImportStrategy,
                busy = backupState.busy,
                onSelectStrategy = backupViewModel::selectImportStrategy,
                onDismiss = backupViewModel::dismissPendingImport,
                onConfirmImport = backupViewModel::confirmPendingImport
            )
        }

        backupState.errorMessage?.let { errorText ->
            BackupErrorDialog(
                errorMessage = errorText,
                onDismiss = backupViewModel::dismissError
            )
        }
    }
}

@Composable
private fun TrackerHubScreen(resumeTick: Int) {
    val context = LocalContext.current
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val installStates = remember(resumeTick) {
        trackerApps.associateWith { app -> context.findLaunchIntent(app) != null }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
        }

        trackerApps.forEachIndexed { index, app ->
            item(key = app.name) {
                Column {
                    TrackerCompactCard(
                        app = app,
                        installed = installStates[app] == true,
                        selected = selectedIndex == index,
                        onSelect = { selectedIndex = if (selectedIndex == index) -1 else index },
                        onPrimary = { openOrInstall(context, app) }
                    )
                    AnimatedVisibility(
                        visible = selectedIndex == index,
                        enter = expandVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ) + fadeIn(animationSpec = tween(160, easing = FastOutSlowInEasing)),
                        exit = shrinkVertically(
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top
                        ) + fadeOut(animationSpec = tween(120, easing = FastOutSlowInEasing))
                    ) {
                        AppDownloadOptions(app)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(76.dp))
        }
    }
}

@Composable
private fun TrackerCompactCard(
    app: TrackerAppInfo,
    installed: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onPrimary: () -> Unit
) {
    val selectedProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "AppCardSelectedProgress"
    )
    val container = lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.surfaceVariant,
        selectedProgress
    )
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = selectedProgress)
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppMark(app = app, size = 44)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (LocalIsSwedish.current) app.summary else app.summaryEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onPrimary) {
                Text(
                    when {
                        installed -> tr("Öppna", "Open")
                        app.githubUrl != null -> tr("Hämta", "Get")
                        else -> tr("Installera", "Install")
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDownloadOptions(app: TrackerAppInfo) {
    val context = LocalContext.current
    val hasLocalApk = remember(app.assetName) { context.hasBundledApk(app) }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        app.githubUrl?.let { url ->
            TextButton(onClick = { openLink(context, url) }) { Text("GitHub") }
            TextButton(onClick = { openLink(context, url + "/releases/latest") }) { Text(tr("Senaste version", "Latest release")) }
        }
        if (hasLocalApk) {
            TextButton(onClick = { installApk(context, app) }) { Text(tr("Lokal APK", "Local APK")) }
        } else if (app.githubUrl == null) {
            Text(
                text = tr("Ingen publik nedladdning ännu", "No public release yet"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AppMark(app: TrackerAppInfo, size: Int) {
    Image(
        painter = painterResource(app.iconRes),
        contentDescription = "${app.name} icon",
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop
    )
}

private fun openOrInstall(context: Context, app: TrackerAppInfo) {
    val launchIntent = context.findLaunchIntent(app)
    if (launchIntent != null) {
        context.startActivity(launchIntent)
        return
    }
    app.githubUrl?.let { openLink(context, "$it/releases/latest"); return }
    if (context.hasBundledApk(app)) {
        installApk(context, app)
    } else {
        Toast.makeText(context, "${app.name} is not installed on this device.", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.hasBundledApk(app: TrackerAppInfo): Boolean {
    return runCatching {
        assets.open(app.assetName).use { true }
    }.getOrDefault(false)
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "Ingen webbläsare tillgänglig.", Toast.LENGTH_LONG).show()
    }
}

private val apkExtractionWorker = Executors.newSingleThreadExecutor()

private fun installApk(context: Context, app: TrackerAppInfo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "Tillåt installation från Tracker Hub och tryck igen.", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
    }

    val appContext = context.applicationContext
    apkExtractionWorker.execute {
        val result = runCatching {
            val apkFile = appContext.extractApk(app)
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val installIntent = result.getOrNull()
            if (installIntent != null) {
                try {
                    context.startActivity(installIntent)
                    return@post
                } catch (_: android.content.ActivityNotFoundException) {
                    // The device may not have a package installer.
                }
            }
            Toast.makeText(appContext, "Kunde inte öppna APK-filen.", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Context.findLaunchIntent(app: TrackerAppInfo): Intent? {
    return app.packageCandidates.firstNotNullOfOrNull { packageName ->
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

private fun Context.extractApk(app: TrackerAppInfo): File {
    val targetDir = File(cacheDir, "tracker-apks").also { it.mkdirs() }
    val target = File(targetDir, app.fileName)
    assets.open(app.assetName).use { input ->
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return target
}
