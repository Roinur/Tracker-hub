package com.example.trackerhub.counter

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

enum class CounterMode(
    val storageKey: String,
    val labelSv: String,
    val labelEn: String,
    val shortBadgeSv: String,
    val shortBadgeEn: String,
    val descriptionSv: String,
    val descriptionEn: String
) {
    DAILY_RESET(
        storageKey = "daily_reset",
        labelSv = "Daglig nollställning",
        labelEn = "Daily reset",
        shortBadgeSv = "Idag",
        shortBadgeEn = "Today",
        descriptionSv = "Börjar på 0 varje ny dag men sparar all historik och total.",
        descriptionEn = "Resets to 0 each new day while keeping all history and totals."
    ),
    CUMULATIVE(
        storageKey = "cumulative",
        labelSv = "Kumulativ",
        labelEn = "Cumulative",
        shortBadgeSv = "Totalt",
        shortBadgeEn = "Total",
        descriptionSv = "Räknar vidare dag efter dag och visar totalsumman.",
        descriptionEn = "Counts continuously across days and displays the running total."
    );

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn
    fun shortBadge(isSwedish: Boolean): String = if (isSwedish) shortBadgeSv else shortBadgeEn
    fun description(isSwedish: Boolean): String = if (isSwedish) descriptionSv else descriptionEn

    companion object {
        fun fromStorageKey(value: String?): CounterMode {
            return entries.firstOrNull { it.storageKey == value } ?: DAILY_RESET
        }
    }
}

data class CounterEvent(
    val timestampEpochMillis: Long,
    val delta: Int = 1
) {
    fun localDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
        return Instant.ofEpochMilli(timestampEpochMillis).atZone(zoneId).toLocalDate()
    }

    fun zonedDateTime(zoneId: ZoneId = ZoneId.systemDefault()): ZonedDateTime {
        return Instant.ofEpochMilli(timestampEpochMillis).atZone(zoneId)
    }
}

data class CounterItem(
    val id: String,
    val name: String,
    val mode: CounterMode,
    val colorIndex: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val dailyCounts: Map<String, Int> = emptyMap(),
    val events: List<CounterEvent> = emptyList(),
    val excludedStatsDates: Set<String> = emptySet(),
    val widgetBackgroundFile: String? = null
) {
    fun isDateExcludedFromStats(date: LocalDate): Boolean {
        return date.toString() in excludedStatsDates
    }
    fun countForDate(date: LocalDate): Int {
        return dailyCounts[date.toString()] ?: 0
    }

    fun todayCount(today: LocalDate): Int {
        val raw = countForDate(today)
        return if (mode == CounterMode.DAILY_RESET) raw.coerceAtLeast(0) else raw
    }

    fun totalCount(): Int {
        return dailyCounts.values.sum().coerceAtLeast(0)
    }

    fun primaryDisplayValue(today: LocalDate): Int {
        return when (mode) {
            CounterMode.DAILY_RESET -> countForDate(today).coerceAtLeast(0)
            CounterMode.CUMULATIVE -> totalCount()
        }
    }

    fun canDecrement(today: LocalDate): Boolean {
        return when (mode) {
            CounterMode.DAILY_RESET -> countForDate(today) > 0
            CounterMode.CUMULATIVE -> totalCount() > 0
        }
    }

    fun eventsForDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<CounterEvent> {
        if (events.isEmpty()) return emptyList()
        return events
            .filter { it.localDate(zoneId) == date }
            .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
    }

    fun recentEventsDescending(limit: Int? = null): List<CounterEvent> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedWith(
            compareByDescending<CounterEvent> { it.timestampEpochMillis }.thenByDescending { it.delta }
        )
        return if (limit != null) sorted.take(limit.coerceAtLeast(0)) else sorted
    }

    fun lastEventEpochMillis(): Long? {
        return events.maxOfOrNull { it.timestampEpochMillis }
    }

    fun untimedCountForDate(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val dayTotal = countForDate(date)
        val timedSum = events.asSequence()
            .filter { it.localDate(zoneId) == date }
            .sumOf { it.delta }
        return dayTotal - timedSum
    }
}

