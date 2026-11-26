package com.roinur.trackerhub.counter

import com.roinur.trackerhub.widget.MidnightRolloverScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CounterRolloverAndStatsTest {

    private class MemoryStorage : CounterStorage {
        var raw: String? = null
        override fun readRaw(): String? = raw
        override fun writeRaw(payload: String) {
            raw = payload
        }
    }

    @Test
    fun dailyRolloverInStockholmResetsDailyDisplayWhilePreservingHistoryAndTotal() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        // 2026-11-25 23:55 CET (+01:00) == 2026-11-25T22:55:00Z
        var clock = Clock.fixed(Instant.parse("2026-11-25T22:55:00Z"), stockholm)
        val repo = CounterRepository(storage = MemoryStorage(), clockProvider = { clock })

        val daily = repo.createCounter("Vatten", CounterMode.DAILY_RESET)
        val cumulative = repo.createCounter("Träning", CounterMode.CUMULATIVE)

        repeat(4) { repo.adjustCounter(daily.id, +1) }
        repeat(3) { repo.adjustCounter(cumulative.id, +1) }

        assertEquals(LocalDate.of(2026, 11, 25), repo.currentDate())
        assertEquals(4, repo.currentSnapshot().counterById(daily.id)!!.primaryDisplayValue(repo.currentDate()))
        assertEquals(3, repo.currentSnapshot().counterById(cumulative.id)!!.primaryDisplayValue(repo.currentDate()))

        // Cross local midnight -> 2026-11-26 00:01 CET (+01:00) == 2026-11-25T23:01:00Z
        clock = Clock.fixed(Instant.parse("2026-11-25T23:01:00Z"), stockholm)
        assertTrue(repo.syncDateRollover())
        assertFalse(repo.syncDateRollover())

        val today = repo.currentDate()
        assertEquals(LocalDate.of(2026, 11, 26), today)

        val dailyAfter = repo.currentSnapshot().counterById(daily.id)!!
        assertEquals(0, dailyAfter.primaryDisplayValue(today))
        assertEquals(0, dailyAfter.todayCount(today))
        assertEquals(4, dailyAfter.totalCount())
        assertEquals(4, dailyAfter.countForDate(LocalDate.of(2026, 11, 25)))

        val cumulativeAfter = repo.currentSnapshot().counterById(cumulative.id)!!
        assertEquals(3, cumulativeAfter.primaryDisplayValue(today))
        assertEquals(0, cumulativeAfter.todayCount(today))
        assertEquals(3, cumulativeAfter.totalCount())

        // Increment on new day adds to new date while keeping previous day intact
        repo.adjustCounter(daily.id, +1)
        repo.adjustCounter(cumulative.id, +1)

        val dailyDay2 = repo.currentSnapshot().counterById(daily.id)!!
        assertEquals(1, dailyDay2.primaryDisplayValue(today))
        assertEquals(5, dailyDay2.totalCount())
        assertEquals(mapOf("2026-11-25" to 4, "2026-11-26" to 1), dailyDay2.dailyCounts)

        val cumulativeDay2 = repo.currentSnapshot().counterById(cumulative.id)!!
        assertEquals(4, cumulativeDay2.primaryDisplayValue(today))
        assertEquals(1, cumulativeDay2.todayCount(today))
        assertEquals(4, cumulativeDay2.totalCount())
    }

    @Test
    fun timezoneChangeTriggersRolloverForSameInstant() {
        val instant = Instant.parse("2026-11-25T23:30:00Z")
        // In UTC it's still 2026-11-25 23:30
        var clock = Clock.fixed(instant, ZoneId.of("UTC"))
        val repo = CounterRepository(storage = MemoryStorage(), clockProvider = { clock })
        val counter = repo.createCounter("Kaffe", CounterMode.DAILY_RESET)
        repo.adjustCounter(counter.id, +1)
        assertEquals(1, repo.currentSnapshot().counterById(counter.id)!!.primaryDisplayValue(repo.currentDate()))

        // Same instant in Europe/Stockholm (UTC+1) is 2026-11-26 00:30 -> rolled over!
        clock = Clock.fixed(instant, ZoneId.of("Europe/Stockholm"))
        assertTrue(repo.syncDateRollover())
        assertEquals(LocalDate.of(2026, 11, 26), repo.currentDate())
        val updated = repo.currentSnapshot().counterById(counter.id)!!
        assertEquals(0, updated.primaryDisplayValue(repo.currentDate()))
        assertEquals(1, updated.totalCount())
    }

    @Test
    fun switchingModePreservesAllHistoryAndTotals() {
        var clock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneId.of("Europe/Stockholm"))
        val repo = CounterRepository(storage = MemoryStorage(), clockProvider = { clock })
        val counter = repo.createCounter("Sidor", CounterMode.DAILY_RESET)
        repeat(5) { repo.adjustCounter(counter.id, +1) }

        clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("Europe/Stockholm"))
        repeat(2) { repo.adjustCounter(counter.id, +1) }

        val switchedToCumulative = repo.updateCounter(counter.id, mode = CounterMode.CUMULATIVE)!!
        assertEquals(7, switchedToCumulative.primaryDisplayValue(repo.currentDate()))
        assertEquals(2, switchedToCumulative.todayCount(repo.currentDate()))
        assertEquals(7, switchedToCumulative.totalCount())

        val switchedBackToDaily = repo.updateCounter(counter.id, mode = CounterMode.DAILY_RESET)!!
        assertEquals(2, switchedBackToDaily.primaryDisplayValue(repo.currentDate()))
        assertEquals(7, switchedBackToDaily.totalCount())
    }

    @Test
    fun midnightSchedulerHandlesRegularAndDstDaysAccurately() {
        val stockholm = ZoneId.of("Europe/Stockholm")

        // Regular winter day: 2026-11-25 14:00 CET -> next midnight is 2026-11-26 00:00:01 CET
        val regularNow = ZonedDateTime.of(2026, 11, 25, 14, 0, 0, 0, stockholm).toInstant()
        val regularNext = Instant.ofEpochMilli(
            MidnightRolloverScheduler.computeNextMidnightEpochMillis(regularNow, stockholm)
        ).atZone(stockholm)
        assertEquals(LocalDate.of(2026, 11, 26), regularNext.toLocalDate())
        assertEquals(0, regularNext.hour)
        assertEquals(0, regularNext.minute)
        assertEquals(1, regularNext.second)

        // DST spring-forward day in Europe/Stockholm (2026-03-29 is 23 hours long)
        val springNow = ZonedDateTime.of(2026, 3, 29, 0, 30, 0, 0, stockholm).toInstant()
        val springNextMillis = MidnightRolloverScheduler.computeNextMidnightEpochMillis(springNow, stockholm)
        val springNext = Instant.ofEpochMilli(springNextMillis).atZone(stockholm)
        assertEquals(LocalDate.of(2026, 3, 30), springNext.toLocalDate())
        assertEquals(0, springNext.hour)
        assertEquals(0, springNext.minute)
        assertEquals(1, springNext.second)
        // Elapsed duration from 00:30 CET to next midnight + 1s CEST should be 22h 30m 1s (not 23h 30m 1s!)
        val expectedSpringElapsedMs = ((22 * 60 + 30) * 60 + 1) * 1000L
        assertEquals(expectedSpringElapsedMs, springNextMillis - springNow.toEpochMilli())

        // DST fall-back day in Europe/Stockholm (2026-10-25 is 25 hours long)
        val fallNow = ZonedDateTime.of(2026, 10, 25, 0, 30, 0, 0, stockholm).toInstant()
        val fallNextMillis = MidnightRolloverScheduler.computeNextMidnightEpochMillis(fallNow, stockholm)
        val fallNext = Instant.ofEpochMilli(fallNextMillis).atZone(stockholm)
        assertEquals(LocalDate.of(2026, 10, 26), fallNext.toLocalDate())
        assertEquals(0, fallNext.hour)
        assertEquals(0, fallNext.minute)
        assertEquals(1, fallNext.second)
        // Elapsed duration from 00:30 CEST to next midnight + 1s CET should be 24h 30m 1s
        val expectedFallElapsedMs = ((24 * 60 + 30) * 60 + 1) * 1000L
        assertEquals(expectedFallElapsedMs, fallNextMillis - fallNow.toEpochMilli())
    }

    @Test
    fun statsCalculatorAggregatesTotalsWindowsStreaksAndSeries() {
        val today = LocalDate.of(2026, 11, 26)
        val counter = CounterItem(
            id = "stats-1",
            name = "Vatten",
            mode = CounterMode.DAILY_RESET,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            dailyCounts = mapOf(
                "2026-10-20" to 8, // older than 30 days & previous month
                "2026-11-01" to 4, // this month & within 30 days
                "2026-11-02" to 6, // 2-day streak earlier
                "2026-11-23" to 3, // within 7 days
                "2026-11-24" to 8, // tie for best day (more recent than Oct 20)
                "2026-11-25" to 5, // yesterday
                "2026-11-26" to 2  // today -> 4-day active streak (23, 24, 25, 26)
            )
        )

        val stats = CounterStatsCalculator.calculate(counter, today)

        assertEquals(2, stats.todayCount)
        assertEquals(36, stats.totalCount)
        assertEquals(18, stats.last7DaysCount) // 3 + 8 + 5 + 2
        assertEquals(28, stats.last30DaysCount) // 4 + 6 + 3 + 8 + 5 + 2
        assertEquals(28, stats.thisMonthCount)
        assertEquals(7, stats.activeDaysCount)
        assertEquals(36.0 / 7.0, stats.averagePerActiveDay, 0.001)
        assertEquals(18.0 / 7.0, stats.averageLast7Days, 0.001)

        // Best day ties at 8 -> most recent date (2026-11-24) wins
        assertNotNull(stats.bestDay)
        assertEquals(LocalDate.of(2026, 11, 24), stats.bestDay!!.date)
        assertEquals(8, stats.bestDay!!.count)

        assertEquals(4, stats.currentStreakDays)
        assertEquals(4, stats.bestStreakDays)

        // 14-day series: oldest to newest, ends on today
        assertEquals(14, stats.last14DaysSeries.size)
        assertEquals(today.minusDays(13), stats.last14DaysSeries.first().date)
        assertEquals(today, stats.last14DaysSeries.last().date)
        assertEquals(2, stats.last14DaysSeries.last().count)
        assertEquals(0, stats.last14DaysSeries.first().count)

        // History sorted newest first
        assertEquals(7, stats.historyDescending.size)
        assertEquals(LocalDate.of(2026, 11, 26), stats.historyDescending.first().date)
        assertEquals(LocalDate.of(2026, 10, 20), stats.historyDescending.last().date)
    }

    @Test
    fun currentStreakStaysAliveBeforeFirstTapToday() {
        val today = LocalDate.of(2026, 11, 26)
        val counter = CounterItem(
            id = "streak-1",
            name = "Löpning",
            mode = CounterMode.DAILY_RESET,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            dailyCounts = mapOf(
                "2026-11-23" to 1,
                "2026-11-24" to 1,
                "2026-11-25" to 1
            )
        )

        val stats = CounterStatsCalculator.calculate(counter, today)
        assertEquals(0, stats.todayCount)
        assertEquals(3, stats.currentStreakDays)
        assertEquals(3, stats.bestStreakDays)
        // Legacy untimed counter exposes zero invented timestamps
        assertEquals(0, stats.timestampedEventCount)
        assertEquals(null, stats.lastEventEpochMillis)
        assertEquals(null, stats.peakHour)
        assertTrue(stats.hourlyDistribution.all { it == 0 })
        assertTrue(stats.historyDescending.all { !it.hasTimestamps && it.events.isEmpty() && it.untimedCount == it.count })
    }

    @Test
    fun statsCalculatorExposesRealEventTimestampsHourlyDistributionAndUntimedResiduals() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        val today = LocalDate.of(2026, 11, 26)
        val y1 = ZonedDateTime.of(2026, 11, 25, 8, 15, 0, 0, stockholm).toInstant().toEpochMilli()
        val y2 = ZonedDateTime.of(2026, 11, 25, 14, 30, 0, 0, stockholm).toInstant().toEpochMilli()
        val t1 = ZonedDateTime.of(2026, 11, 26, 8, 45, 0, 0, stockholm).toInstant().toEpochMilli()
        val t2 = ZonedDateTime.of(2026, 11, 26, 18, 5, 0, 0, stockholm).toInstant().toEpochMilli()

        val counter = CounterItem(
            id = "timed-1",
            name = "Kaffe",
            mode = CounterMode.DAILY_RESET,
            createdAtEpochMillis = y1,
            updatedAtEpochMillis = t2,
            dailyCounts = mapOf(
                "2026-11-24" to 3, // pure legacy untimed day
                "2026-11-25" to 4, // 2 legacy untimed + 2 real timestamps
                "2026-11-26" to 2  // fully timestamped today
            ),
            events = listOf(
                CounterEvent(t2, +1),
                CounterEvent(y1, +1),
                CounterEvent(t1, +1),
                CounterEvent(y2, +1)
            )
        )

        val stats = CounterStatsCalculator.calculate(counter, today, stockholm)

        assertEquals(4, stats.timestampedEventCount)
        assertEquals(t2, stats.lastEventEpochMillis)
        assertEquals(listOf(CounterEvent(t2, +1), CounterEvent(t1, +1)), stats.todayEventsDescending)
        assertEquals(
            listOf(CounterEvent(t2, +1), CounterEvent(t1, +1), CounterEvent(y2, +1), CounterEvent(y1, +1)),
            stats.recentEventsDescending
        )
        assertEquals(24, stats.hourlyDistribution.size)
        assertEquals(2, stats.hourlyDistribution[8])
        assertEquals(1, stats.hourlyDistribution[14])
        assertEquals(1, stats.hourlyDistribution[18])
        assertEquals(8, stats.peakHour)

        val todayEntry = stats.historyDescending[0]
        assertEquals(LocalDate.of(2026, 11, 26), todayEntry.date)
        assertTrue(todayEntry.hasTimestamps)
        assertEquals(0, todayEntry.untimedCount)
        assertEquals(listOf(CounterEvent(t1, +1), CounterEvent(t2, +1)), todayEntry.events)

        val yesterdayEntry = stats.historyDescending[1]
        assertEquals(LocalDate.of(2026, 11, 25), yesterdayEntry.date)
        assertTrue(yesterdayEntry.hasTimestamps)
        assertEquals(2, yesterdayEntry.untimedCount)
        assertEquals(listOf(CounterEvent(y1, +1), CounterEvent(y2, +1)), yesterdayEntry.events)

        val legacyEntry = stats.historyDescending[2]
        assertEquals(LocalDate.of(2026, 11, 24), legacyEntry.date)
        assertFalse(legacyEntry.hasTimestamps)
        assertEquals(3, legacyEntry.untimedCount)
        assertTrue(legacyEntry.events.isEmpty())
    }
}
