package com.example.trackerhub.counter

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

enum class StatsGranularity(val labelSv: String, val labelEn: String) {
    DAY("Dag", "Day"),
    WEEK("Vecka", "Week"),
    MONTH("Månad", "Month"),
    YEAR("År", "Year"),
    ALL_TIME("Allt", "All time");

    fun label(isSwedish: Boolean): String = if (isSwedish) labelSv else labelEn
}

data class StatsPeriodBucket(
    val key: String,
    val shortLabel: String,
    val detailLabel: String,
    val total: Int,
    val activeDays: Int,
    val calendarDays: Int,
    val averagePerDay: Double,
    val averagePerActiveDay: Double,
    val isCurrent: Boolean
)

data class WeekdayStat(
    val dayOfWeek: DayOfWeek,
    val shortLabelSv: String,
    val total: Int,
    val occurrences: Int,
    val averagePerOccurrence: Double,
    val activeDays: Int
)

data class CounterFullStatistics(
    val firstDate: LocalDate,
    val today: LocalDate,
    val totalSpanDays: Int,
    val totalCount: Int,
    val activeDaysCount: Int,
    val activeDaysPercent: Double,
    val allTimeAvgPerCalendarDay: Double,
    val allTimeAvgPerActiveDay: Double,
    val allTimeAvgPerWeek: Double,
    val allTimeAvgPerMonth: Double,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val dailyBucketsDescending: List<StatsPeriodBucket>,
    val weeklyBucketsDescending: List<StatsPeriodBucket>,
    val monthlyBucketsDescending: List<StatsPeriodBucket>,
    val yearlyBucketsDescending: List<StatsPeriodBucket>,
    val bestDay: StatsPeriodBucket?,
    val bestWeek: StatsPeriodBucket?,
    val bestMonth: StatsPeriodBucket?,
    val bestYear: StatsPeriodBucket?,
    val weekdayStats: List<WeekdayStat>
)

object CounterFullStatisticsCalculator {
    private val swedishLocale = Locale("sv", "SE")
    private val dayShortFormatter = DateTimeFormatter.ofPattern("d MMM", swedishLocale)
    private val dayDetailFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", swedishLocale)
    private val monthShortFormatter = DateTimeFormatter.ofPattern("MMM", swedishLocale)
    private val monthYearShortFormatter = DateTimeFormatter.ofPattern("MMM yy", swedishLocale)
    private val monthDetailFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", swedishLocale)

    private val weekdayShortNamesSv = mapOf(
        DayOfWeek.MONDAY to "Mån",
        DayOfWeek.TUESDAY to "Tis",
        DayOfWeek.WEDNESDAY to "Ons",
        DayOfWeek.THURSDAY to "Tor",
        DayOfWeek.FRIDAY to "Fre",
        DayOfWeek.SATURDAY to "Lör",
        DayOfWeek.SUNDAY to "Sön"
    )

