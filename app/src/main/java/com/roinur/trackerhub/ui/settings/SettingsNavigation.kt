package com.roinur.trackerhub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.roinur.trackerhub.ui.LocalIsSwedish
import com.roinur.trackerhub.ui.tr
import kotlinx.coroutines.delay

enum class SettingsCategory(
    val titleSv: String,
    val titleEn: String,
    val summarySv: String,
    val summaryEn: String,
    val pathData: String
) {
    APPEARANCE(
        titleSv = "Utseende",
        titleEn = "Appearance",
        summarySv = "Språk, tema, accentfärg, extra mörkt och startsida",
        summaryEn = "Language, theme, accent color, extra dark, and start tab",
        pathData = "M12,3a9,9 0,1 0,0 18h1a2,2 0,0 0,1.4 -3.4c-.5,-.5 -.1,-1.6 .7,-1.6H17a4,4 0,0 0,4 -4c0,-5 -4,-9 -9,-9z M7,8h2v2H7z M11,6h2v2h-2z M15,8h2v2h-2z M6,12h2v2H6z"
    ),
    BACKUP(
        titleSv = "Data och säkerhetskopia",
        titleEn = "Data & Backup",
        summarySv = "Exportera och importera räknare (JSON & ZIP)",
        summaryEn = "Export and import counters (JSON & ZIP)",
        pathData = "M12,3a9,9 0,1 1,-8 5H1l4,-5 4,5H6a7,7 0,1 0,6 -3z M11,8h2v5l4,2 -1,2 -5,-3z"
    ),
    WIDGETS(
        titleSv = "Widgets",
        titleEn = "Widgets",
        summarySv = "Hemskärmswidgets, aktiva kopplingar och guide",
        summaryEn = "Home screen widgets, active bindings, and guide",
        pathData = "M3,3h8v8H3V3z M13,3h8v8h-8V3z M3,13h8v8H3v-8z M16,13h2v3h3v2h-3v3h-2v-3h-3v-2h3v-3z"
    ),
    ABOUT(
        titleSv = "Om appen",
        titleEn = "About",
        summarySv = "Version, lokal lagring och säkerhetskopieformat",
        summaryEn = "Version, local storage, and backup format",
        pathData = "M12,2a10,10 0,1 0,0 20a10,10 0,0 0,0 -20z M11,6h2v2h-2z M11,10h2v7h-2z"
    );

    fun title(isSwedish: Boolean): String = if (isSwedish) titleSv else titleEn
    fun summary(isSwedish: Boolean): String = if (isSwedish) summarySv else summaryEn

    companion object {
        fun fromNameOrNull(name: String?): SettingsCategory? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name == name }
        }
    }
}

private fun settingsVector(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

internal val SettingsBackIcon: ImageVector =
    settingsVector("SettingsBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")

internal val SettingsSearchIcon: ImageVector =
    settingsVector("SettingsSearch", "M10,3a7,7 0,1 0,0 14a7,7 0,0 0,0 -14z M10,5a5,5 0,1 1,0 10a5,5 0,0 1,0 -10z M15,14l7,7 -1.5,1.5 -7,-7z")

internal val SettingsChevronIcon: ImageVector =
    settingsVector("SettingsChevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")

internal val SettingsCategoryIcons: Map<SettingsCategory, ImageVector> =
    SettingsCategory.entries.associateWith { settingsVector(it.name, it.pathData) }

internal fun settingsSearchMatches(query: String, text: String): Boolean {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) return true
    val haystack = text.lowercase()
    return tokens.all { it in haystack }
}

@Composable
internal fun SettingsSearchBar(
    query: String,
    onQuery: (String) -> Unit
) {
    var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query))
    }
    var published by remember { mutableStateOf(query) }
    val publish by rememberUpdatedState(onQuery)

    LaunchedEffect(query) {
        if (query != published) {
            published = query
            editor = TextFieldValue(query, TextRange(query.length))
        }
    }
    LaunchedEffect(editor.text) {
        if (editor.text != published) {
            delay(150)
            published = editor.text
            publish(editor.text)
        }
    }

    fun clear() {
        editor = TextFieldValue("")
        published = ""
        onQuery("")
    }

    OutlinedTextField(
        value = editor,
        onValueChange = { editor = it },
        singleLine = true,
        placeholder = { Text(tr("Sök inställningar", "Search settings")) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        leadingIcon = {
            Icon(
                imageVector = SettingsSearchIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (editor.text.isNotBlank()) {
            {
                TextButton(onClick = { clear() }) {
                    Text(tr("Rensa", "Clear"))
                }
            }
        } else {
            null
        }
    )
}

@Composable
internal fun SettingsCategoryRow(
    category: SettingsCategory,
    detailHint: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = SettingsCategoryIcons.getValue(category),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            val isSwedish = LocalIsSwedish.current
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = category.title(isSwedish),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = detailHint ?: category.summary(isSwedish),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = SettingsChevronIcon,
                contentDescription = category.title(isSwedish),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SettingsOptionSection(
    category: SettingsCategory,
    title: String,
    terms: String,
    active: SettingsCategory?,
    query: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    val visible = if (query.isNotBlank()) {
        settingsSearchMatches(query, "${category.titleSv} ${category.titleEn} ${category.summarySv} ${category.summaryEn} $title $terms")
    } else {
        active == category
    }
    if (!visible) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = SettingsCategoryIcons.getValue(category),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (query.isNotBlank()) "${category.title(isSwedish)} · $title" else title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
internal fun SettingsActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val palette = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                destructive -> palette.error.copy(alpha = 0.55f)
                primary -> palette.primary.copy(alpha = 0.45f)
                else -> palette.outlineVariant
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                primary -> palette.primaryContainer
                else -> palette.surfaceContainer
            },
            contentColor = when {
                destructive -> palette.error
                primary -> palette.onPrimaryContainer
                else -> palette.onSurface
            }
        ),
        content = content
    )
}
