package com.roinur.trackerhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roinur.trackerhub.counter.CounterBackupCodec
import com.roinur.trackerhub.counter.CounterRepository
import com.roinur.trackerhub.ui.LocalIsSwedish
import com.roinur.trackerhub.ui.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SwedishLocale = Locale("sv", "SE")
private val BackupTimestampFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", SwedishLocale)

internal fun formatBackupTimestampSv(isoString: String?): String? {
    if (isoString.isNullOrBlank()) return null
    return try {
        Instant.parse(isoString)
            .atZone(ZoneId.systemDefault())
            .format(BackupTimestampFormatter)
    } catch (_: Exception) {
        null
    }
}

internal fun formatEpochTimestampSv(epochMillis: Long?): String? {
    if (epochMillis == null || epochMillis <= 0L) return null
    return try {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(BackupTimestampFormatter)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ImportPreviewDialog(
    inspection: CounterBackupCodec.BackupInspectionResult,
    existingCountersCount: Int,
    selectedStrategy: CounterRepository.ImportStrategy,
    busy: Boolean,
    onSelectStrategy: (CounterRepository.ImportStrategy) -> Unit,
    onDismiss: () -> Unit,
    onConfirmImport: () -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    val formattedDate = remember(inspection.createdAtIso) {
        formatBackupTimestampSv(inspection.createdAtIso)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Granska säkerhetskopia", "Review backup"), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val badgeText = when (inspection.sourceFormat) {
                            CounterBackupCodec.BackupSourceFormat.TRACKERHUB_JSON ->
                                tr(isSwedish, "Validerad · Tracker Hub JSON v${inspection.formatVersion}", "Verified · Tracker Hub JSON v${inspection.formatVersion}")
                            CounterBackupCodec.BackupSourceFormat.LEGACY_ZIP ->
                                tr(isSwedish, "Validerad · ZIP-arkiv v${inspection.formatVersion} (${inspection.verifiedFilesCount} filer kontrollerade)", "Verified · ZIP archive v${inspection.formatVersion} (${inspection.verifiedFilesCount} files checked)")
                        }
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (formattedDate != null) {
                            Text(
                                text = tr(isSwedish, "Skapad: $formattedDate", "Created: $formattedDate"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = tr(
                                isSwedish,
                                "${inspection.counters.size} räknare · ${inspection.totalActiveDays} dagar · totalt ${inspection.totalCountSum}",
                                "${inspection.counters.size} counters · ${inspection.totalActiveDays} days · total ${inspection.totalCountSum}"
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (inspection.counters.isEmpty()) {
                    Text(
                        text = "Säkerhetskopian innehåller inga räknare.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Räknare i filen",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        inspection.counters.take(8).forEach { counter ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = counter.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${counter.totalCount()} (${counter.dailyCounts.size} d)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (inspection.counters.size > 8) {
                            Text(
                                text = "+ ${inspection.counters.size - 8} till",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (inspection.warnings.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Kompatibilitetsnoteringar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            inspection.warnings.forEach { warning ->
                                Text(
                                    text = "• $warning",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Text(
                    text = tr("Importläge", "Import mode"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                ImportStrategyOptionCard(
                    title = tr("Slå ihop (rekommenderat)", "Merge (recommended)"),
                    description = tr(
                        "Behåller alla befintliga räknare och lägger till eller kompletterar historik utan dataförlust.",
                        "Keeps existing counters and adds or merges history without data loss."
                    ),
                    selected = selectedStrategy == CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE,
                    warning = null,
                    onClick = { onSelectStrategy(CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE) }
                )

                ImportStrategyOptionCard(
                    title = tr("Ersätt alla räknare", "Replace all counters"),
                    description = tr(
                        "Ersätter nuvarande räknare med innehållet i säkerhetskopian.",
                        "Replaces current counters with the contents of this backup."
                    ),
                    selected = selectedStrategy == CounterRepository.ImportStrategy.REPLACE_ALL,
                    warning = if (existingCountersCount > 0) {
                        tr(
                            isSwedish,
                            "Obs: $existingCountersCount befintliga räknare ersätts (widgets för kvarvarande ID behålls).",
                            "Note: $existingCountersCount existing counters will be replaced (widgets with matching IDs are kept)."
                        )
                    } else null,
                    onClick = { onSelectStrategy(CounterRepository.ImportStrategy.REPLACE_ALL) }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && inspection.counters.isNotEmpty(),
                colors = if (selectedStrategy == CounterRepository.ImportStrategy.REPLACE_ALL && existingCountersCount > 0) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = onConfirmImport
            ) {
                Text(
                    if (selectedStrategy == CounterRepository.ImportStrategy.REPLACE_ALL) tr("Ersätt", "Replace")
                    else tr("Importera", "Import")
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(tr("Avbryt", "Cancel"))
            }
        }
    )
}

@Composable
fun BackupErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Säkerhetskopia misslyckades", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(errorMessage)
                Text(
                    text = "Inga befintliga räknare har ändrats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun ImportStrategyOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    warning: String?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