    private val weekdayShortNamesEn = mapOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun"
    )

    fun calculate(counter: CounterItem, today: LocalDate, isSwedish: Boolean = true): CounterFullStatistics {
        val locale = if (isSwedish) swedishLocale else Locale.ENGLISH
        val dayShortFmt = DateTimeFormatter.ofPattern("d MMM", locale)
        val dayDetailFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
        val monthShortFmt = DateTimeFormatter.ofPattern("MMM", locale)
        val monthYearShortFmt = DateTimeFormatter.ofPattern("MMM yy", locale)
        val monthDetailFmt = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val weekdayNames = if (isSwedish) weekdayShortNamesSv else weekdayShortNamesEn
        val parsed = counter.dailyCounts.entries.mapNotNull { (key, value) ->
            val date = CounterStatsCalculator.parseLocalDateOrNull(key) ?: return@mapNotNull null
            if (value == 0 || date.isAfter(today) || key in counter.excludedStatsDates) null else date to value
        }.toMap()

        val earliestRecorded = parsed.keys.minOrNull()
        val firstDate = earliestRecorded ?: today
        val totalSpanDays = (ChronoUnit.DAYS.between(firstDate, today).toInt() + 1).coerceAtLeast(1)

        val totalCount = parsed.values.sum().coerceAtLeast(0)
        val activeDaysCount = parsed.values.count { it > 0 }
        val activeSum = parsed.values.filter { it > 0 }.sum()
        val activeDaysPercent = (activeDaysCount.toDouble() / totalSpanDays.toDouble()) * 100.0
        val avgPerCalendarDay = totalCount.toDouble() / totalSpanDays.toDouble()
        val avgPerActiveDay = if (activeDaysCount > 0) activeSum.toDouble() / activeDaysCount.toDouble() else 0.0
        val avgPerWeek = avgPerCalendarDay * 7.0
        val avgPerMonth = avgPerCalendarDay * 30.4375

        val baseStats = CounterStatsCalculator.calculate(counter, today)

        // Daily buckets: newest first, at least 30 days back or back to earliest recorded date
        val dailyStart = minOf(firstDate, today.minusDays(29))
        val dailyCount = (ChronoUnit.DAYS.between(dailyStart, today).toInt() + 1).coerceAtLeast(1)
        val dailyBuckets = ArrayList<StatsPeriodBucket>(dailyCount)
        for (offset in 0 until dailyCount) {
            val date = today.minusDays(offset.toLong())
            val count = parsed[date] ?: 0
            val shortLabel = when (offset) {
                0 -> if (isSwedish) "Idag" else "Today"
                1 -> if (isSwedish) "Igår" else "Yesterday"
                else -> date.format(dayShortFmt)
            }
            val detailLabel = date.format(dayDetailFmt).replaceFirstChar { it.titlecase(locale) }
            dailyBuckets.add(
                StatsPeriodBucket(
                    key = "d:$date",
                    shortLabel = shortLabel,
                    detailLabel = detailLabel,
                    total = count,
                    activeDays = if (count > 0) 1 else 0,
                    calendarDays = 1,
                    averagePerDay = count.toDouble(),
                    averagePerActiveDay = if (count > 0) count.toDouble() else 0.0,
                    isCurrent = offset == 0
                )
            )
        }

        // Weekly buckets (ISO Monday-Sunday): newest first, at least 12 weeks back or back to firstDate's week
        val currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val earliestWeekStart = minOf(
            firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            currentWeekStart.minusWeeks(11)
        )
        val weekTotals = HashMap<LocalDate, Int>()
        val weekActiveDays = HashMap<LocalDate, Int>()
        for ((date, count) in parsed) {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            weekTotals[monday] = (weekTotals[monday] ?: 0) + count
            if (count > 0) {
                weekActiveDays[monday] = (weekActiveDays[monday] ?: 0) + 1
            }
        }
        val isoWeekField = WeekFields.ISO.weekOfWeekBasedYear()
        val isoWeekYearField = WeekFields.ISO.weekBasedYear()
        val weekCount = (ChronoUnit.WEEKS.between(earliestWeekStart, currentWeekStart).toInt() + 1).coerceAtLeast(1)
        val weeklyBuckets = ArrayList<StatsPeriodBucket>(weekCount)
        for (w in 0 until weekCount) {
            val weekStart = currentWeekStart.minusWeeks(w.toLong())
            val weekEnd = minOf(weekStart.plusDays(6), today)
            val calDays = (ChronoUnit.DAYS.between(weekStart, weekEnd).toInt() + 1).coerceAtLeast(1)
            val total = weekTotals[weekStart] ?: 0
            val active = weekActiveDays[weekStart] ?: 0
            val weekNum = weekStart.get(isoWeekField)
            val weekYear = weekStart.get(isoWeekYearField)
            val rangeLabel = "${weekStart.format(dayShortFmt)} – ${weekStart.plusDays(6).format(dayShortFmt)}"
            weeklyBuckets.add(
                StatsPeriodBucket(
                    key = "w:$weekStart",
                    shortLabel = if (isSwedish) "v.$weekNum" else "W$weekNum",
                    detailLabel = if (isSwedish) "Vecka $weekNum ($weekYear) · $rangeLabel" else "Week $weekNum ($weekYear) · $rangeLabel",
                    total = total,
                    activeDays = active,
                    calendarDays = calDays,
                    averagePerDay = total.toDouble() / calDays.toDouble(),
                    averagePerActiveDay = if (active > 0) total.toDouble() / active.toDouble() else 0.0,
                    isCurrent = w == 0
                )
            )
        }

        // Monthly buckets: newest first, at least 6 months back or back to firstDate's month
        val currentMonth = YearMonth.from(today)
        val earliestMonth = minOf(YearMonth.from(firstDate), currentMonth.minusMonths(5))
        val monthTotals = HashMap<YearMonth, Int>()
        val monthActiveDays = HashMap<YearMonth, Int>()
        for ((date, count) in parsed) {
            val ym = YearMonth.from(date)
            monthTotals[ym] = (monthTotals[ym] ?: 0) + count
            if (count > 0) {
                monthActiveDays[ym] = (monthActiveDays[ym] ?: 0) + 1
            }
        }
        val monthCount = (ChronoUnit.MONTHS.between(earliestMonth.atDay(1), currentMonth.atDay(1)).toInt() + 1).coerceAtLeast(1)
        val monthlyBuckets = ArrayList<StatsPeriodBucket>(monthCount)
        for (m in 0 until monthCount) {
            val ym = currentMonth.minusMonths(m.toLong())
            val monthStart = ym.atDay(1)
            val monthEnd = minOf(ym.atEndOfMonth(), today)
            val calDays = (ChronoUnit.DAYS.between(monthStart, monthEnd).toInt() + 1).coerceAtLeast(1)
            val total = monthTotals[ym] ?: 0
            val active = monthActiveDays[ym] ?: 0
            val shortLabel = if (ym.year == today.year) {
                ym.format(monthShortFmt).replaceFirstChar { it.titlecase(locale) }
            } else {
                ym.format(monthYearShortFmt).replaceFirstChar { it.titlecase(locale) }
            }
            val detailLabel = ym.format(monthDetailFmt).replaceFirstChar { it.titlecase(locale) }
            monthlyBuckets.add(
                StatsPeriodBucket(
                    key = "m:$ym",
                    shortLabel = shortLabel,
                    detailLabel = detailLabel,
                    total = total,
                    activeDays = active,
                    calendarDays = calDays,
                    averagePerDay = total.toDouble() / calDays.toDouble(),
                    averagePerActiveDay = if (active > 0) total.toDouble() / active.toDouble() else 0.0,
                    isCurrent = m == 0
                )
            )
        }

        // Yearly buckets: newest first from today.year back to firstDate.year
        val yearTotals = HashMap<Int, Int>()
        val yearActiveDays = HashMap<Int, Int>()
        for ((date, count) in parsed) {
            val y = date.year
            yearTotals[y] = (yearTotals[y] ?: 0) + count
            if (count > 0) {
                yearActiveDays[y] = (yearActiveDays[y] ?: 0) + 1
            }
        }
        val earliestYear = firstDate.year
        val yearlyBuckets = (today.year downTo earliestYear).map { year ->
            val yearStart = LocalDate.of(year, 1, 1)
            val yearEnd = minOf(LocalDate.of(year, 12, 31), today)
            val calDays = (ChronoUnit.DAYS.between(yearStart, yearEnd).toInt() + 1).coerceAtLeast(1)
            val total = yearTotals[year] ?: 0
            val active = yearActiveDays[year] ?: 0
            StatsPeriodBucket(
                key = "y:$year",
                shortLabel = year.toString(),
                detailLabel = if (isSwedish) "År $year" else "Year $year",
                total = total,
                activeDays = active,
                calendarDays = calDays,
                averagePerDay = total.toDouble() / calDays.toDouble(),
                averagePerActiveDay = if (active > 0) total.toDouble() / active.toDouble() else 0.0,
                isCurrent = year == today.year
            )
        }

        // Weekday breakdown across [firstDate..today]
        val weekdayTotals = IntArray(7)
        val weekdayActive = IntArray(7)
        for ((date, count) in parsed) {
            val idx = date.dayOfWeek.value - 1
            weekdayTotals[idx] += count
            if (count > 0) weekdayActive[idx] += 1
        }
        val fullWeeks = totalSpanDays / 7
        val remainderDays = totalSpanDays % 7
        val firstDowIndex = firstDate.dayOfWeek.value - 1
        val weekdayStats = DayOfWeek.entries.map { dow ->
            val idx = dow.value - 1
            val offsetFromFirst = (idx - firstDowIndex + 7) % 7
            val occurrences = (fullWeeks + if (offsetFromFirst < remainderDays) 1 else 0).coerceAtLeast(1)
            val total = weekdayTotals[idx].coerceAtLeast(0)
            WeekdayStat(
                dayOfWeek = dow,
                shortLabelSv = weekdayNames.getValue(dow),
                total = total,
                occurrences = occurrences,
                averagePerOccurrence = total.toDouble() / occurrences.toDouble(),
                activeDays = weekdayActive[idx]
            )
        }

        return CounterFullStatistics(
            firstDate = firstDate,
            today = today,
            totalSpanDays = totalSpanDays,
            totalCount = totalCount,
            activeDaysCount = activeDaysCount,
            activeDaysPercent = activeDaysPercent,
            allTimeAvgPerCalendarDay = avgPerCalendarDay,
            allTimeAvgPerActiveDay = avgPerActiveDay,
            allTimeAvgPerWeek = avgPerWeek,
            allTimeAvgPerMonth = avgPerMonth,
            currentStreakDays = baseStats.currentStreakDays,
            bestStreakDays = baseStats.bestStreakDays,
            dailyBucketsDescending = dailyBuckets,
            weeklyBucketsDescending = weeklyBuckets,
            monthlyBucketsDescending = monthlyBuckets,
            yearlyBucketsDescending = yearlyBuckets,
            bestDay = dailyBuckets.filter { it.total > 0 }.maxByOrNull { it.total },
            bestWeek = weeklyBuckets.filter { it.total > 0 }.maxByOrNull { it.total },
            bestMonth = monthlyBuckets.filter { it.total > 0 }.maxByOrNull { it.total },
            bestYear = yearlyBuckets.filter { it.total > 0 }.maxByOrNull { it.total },
            weekdayStats = weekdayStats
        )
    }
}
