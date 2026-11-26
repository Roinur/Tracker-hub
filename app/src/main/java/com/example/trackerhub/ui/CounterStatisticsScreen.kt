package com.example.trackerhub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackerhub.counter.CounterFullStatistics
import com.example.trackerhub.counter.CounterFullStatisticsCalculator
import com.example.trackerhub.counter.CounterItem
import com.example.trackerhub.counter.StatsGranularity
import com.example.trackerhub.counter.StatsPeriodBucket
import com.example.trackerhub.counter.WeekdayStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val StatsSwedishLocale = Locale("sv", "SE")
private val StatsFullDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", StatsSwedishLocale)

@Composable
fun CounterStatisticsScreen(
    counter: CounterItem,
    today: LocalDate,
    onBack: () -> Unit
) {
    val isSwedish = LocalIsSwedish.current
    val accent = counterAccentColor(counter.colorIndex)
    val fullStats by produceState<CounterFullStatistics?>(null, counter, today, isSwedish) {
        value = withContext(Dispatchers.Default) {
            CounterFullStatisticsCalculator.calculate(counter, today, isSwedish = isSwedish)
        }
    }
    var granularityName by rememberSaveable(counter.id) { mutableStateOf(StatsGranularity.DAY.name) }
    val granularity = StatsGranularity.entries.firstOrNull { it.name == granularityName } ?: StatsGranularity.DAY

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("← ${counter.name}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                    Text(
                        text = tr("Statistik", "Statistics"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(StatsGranularity.entries, key = { it.name }) { option ->
                    FilterChip(
                        selected = granularity == option,
                        onClick = { granularityName = option.name },
                        label = { Text(option.label(isSwedish)) }
                    )
                }
            }
        }

        val stats = fullStats
        if (stats != null) {
            item {
                AnimatedContent(
                    targetState = granularity,
                    transitionSpec = {
                        fadeIn(tween(110)) togetherWith fadeOut(tween(80))
                    },
                    label = "StatsGranularityTransition"
                ) { selectedGranularity ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (selectedGranularity) {
                            StatsGranularity.DAY -> {
                                ScrollableBucketsChartCard(
                                    title = "Per dag",
                                    bucketsDescending = stats.dailyBucketsDescending,
                                    accent = accent,
                                    barWidth = 42.dp
                                )
                                PeriodSummaryGrid(
                                    firstLabel = "Snitt / dag",
                                    firstValue = formatOneDecSv(stats.allTimeAvgPerCalendarDay),
                                    firstSub = "Alla ${stats.totalSpanDays} dagar",
                                    secondLabel = "Snitt / aktiv dag",
                                    secondValue = formatOneDecSv(stats.allTimeAvgPerActiveDay),
                                    secondSub = "${stats.activeDaysCount} aktiva (${stats.activeDaysPercent.toInt()} %)",
                                    bestLabel = "Bästa dag",
                                    bestBucket = stats.bestDay
                                )
                            }

                            StatsGranularity.WEEK -> {
                                ScrollableBucketsChartCard(
                                    title = "Per vecka",
                                    bucketsDescending = stats.weeklyBucketsDescending,
                                    accent = accent,
                                    barWidth = 48.dp
                                )
                                PeriodSummaryGrid(
                                    firstLabel = "Snitt / vecka",
                                    firstValue = formatOneDecSv(stats.allTimeAvgPerWeek),
                                    firstSub = "${stats.weeklyBucketsDescending.size} veckor",
                                    secondLabel = "Snitt / dag",
                                    secondValue = formatOneDecSv(stats.allTimeAvgPerCalendarDay),
                                    secondSub = "Aktivt snitt ${formatOneDecSv(stats.allTimeAvgPerActiveDay)}/d",
                                    bestLabel = "Bästa vecka",
                                    bestBucket = stats.bestWeek
                                )
                                PeriodBreakdownCard(
                                    title = "Veckoöversikt",
                                    buckets = stats.weeklyBucketsDescending.take(12),
                                    accent = accent
                                )
                            }

                            StatsGranularity.MONTH -> {
                                ScrollableBucketsChartCard(
                                    title = "Per månad",
                                    bucketsDescending = stats.monthlyBucketsDescending,
                                    accent = accent,
                                    barWidth = 54.dp
                                )
                                PeriodSummaryGrid(
                                    firstLabel = "Snitt / månad",
                                    firstValue = formatOneDecSv(stats.allTimeAvgPerMonth),
                                    firstSub = "${stats.monthlyBucketsDescending.size} månader",
                                    secondLabel = "Snitt / dag",
                                    secondValue = formatOneDecSv(stats.allTimeAvgPerCalendarDay),
                                    secondSub = "Aktivt snitt ${formatOneDecSv(stats.allTimeAvgPerActiveDay)}/d",
                                    bestLabel = "Bästa månad",
                                    bestBucket = stats.bestMonth
                                )
                                PeriodBreakdownCard(
                                    title = "Månadsöversikt",
                                    buckets = stats.monthlyBucketsDescending.take(12),
                                    accent = accent
                                )
                            }

                            StatsGranularity.YEAR -> {
                                ScrollableBucketsChartCard(
                                    title = "Per år",
                                    bucketsDescending = stats.yearlyBucketsDescending,
                                    accent = accent,
                                    barWidth = 68.dp
                                )
                                PeriodSummaryGrid(
                                    firstLabel = "Totalt",
                                    firstValue = stats.totalCount.toString(),
                                    firstSub = "Sedan ${stats.firstDate.format(StatsFullDateFormatter)}",
                                    secondLabel = "Snitt / dag",
                                    secondValue = formatOneDecSv(stats.allTimeAvgPerCalendarDay),
                                    secondSub = "Snitt ${formatOneDecSv(stats.allTimeAvgPerMonth)}/mån",
                                    bestLabel = "Bästa år",
                                    bestBucket = stats.bestYear
                                )
                                PeriodBreakdownCard(
                                    title = "Årsöversikt",
                                    buckets = stats.yearlyBucketsDescending,
                                    accent = accent
                                )
                            }

                            StatsGranularity.ALL_TIME -> {
                                AllTimeOverviewSection(stats = stats, accent = accent)
                            }
                        }

                        WeekdayDistributionCard(weekdays = stats.weekdayStats, accent = accent)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun ScrollableBucketsChartCard(
    title: String,
    bucketsDescending: List<StatsPeriodBucket>,
    accent: Color,
    barWidth: Dp
) {
    val listState = rememberLazyListState()
    var selectedKey by rememberSaveable(title, bucketsDescending.firstOrNull()?.key) {
        mutableStateOf(bucketsDescending.firstOrNull()?.key)
    }
    val selectedBucket = bucketsDescending.firstOrNull { it.key == selectedKey } ?: bucketsDescending.firstOrNull()
    val maxVal = (bucketsDescending.maxOfOrNull { kotlin.math.abs(it.total.toLong()) } ?: 0L).coerceAtLeast(1L)

    Card(
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
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Max $maxVal",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selectedBucket != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = selectedBucket.detailLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sub = if (selectedBucket.calendarDays > 1) {
                                "Snitt ${formatOneDecSv(selectedBucket.averagePerDay)}/dag · ${selectedBucket.activeDays}/${selectedBucket.calendarDays} aktiva dagar"
                            } else {
                                if (selectedBucket.total > 0) "Aktiv dag" else "Ingen aktivitet"
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = selectedBucket.total.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
            }

            LazyRow(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(bucketsDescending, key = { it.key }) { bucket ->
                    val magnitude = kotlin.math.abs(bucket.total.toLong())
                    val fraction = if (magnitude == 0L) 0.05f else (magnitude.toFloat() / maxVal.toFloat()).coerceIn(0.14f, 1f)
                    val isSelected = bucket.key == selectedBucket?.key
                    val barColor = when {
                        magnitude == 0L -> MaterialTheme.colorScheme.surfaceVariant
                        bucket.total < 0 -> MaterialTheme.colorScheme.error
                        isSelected -> accent
                        bucket.isCurrent -> accent.copy(alpha = 0.8f)
                        else -> accent.copy(alpha = 0.48f)
                    }
                    Column(
                        modifier = Modifier
                            .width(barWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .clickable { selectedKey = bucket.key }
                            .padding(horizontal = 3.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                if (magnitude > 0L) {
                                    Text(
                                        text = bucket.total.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.76f)
                                        .fillMaxHeight(fraction * 0.78f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                        .background(barColor)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = bucket.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected || bucket.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected || bucket.isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Text(
                text = "Svep åt höger i grafen för att skrolla bakåt i tiden · Tryck på en stapel för detaljer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PeriodSummaryGrid(
    firstLabel: String,
    firstValue: String,
    firstSub: String,
    secondLabel: String,
    secondValue: String,
    secondSub: String,
    bestLabel: String,
    bestBucket: StatsPeriodBucket?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsTileCard(firstLabel, firstValue, firstSub, Modifier.weight(1f))
            StatsTileCard(secondLabel, secondValue, secondSub, Modifier.weight(1f))
        }
        StatsTileCard(
            label = bestLabel,
            value = bestBucket?.total?.toString() ?: "–",
            subtitle = bestBucket?.detailLabel ?: "Ingen data ännu",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AllTimeOverviewSection(
    stats: CounterFullStatistics,
    accent: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsTileCard(
                label = "Totalt all time",
                value = stats.totalCount.toString(),
                subtitle = "Sedan ${stats.firstDate.format(StatsFullDateFormatter)}",
                modifier = Modifier.weight(1f)
            )
            StatsTileCard(
                label = "Aktiva dagar",
                value = "${stats.activeDaysCount} d",
                subtitle = "${stats.activeDaysPercent.toInt()} % av ${stats.totalSpanDays} d",
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsTileCard(
                label = "Snitt / dag",
                value = formatOneDecSv(stats.allTimeAvgPerCalendarDay),
                subtitle = "Aktiv dag: ${formatOneDecSv(stats.allTimeAvgPerActiveDay)}",
                modifier = Modifier.weight(1f)
            )
            StatsTileCard(
                label = "Snitt / vecka",
                value = formatOneDecSv(stats.allTimeAvgPerWeek),
                subtitle = "Månad: ${formatOneDecSv(stats.allTimeAvgPerMonth)}",
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatsTileCard(
                label = "Nuvarande svit",
                value = "${stats.currentStreakDays} d",
                subtitle = "Bästa svit: ${stats.bestStreakDays} d",
                modifier = Modifier.weight(1f)
            )
            StatsTileCard(
                label = "Bästa dag",
                value = stats.bestDay?.total?.toString() ?: "–",
                subtitle = stats.bestDay?.shortLabel ?: "Ingen data",
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Rekord", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                RecordRow("Bästa dag", stats.bestDay, accent)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RecordRow("Bästa vecka", stats.bestWeek, accent)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RecordRow("Bästa månad", stats.bestMonth, accent)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                RecordRow("Bästa år", stats.bestYear, accent)
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, bucket: StatsPeriodBucket?, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = bucket?.detailLabel ?: "Ingen data ännu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = bucket?.total?.toString() ?: "–",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (bucket != null) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PeriodBreakdownCard(
    title: String,
    buckets: List<StatsPeriodBucket>,
    accent: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            buckets.forEachIndexed { index, bucket ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = bucket.detailLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (bucket.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Snitt ${formatOneDecSv(bucket.averagePerDay)}/dag · ${bucket.activeDays} aktiva dagar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = bucket.total.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (bucket.isCurrent) accent else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayDistributionCard(
    weekdays: List<WeekdayStat>,
    accent: Color
) {
    val maxAvg = (weekdays.maxOfOrNull { it.averagePerOccurrence } ?: 0.0).coerceAtLeast(0.01)

    Card(
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
                Text("Snitt per veckodag", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "All time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                weekdays.forEach { stat ->
                    val fraction = if (stat.total == 0) 0.05f else (stat.averagePerOccurrence / maxAvg).toFloat().coerceIn(0.14f, 1f)
                    val isBest = stat.total > 0 && stat.averagePerOccurrence >= maxAvg - 0.0001
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                if (stat.total > 0) {
                                    Text(
                                        text = formatOneDecSv(stat.averagePerOccurrence),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = if (isBest) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .fillMaxHeight(fraction * 0.78f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                        .background(
                                            when {
                                                stat.total == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                                isBest -> accent
                                                else -> accent.copy(alpha = 0.55f)
                                            }
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stat.shortLabelSv,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBest) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTileCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
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

private fun formatOneDecSv(value: Double): String {
    return String.format(StatsSwedishLocale, "%.1f", value)
}