data class CounterStoreState(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val counters: List<CounterItem> = emptyList(),
    val widgetBindings: Map<Int, String> = emptyMap(),
    val lastObservedDate: String? = null
) {
    fun counterById(id: String?): CounterItem? {
        if (id.isNullOrBlank()) return null
        return counters.firstOrNull { it.id == id }
    }

    fun counterForWidget(appWidgetId: Int): CounterItem? {
        return counterById(widgetBindings[appWidgetId])
    }

    fun widgetCountForCounter(counterId: String): Int {
        return widgetBindings.values.count { it == counterId }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

data class DailyEntry(
    val date: LocalDate,
    val count: Int,
    val events: List<CounterEvent> = emptyList(),
    val untimedCount: Int = count - events.sumOf { it.delta },
    val excludedFromStats: Boolean = false
) {
    val hasTimestamps: Boolean
        get() = events.isNotEmpty()
}

data class CounterStats(
    val todayCount: Int,
    val totalCount: Int,
    val last7DaysCount: Int,
    val last30DaysCount: Int,
    val thisMonthCount: Int,
    val activeDaysCount: Int,
    val averagePerActiveDay: Double,
    val averageLast7Days: Double,
    val bestDay: DailyEntry?,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val last14DaysSeries: List<DailyEntry>,
    val historyDescending: List<DailyEntry>,
    val todayEventsDescending: List<CounterEvent> = emptyList(),
    val recentEventsDescending: List<CounterEvent> = emptyList(),
    val timestampedEventCount: Int = 0,
    val lastEventEpochMillis: Long? = null,
    val hourlyDistribution: List<Int> = List(24) { 0 },
    val peakHour: Int? = null
)

object CounterStatsCalculator {
    fun calculate(
        counter: CounterItem,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CounterStats {
        val parsed = counter.dailyCounts.entries.mapNotNull { (key, value) ->
            val date = parseLocalDateOrNull(key) ?: return@mapNotNull null
            if (value == 0) null else date to value
        }.toMap()
        val excludedDates = counter.excludedStatsDates.mapNotNull(::parseLocalDateOrNull).toSet()
        val statsParsed = if (excludedDates.isEmpty()) parsed else parsed.filterKeys { it !in excludedDates }

        val validEventsAsc = counter.events
            .asSequence()
            .filter { it.delta != 0 && it.timestampEpochMillis > 0L }
            .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
            .toList()

        val eventsByDate = HashMap<LocalDate, MutableList<CounterEvent>>()
        val hourBuckets = IntArray(24)
        for (event in validEventsAsc) {
            val zdt = event.zonedDateTime(zoneId)
            val eventDate = zdt.toLocalDate()
            eventsByDate.getOrPut(eventDate) { mutableListOf() }.add(event)
            if (eventDate !in excludedDates) {
                hourBuckets[zdt.hour] += event.delta
            }
        }

        fun buildDailyEntry(date: LocalDate, count: Int): DailyEntry {
            val dayEvents = eventsByDate[date]?.toList().orEmpty()
            return DailyEntry(
                date = date,
                count = count,
                events = dayEvents,
                untimedCount = count - dayEvents.sumOf { it.delta },
                excludedFromStats = date in excludedDates
            )
        }

        val todayRaw = parsed[today] ?: 0
        val todayCount = if (counter.mode == CounterMode.DAILY_RESET) {
            todayRaw.coerceAtLeast(0)
        } else {
            todayRaw
        }
        val totalCount = parsed.values.sum().coerceAtLeast(0)

        val sevenDaysStart = today.minusDays(6)
        val thirtyDaysStart = today.minusDays(29)
        val currentMonth = YearMonth.from(today)

        var last7 = 0
        var last30 = 0
        var thisMonth = 0
        var activeDays = 0
        var activeSum = 0
        var bestDay: DailyEntry? = null

        val positiveDates = mutableListOf<LocalDate>()

        for ((date, count) in statsParsed) {
            if (!date.isBefore(sevenDaysStart) && !date.isAfter(today)) {
                last7 += count
            }
            if (!date.isBefore(thirtyDaysStart) && !date.isAfter(today)) {
                last30 += count
            }
            if (YearMonth.from(date) == currentMonth && !date.isAfter(today)) {
                thisMonth += count
            }
            if (count > 0) {
                activeDays += 1
                activeSum += count
                positiveDates.add(date)
                val currentBest = bestDay
                if (currentBest == null || count > currentBest.count || (count == currentBest.count && date.isAfter(currentBest.date))) {
                    bestDay = buildDailyEntry(date, count)
                }
            }
        }

        val averageActive = if (activeDays > 0) activeSum.toDouble() / activeDays.toDouble() else 0.0
        val average7 = last7.toDouble() / 7.0

        val positiveDateSet = positiveDates.toSet()
        val currentStreak = computeCurrentStreak(positiveDateSet, today)
        val bestStreak = computeBestStreak(positiveDates)

        val last14DaysSeries = (13 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            buildDailyEntry(day, statsParsed[day] ?: 0)
        }

        val historyDescending = (parsed.keys + eventsByDate.keys)
            .map { buildDailyEntry(it, parsed[it] ?: 0) }
            .sortedByDescending { it.date }

        val recentEventsDescending = validEventsAsc.asReversed()
        val todayEventsDescending = eventsByDate[today]?.asReversed()?.toList().orEmpty()
        val hourlyDistribution = hourBuckets.map { it.coerceAtLeast(0) }
        val maxHourCount = hourlyDistribution.maxOrNull() ?: 0
        val peakHour = if (maxHourCount > 0) hourlyDistribution.indexOfFirst { it == maxHourCount } else null

        return CounterStats(
            todayCount = todayCount,
            totalCount = totalCount,
            last7DaysCount = last7,
            last30DaysCount = last30,
            thisMonthCount = thisMonth,
            activeDaysCount = activeDays,
            averagePerActiveDay = averageActive,
            averageLast7Days = average7,
            bestDay = bestDay,
            currentStreakDays = currentStreak,
            bestStreakDays = bestStreak,
            last14DaysSeries = last14DaysSeries,
            historyDescending = historyDescending,
            todayEventsDescending = todayEventsDescending,
            recentEventsDescending = recentEventsDescending,
            timestampedEventCount = validEventsAsc.size,
            lastEventEpochMillis = validEventsAsc.lastOrNull()?.timestampEpochMillis,
            hourlyDistribution = hourlyDistribution,
            peakHour = peakHour
        )
    }

    private fun computeCurrentStreak(positiveDates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in positiveDates -> today
            today.minusDays(1) in positiveDates -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (cursor in positiveDates) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun computeBestStreak(positiveDates: List<LocalDate>): Int {
        if (positiveDates.isEmpty()) return 0
        val sorted = positiveDates.distinct().sorted()
        var best = 1
        var current = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1].plusDays(1)) {
                current += 1
                if (current > best) best = current
            } else {
                current = 1
            }
        }
        return best
    }

    internal fun parseLocalDateOrNull(raw: String): LocalDate? {
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
