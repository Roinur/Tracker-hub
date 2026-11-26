package com.example.trackerhub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackerhub.R
import com.example.trackerhub.settings.AccentMode
import com.example.trackerhub.settings.ThemeMode

internal enum class HubContentTab(val labelSv: String, val labelEn: String, val iconRes: Int) {
    APPS("Appar", "Apps", R.drawable.ic_nav_hub),
    COUNTERS("Räknare", "Counters", R.drawable.ic_nav_counter);

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn
}

internal fun nextThemeMode(themeMode: ThemeMode): ThemeMode =
    when (themeMode) {
        ThemeMode.SYSTEM -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.LIGHT
        ThemeMode.LIGHT -> ThemeMode.SYSTEM
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubTopBar(
    title: String,
    themeMode: ThemeMode,
    accentMode: AccentMode,
    settingsActive: Boolean,
    onBack: (() -> Unit)? = null,
    onCycleThemeMode: () -> Unit,
    onAccentModeSelected: (AccentMode) -> Unit,
    onSettings: () -> Unit
) {
    CenterAlignedTopAppBar(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        "\u2039",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                ThemeToggleWithAccentPicker(
                    themeMode = themeMode,
                    accentMode = accentMode,
                    onCycleThemeMode = onCycleThemeMode,
                    onAccentModeSelected = onAccentModeSelected,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        },
        actions = {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = if (settingsActive) "\u00D7" else "\u2699",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
internal fun HubBottomBar(
    selected: HubContentTab,
    pagePosition: () -> Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onSelected: (HubContentTab) -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth(0.70f)
                .height(46.dp)
                .pointerInput(Unit) {
                    val velocity = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            velocity.resetTracking()
                            onDragStart()
                        },
                        onDragCancel = { onDragEnd(0f) },
                        onDragEnd = { onDragEnd(velocity.calculateVelocity().x / density) },
                        onHorizontalDrag = { change, amount ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                            onDrag(amount)
                        }
                    )
                }
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                .padding(3.dp)
        ) {
            val segmentWidth = maxWidth / 2
            Box(
                Modifier
                    .graphicsLayer { translationX = segmentWidth.toPx() * pagePosition() }
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(21.dp))
            )
            Row(Modifier.fillMaxSize()) {
                HubContentTab.entries.forEach { tab ->
                    val active = selected == tab
                    val label = tab.label(isSwedish)
                    val contentTint = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        onClick = { onSelected(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(21.dp),
                        color = Color.Transparent
                    ) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = label,
                                tint = contentTint,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = contentTint
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThemeToggleWithAccentPicker(
    themeMode: ThemeMode,
    accentMode: AccentMode,
    onCycleThemeMode: () -> Unit,
    onAccentModeSelected: (AccentMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = ACCENT_PICKER_OPTIONS
    var pickerVisible by remember { mutableStateOf(false) }
    var highlightedIndex by remember { mutableStateOf<Int?>(null) }

    val iconSize = 40.dp
    val chipSize = 22.dp
    val chipSpacing = 8.dp
    val pillPadding = 10.dp
    val pillGap = 8.dp
    val pillHeight = 34.dp
    val pillWidth =
        (pillPadding * 2) + (chipSize * options.size) + (chipSpacing * (options.size - 1))
    val expandedWidth = iconSize + pillGap + pillWidth

    val density = LocalDensity.current
    fun indexForX(x: Float): Int? {
        val iconPx = with(density) { iconSize.toPx() }
        val gapPx = with(density) { pillGap.toPx() }
        val paddingPx = with(density) { pillPadding.toPx() }
        val chipPx = with(density) { chipSize.toPx() }
        val slotPx = with(density) { (chipSize + chipSpacing).toPx() }

        val start = iconPx + gapPx + paddingPx
        val end = start + ((options.size - 1) * slotPx) + chipPx
        if (x < start || x > end) return null
        val idx = ((x - start) / slotPx).toInt()
        return idx.coerceIn(0, options.lastIndex)
    }

    Box(
        modifier = modifier
            .width(if (pickerVisible) expandedWidth else iconSize)
            .height(iconSize)
            .pointerInput(accentMode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        onCycleThemeMode()
                        return@awaitEachGesture
                    }

                    pickerVisible = true
                    highlightedIndex = indexForX(longPress.position.x)
                        ?: options.indexOfFirst { it.mode == accentMode }.takeIf { it >= 0 }

                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change == null) {
                            released = true
                        } else if (!change.pressed) {
                            released = true
                        } else {
                            highlightedIndex = indexForX(change.position.x) ?: highlightedIndex
                        }
                    }

                    val picked = highlightedIndex
                    if (picked != null && picked in options.indices) {
                        onAccentModeSelected(options[picked].mode)
                    }
                    pickerVisible = false
                    highlightedIndex = null
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (pickerVisible) {
            Box(
                modifier = Modifier
                    .offset(x = iconSize + pillGap)
                    .height(pillHeight)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = pillPadding),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(chipSpacing)
                ) {
                    options.forEachIndexed { index, option ->
                        val selected = accentMode == option.mode
                        val hovered = highlightedIndex == index
                        val ringColor = when {
                            hovered -> MaterialTheme.colorScheme.primary
                            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                        if (option.color == null) {
                            val autoCircleColor = if (hovered || selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            }
                            Canvas(
                                modifier = Modifier
                                    .size(chipSize)
                                    .padding(1.dp)
                            ) {
                                val stroke = if (hovered || selected) 3f else 2f
                                val radius = (size.minDimension / 2f) - stroke
                                drawCircle(
                                    color = autoCircleColor,
                                    radius = radius,
                                    style = Stroke(width = stroke)
                                )
                                drawLine(
                                    color = autoCircleColor,
                                    start = Offset(size.width * 0.72f, size.height * 0.18f),
                                    end = Offset(size.width * 0.28f, size.height * 0.82f),
                                    strokeWidth = stroke
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(chipSize)
                                    .clip(CircleShape)
                                    .background(option.color)
                                    .border(
                                        width = if (hovered || selected) 2.2.dp else 1.2.dp,
                                        color = ringColor,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(iconSize)
                .clip(CircleShape)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = themeModeSymbol(themeMode),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

internal fun themeModeSymbol(mode: ThemeMode): String {
    return when (mode) {
        ThemeMode.LIGHT -> "☀"
        ThemeMode.DARK -> "☾"
        ThemeMode.SYSTEM -> "◐"
    }
}

internal data class AccentPickerOption(
    val mode: AccentMode,
    val color: Color?
)

internal val ACCENT_PICKER_OPTIONS = listOf(
    AccentPickerOption(AccentMode.AUTO, null),
    AccentPickerOption(AccentMode.RED, Color(0xFFE53935)),
    AccentPickerOption(AccentMode.ORANGE, Color(0xFFFB8C00)),
    AccentPickerOption(AccentMode.AMBER, Color(0xFFF9A825)),
    AccentPickerOption(AccentMode.GREEN, Color(0xFF43A047)),
    AccentPickerOption(AccentMode.TEAL, Color(0xFF00897B)),
    AccentPickerOption(AccentMode.BLUE, Color(0xFF1E88E5)),
    AccentPickerOption(AccentMode.INDIGO, Color(0xFF5E35B1)),
    AccentPickerOption(AccentMode.PINK, Color(0xFFD81B60))
)
