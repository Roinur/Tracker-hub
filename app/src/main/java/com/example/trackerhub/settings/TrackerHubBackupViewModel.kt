package com.example.trackerhub.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.example.trackerhub.counter.CounterBackupCodec
import com.example.trackerhub.counter.CounterRepository
import com.example.trackerhub.widget.CounterWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupToastMessage(
    val id: Long,
    val text: String
)

data class BackupFlowUiState(
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val pendingImport: CounterBackupCodec.BackupInspectionResult? = null,
    val selectedImportStrategy: CounterRepository.ImportStrategy = CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE,
    val errorMessage: String? = null,
    val toastMessage: BackupToastMessage? = null
)

/**
 * Activity-scoped ViewModel so in-flight ZIP export/import operations, pending import previews,
 * selected merge/replace strategy, and error dialogs survive screen rotation cleanly.
 */
class TrackerHubBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = CounterRepository.getInstance(appContext)
    private val settingsRepository = TrackerHubSettingsRepository.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nextToastId = 1L

    private val _uiState = MutableStateFlow(BackupFlowUiState())
    val uiState: StateFlow<BackupFlowUiState> = _uiState.asStateFlow()

    fun exportToUri(uri: Uri) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, busyLabel = "Exporterar säkerhetskopia…", errorMessage = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshotCounters = repository.currentSnapshot().counters
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Kunde inte öppna filen för skrivning.")
                    stream.use { out ->
                        CounterBackupCodec.exportJson(counters = snapshotCounters, output = out)
                    }
                }
            }
            result.onSuccess { summary ->
                settingsRepository.recordExportSuccess()
                val toast = BackupToastMessage(
                    id = nextToastId++,
                    text = "Säkerhetskopia sparad (${summary.countersCount} räknare, ${summary.totalActiveDays} dagar)."
                )
                _uiState.update { it.copy(busy = false, busyLabel = null, toastMessage = toast) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        busyLabel = null,
                        errorMessage = err.message ?: "Kunde inte spara säkerhetskopian."
                    )
                }
            }
        }
    }

    fun inspectImportUri(uri: Uri) {
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(
                busy = true,
                busyLabel = "Validerar säkerhetskopia…",
                errorMessage = null
            )
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Kunde inte öppna vald fil.")
                    stream.use { input ->
                        CounterBackupCodec.inspectBackup(input = input)
                    }
                }
            }
            result.onSuccess { inspected ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        busyLabel = null,
                        pendingImport = inspected,
                        selectedImportStrategy = CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        busyLabel = null,
                        errorMessage = err.message ?: "Kunde inte läsa eller validera säkerhetskopian."
                    )
                }
            }
        }
    }

    fun selectImportStrategy(strategy: CounterRepository.ImportStrategy) {
        _uiState.update { it.copy(selectedImportStrategy = strategy) }
    }

    fun dismissPendingImport() {
        if (_uiState.value.busy) return
        _uiState.update {
            it.copy(
                pendingImport = null,
                selectedImportStrategy = CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE
            )
        }
    }

    fun confirmPendingImport() {
        val current = _uiState.value
        val inspected = current.pendingImport ?: return
        if (current.busy) return
        val strategy = current.selectedImportStrategy
        _uiState.update {
            it.copy(
                busy = true,
                busyLabel = "Importerar räknare…",
                pendingImport = null,
                errorMessage = null
            )
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repository.importCounters(inspected.counters, strategy) }
            }
            result.onSuccess { applied ->
                CounterWidgetProvider.updateAllWidgets(appContext)
                settingsRepository.recordImportSuccess()
                val message = when (applied.strategy) {
                    CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE ->
                        "Import klar. ${applied.addedCount} nya, ${applied.mergedCount} sammanslagna."
                    CounterRepository.ImportStrategy.REPLACE_ALL ->
                        "Återställde ${applied.replacedCount} räknare från säkerhetskopian."
                }
                _uiState.update {
                    it.copy(
                        busy = false,
                        busyLabel = null,
                        selectedImportStrategy = CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE,
                        toastMessage = BackupToastMessage(id = nextToastId++, text = message)
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        busy = false,
                        busyLabel = null,
                        errorMessage = err.message ?: "Kunde inte spara de importerade räknarna."
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun consumeToast(id: Long) {
        _uiState.update { state ->
            if (state.toastMessage?.id == id) state.copy(toastMessage = null) else state
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}
