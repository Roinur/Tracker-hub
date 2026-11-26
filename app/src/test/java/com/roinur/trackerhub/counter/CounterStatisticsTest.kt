package com.roinur.trackerhub.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class CounterStatisticsTest {

    @Test
    fun emptyCounterProducesZeroedSafeStatistics() {
        val today = LocalDate.of(2026, 11, 26)
        val counter = CounterItem(
            id = "empty",
            name = "Test",
            mode = CounterMode.DAILY_RESET,
            colorIndex = 0,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L
        )

        val stats = CounterFullStatisticsCalculator.calculate(counter, today)

        assertEquals(today, stats.firstDate)
        assertEquals(1, stats.totalSpanDays)
        assertEquals(0, stats.totalCount)
        assertEquals(0, stats.activeDaysCount)
        assertEquals(0.0, stats.allTimeAvgPerCalendarDay, 0.0001)
        assertEquals(30, stats.dailyBucketsDescending.size)
        assertEquals(12, stats.weeklyBucketsDescending.size)
        assertEquals(6, stats.monthlyBucketsDescending.size)
        assertEquals(1, stats.yearlyBucketsDescending.size)
        assertNull(stats.bestDay)
        assertNull(stats.bestWeek)
        assertNull(stats.bestMonth)
        assertNull(stats.bestYear)
        assertEquals(7, stats.weekdayStats.size)
    }

    @Test
    fun multiYearHistoryComputesDailyWeeklyMonthlyYearlyAndWeekdayStats() {
        val today = LocalDate.of(2026, 11, 26) // Thursday
        val counter = CounterItem(
            id = "hist",
            name = "Vatten",
            mode = CounterMode.DAILY_RESET,
            colorIndex = 1,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            dailyCounts = mapOf(
                "2025-10-15" to 5,
                "2026-11-23" to 3, // Monday
                "2026-11-25" to 4, // Wednesday
                "2026-11-26" to 6  // Thursday (today)
            )
        )

        val stats = CounterFullStatisticsCalculator.calculate(counter, today)

        assertEquals(LocalDate.of(2025, 10, 15), stats.firstDate)
        assertEquals(18, stats.totalCount)
        assertEquals(4, stats.activeDaysCount)
        assertTrue(stats.dailyBucketsDescending.size > 400)
        assertEquals("Idag", stats.dailyBucketsDescending.first().shortLabel)
        assertEquals(6, stats.dailyBucketsDescending.first().total)
        assertEquals(2, stats.yearlyBucketsDescending.size)
        assertEquals(13, stats.yearlyBucketsDescending.first().total) // 2026
        assertEquals(5, stats.yearlyBucketsDescending.last().total)   // 2025
        assertNotNull(stats.bestDay)
        assertEquals(6, stats.bestDay?.total)
        assertNotNull(stats.bestWeek)
        assertEquals(13, stats.bestWeek?.total)
        assertNotNull(stats.bestMonth)
        assertEquals(13, stats.bestMonth?.total)
        assertNotNull(stats.bestYear)
        assertEquals(13, stats.bestYear?.total)

        val thursdayStat = stats.weekdayStats.first { it.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(6, thursdayStat.total)
        assertEquals(1, thursdayStat.activeDays)
    }

    @Test
    fun excludedDateIsOmittedFromStatsAndChartsButKeptInTotalAndHistoryAndJson() {
        val today = LocalDate.of(2026, 11, 26)
        val counter = CounterItem(
            id = "books",
            name = "Books",
            mode = CounterMode.CUMULATIVE,
            colorIndex = 2,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            dailyCounts = mapOf(
                "2026-11-25" to 2,
                "2026-11-26" to 28
            ),
            excludedStatsDates = setOf("2026-11-26")
        )

        val base = CounterStatsCalculator.calculate(counter, today)
        assertEquals(30, base.totalCount)
        assertEquals(2, base.last7DaysCount)
        assertEquals(1, base.activeDaysCount)
        assertEquals(2, base.bestDay?.count)
        assertEquals(0, base.last14DaysSeries.last().count)
        assertTrue(base.last14DaysSeries.last().excludedFromStats)
        assertEquals(2, base.historyDescending.size)
        assertEquals(28, base.historyDescending.first().count)
        assertTrue(base.historyDescending.first().excludedFromStats)

        val full = CounterFullStatisticsCalculator.calculate(counter, today)
        assertEquals(2, full.totalCount)
        assertEquals(1, full.activeDaysCount)
        assertEquals(2, full.bestDay?.total)
        assertEquals(0, full.dailyBucketsDescending.first().total)

        val encoded = CounterJsonCodec.encode(CounterStoreState(counters = listOf(counter)))
        val decoded = CounterJsonCodec.decode(encoded)
        assertEquals(setOf("2026-11-26"), decoded.counters.single().excludedStatsDates)
    }

    @Test
    fun nativeJsonBackupRoundTripsExcludedDatesAndAutoDetectsJsonVsZip() {
        val counter = CounterItem(
            id = "books-json",
            name = "Books",
            mode = CounterMode.CUMULATIVE,
            colorIndex = 2,
            createdAtEpochMillis = 1_700_000_000_000L,
            updatedAtEpochMillis = 1_700_000_100_000L,
            dailyCounts = mapOf("2026-11-25" to 1, "2026-11-26" to 28),
            events = listOf(CounterEvent(timestampEpochMillis = 1_700_000_050_000L, delta = 1)),
            excludedStatsDates = setOf("2026-11-26")
        )

        val (jsonBytes, exportSummary) = CounterBackupCodec.exportJsonToBytes(listOf(counter))
        assertEquals(1, exportSummary.countersCount)
        assertEquals(2, exportSummary.totalActiveDays)
        assertEquals(29L, exportSummary.totalCountSum)

        val inspectedJson = CounterBackupCodec.inspectBackupBytes(jsonBytes)
        assertEquals(CounterBackupCodec.BackupSourceFormat.TRACKERHUB_JSON, inspectedJson.sourceFormat)
        assertEquals(1, inspectedJson.counters.size)
        val restored = inspectedJson.counters.single()
        assertEquals("Books", restored.name)
        assertEquals(CounterMode.CUMULATIVE, restored.mode)
        assertEquals(setOf("2026-11-26"), restored.excludedStatsDates)
        assertEquals(1, restored.events.size)

        val (zipBytes, _) = CounterBackupCodec.exportZipToBytes(listOf(counter))
        val inspectedZip = CounterBackupCodec.inspectBackupBytes(zipBytes)
        assertEquals(CounterBackupCodec.BackupSourceFormat.LEGACY_ZIP, inspectedZip.sourceFormat)
        assertEquals(1, inspectedZip.counters.size)
    }
}
