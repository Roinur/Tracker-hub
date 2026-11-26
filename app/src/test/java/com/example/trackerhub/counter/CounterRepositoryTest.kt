package com.example.trackerhub.counter

import com.example.trackerhub.widget.CounterWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CounterRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryCounterStorage : CounterStorage {
        var raw: String? = null
        override fun readRaw(): String? = raw
        override fun writeRaw(payload: String) {
            raw = payload
        }
    }

    private fun fixedClock(isoInstant: String, zone: String = "Europe/Stockholm"): Clock {
        return Clock.fixed(Instant.parse(isoInstant), ZoneId.of(zone))
    }

    @Test
    fun jsonCodecRoundTripPreservesSwedishCharactersEscapesAndBindings() {
        val original = CounterStoreState(
            schemaVersion = 1,
            lastObservedDate = "2026-11-25",
            counters = listOf(
                CounterItem(
                    id = "c-1",
                    name = "Kaffe \"På jobbet\" ÅÄÖ åäö",
                    mode = CounterMode.DAILY_RESET,
                    colorIndex = 2,
                    createdAtEpochMillis = 1_700_000_000_000L,
                    updatedAtEpochMillis = 1_700_000_050_000L,
                    dailyCounts = mapOf("2026-11-24" to 3, "2026-11-25" to 5),
                    events = listOf(
                        CounterEvent(1_700_000_001_000L, +1),
                        CounterEvent(1_700_000_002_500L, +2)
                    )
                ),
                CounterItem(
                    id = "c-2",
                    name = "Träningspass \\ Gym",
                    mode = CounterMode.CUMULATIVE,
                    colorIndex = 4,
                    createdAtEpochMillis = 1_700_000_010_000L,
                    updatedAtEpochMillis = 1_700_000_090_000L,
                    dailyCounts = mapOf("2026-11-20" to 1, "2026-11-25" to 2),
                    events = listOf(
                        CounterEvent(1_700_000_011_000L, +1),
                        CounterEvent(1_700_000_050_000L, -1)
                    )
                )
            ),
            widgetBindings = mapOf(101 to "c-1", 202 to "c-2", 303 to "c-1")
        )

        val encoded = CounterJsonCodec.encode(original)
        val decoded = CounterJsonCodec.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun jsonCodecRecoversGracefullyFromCorruptPayload() {
        val decoded = CounterJsonCodec.decode("{not-valid-json")
        assertTrue(decoded.counters.isEmpty())
        assertTrue(decoded.widgetBindings.isEmpty())
    }

    @Test
    fun atomicFileStoragePersistsAndRecoversFromBackup() {
        val storeFile = File(tempFolder.root, "counters_store_v1.json")
        var currentClock = fixedClock("2026-11-25T10:00:00Z")
        val storage = AtomicFileCounterStorage(storeFile)
        val repo1 = CounterRepository(storage = storage, clockProvider = { currentClock })

        val water = repo1.createCounter("Vatten", CounterMode.DAILY_RESET)
        repo1.adjustCounter(water.id, +1)
        repo1.adjustCounter(water.id, +1)
        repo1.bindWidget(42, water.id)

        // Reload in a brand-new repository instance
        val repo2 = CounterRepository(storage = AtomicFileCounterStorage(storeFile), clockProvider = { currentClock })
        val reloaded = repo2.currentSnapshot().counterById(water.id)
        assertNotNull(reloaded)
        assertEquals("Vatten", reloaded!!.name)
        assertEquals(2, reloaded.primaryDisplayValue(repo2.currentDate()))
        assertEquals(water.id, repo2.getBoundCounterId(42))

        // Delete main file and ensure .bak restores previous valid state
        storeFile.delete()
        val repo3 = CounterRepository(storage = AtomicFileCounterStorage(storeFile), clockProvider = { currentClock })
        assertNotNull(repo3.currentSnapshot().counterById(water.id))
    }

    @Test
    fun atomicFileStorageRecoversFromCorruptNonEmptyMainFileUsingBackup() {
        val storeFile = File(tempFolder.root, "counters_store_corrupt_test.json")
        val backupFile = File(tempFolder.root, "counters_store_corrupt_test.json.bak")
        val clock = fixedClock("2026-11-25T10:00:00Z")
        val repo1 = CounterRepository(storage = AtomicFileCounterStorage(storeFile), clockProvider = { clock })

        val coffee = repo1.createCounter("Kaffe", CounterMode.DAILY_RESET)
        repo1.adjustCounter(coffee.id, +1)
        repo1.adjustCounter(coffee.id, +1)
        repo1.bindWidget(99, coffee.id)
        assertTrue(backupFile.exists())

        // Corrupt main file with non-empty broken JSON while valid .bak exists
        storeFile.writeText("{\"schemaVersion\":2,\"counters\":[{\"id\":\"broken", Charsets.UTF_8)

        val repo2 = CounterRepository(storage = AtomicFileCounterStorage(storeFile), clockProvider = { clock })
        val restored = repo2.currentSnapshot().counterById(coffee.id)
        assertNotNull(restored)
        assertEquals("Kaffe", restored!!.name)
        assertTrue(restored.primaryDisplayValue(repo2.currentDate()) >= 1)

        // Subsequent write must heal the main file without overwriting .bak with corrupt JSON
        repo2.adjustCounter(coffee.id, +1)
        assertNotNull(CounterJsonCodec.decodeOrNull(storeFile.readText(Charsets.UTF_8)))
        assertNotNull(CounterJsonCodec.decodeOrNull(backupFile.readText(Charsets.UTF_8)))
    }

    @Test
    fun dailyResetClampsAtZeroAndResetTodayPreservesHistory() {
        val storage = InMemoryCounterStorage()
        var currentClock = fixedClock("2026-11-24T12:00:00Z")
        val repo = CounterRepository(storage = storage, clockProvider = { currentClock })

        val counter = repo.createCounter("Kaffe", CounterMode.DAILY_RESET)
        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)

        // Next day
        currentClock = fixedClock("2026-11-25T09:00:00Z")
        // Decrement at 0 must stay 0 and not reduce yesterday's total
        val afterMinusAtZero = repo.adjustCounter(counter.id, -1)!!
        assertEquals(0, afterMinusAtZero.primaryDisplayValue(repo.currentDate()))
        assertEquals(3, afterMinusAtZero.totalCount())

        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)
        val afterTwoToday = repo.currentSnapshot().counterById(counter.id)!!
        assertEquals(2, afterTwoToday.primaryDisplayValue(repo.currentDate()))
        assertEquals(5, afterTwoToday.totalCount())

        // Reset today keeps yesterday's 3
        val afterReset = repo.resetToday(counter.id)!!
        assertEquals(0, afterReset.primaryDisplayValue(repo.currentDate()))
        assertEquals(3, afterReset.totalCount())
    }

    @Test
    fun cumulativeSupportsCrossDayDecrementAndClampsTotalAtZero() {
        val storage = InMemoryCounterStorage()
        var currentClock = fixedClock("2026-11-24T12:00:00Z")
        val repo = CounterRepository(storage = storage, clockProvider = { currentClock })

        val counter = repo.createCounter("Böcker", CounterMode.CUMULATIVE)
        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)

        // Next day: total is 2; decrement by 1 -> total becomes 1
        currentClock = fixedClock("2026-11-25T12:00:00Z")
        val afterMinus = repo.adjustCounter(counter.id, -1)!!
        assertEquals(1, afterMinus.primaryDisplayValue(repo.currentDate()))
        assertEquals(1, afterMinus.totalCount())

        // Decrement twice more -> total clamps at 0
        repo.adjustCounter(counter.id, -1)
        val afterClamp = repo.adjustCounter(counter.id, -1)!!
        assertEquals(0, afterClamp.totalCount())
        assertEquals(0, afterClamp.primaryDisplayValue(repo.currentDate()))
    }

    @Test
    fun multipleCountersAndIndependentWidgetBindingsWorkCleanly() {
        val storage = InMemoryCounterStorage()
        val clock = fixedClock("2026-11-25T12:00:00Z")
        val repo = CounterRepository(storage = storage, clockProvider = { clock })

        val c1 = repo.createCounter("Vatten", CounterMode.DAILY_RESET)
        val c2 = repo.createCounter("Pushups", CounterMode.CUMULATIVE)

        repo.bindWidget(10, c1.id)
        repo.bindWidget(11, c2.id)
        repo.bindWidget(12, c1.id)

        assertEquals(listOf(10, 12), repo.getWidgetIdsForCounter(c1.id).sorted())
        assertEquals(listOf(11), repo.getWidgetIdsForCounter(c2.id))

        // Removing widget 10 keeps widget 12 and counter c1 intact
        repo.unbindWidgets(intArrayOf(10))
        assertNull(repo.getBoundCounterId(10))
        assertEquals(c1.id, repo.getBoundCounterId(12))
        assertNotNull(repo.currentSnapshot().counterById(c1.id))

        // Deleting c1 unbinds widget 12 while keeping c2 and widget 11 untouched
        assertTrue(repo.deleteCounter(c1.id))
        assertFalse(repo.deleteCounter(c1.id))
        assertNull(repo.getBoundCounterId(12))
        assertEquals(c2.id, repo.getBoundCounterId(11))

        // Request codes for distinct widget IDs & slots must never collide
        val codes = setOf(
            CounterWidgetProvider.requestCodeFor(10, 1),
            CounterWidgetProvider.requestCodeFor(10, 2),
            CounterWidgetProvider.requestCodeFor(10, 3),
            CounterWidgetProvider.requestCodeFor(11, 1),
            CounterWidgetProvider.requestCodeFor(11, 2),
            CounterWidgetProvider.requestCodeFor(11, 3)
        )
        assertEquals(6, codes.size)
        assertNotEquals(
            CounterWidgetProvider.requestCodeFor(10, 1),
            CounterWidgetProvider.requestCodeFor(11, 1)
        )
    }

    @Test
    fun legacyV1JsonDecodesDailyTotalsWithoutInventingTimestampsAndPreservesMixedDay() {
        val legacyV1Json = """
            {
              "schemaVersion": 1,
              "lastObservedDate": "2026-11-25",
              "counters": [
                {
                  "id": "legacy-c1",
                  "name": "Kaffe",
                  "mode": "daily_reset",
                  "colorIndex": 1,
                  "createdAtEpochMillis": 1700000000000,
                  "updatedAtEpochMillis": 1700000050000,
                  "dailyCounts": {"2026-11-24": 4, "2026-11-25": 3}
                }
              ],
              "widgetBindings": {"7": "legacy-c1"}
            }
        """.trimIndent()

        val storage = InMemoryCounterStorage().apply { raw = legacyV1Json }
        val stockholm = ZoneId.of("Europe/Stockholm")
        val tapTime = Instant.parse("2026-11-25T13:22:00Z") // 14:22 CET
        val repo = CounterRepository(storage = storage, clockProvider = { Clock.fixed(tapTime, stockholm) })

        val loaded = repo.currentSnapshot().counterById("legacy-c1")!!
        assertEquals(mapOf("2026-11-24" to 4, "2026-11-25" to 3), loaded.dailyCounts)
        assertTrue(loaded.events.isEmpty())
        assertEquals(3, loaded.untimedCountForDate(LocalDate.of(2026, 11, 25), stockholm))
        assertNull(loaded.lastEventEpochMillis())

        // New step on the same day records a real timestamp without inventing times for the 3 legacy counts
        val afterTap = repo.adjustCounter("legacy-c1", +1)!!
        assertEquals(4, afterTap.countForDate(LocalDate.of(2026, 11, 25)))
        assertEquals(listOf(CounterEvent(tapTime.toEpochMilli(), +1)), afterTap.events)
        assertEquals(3, afterTap.untimedCountForDate(LocalDate.of(2026, 11, 25), stockholm))
        assertEquals(4, afterTap.untimedCountForDate(LocalDate.of(2026, 11, 24), stockholm))
        assertEquals(CounterStoreState.CURRENT_SCHEMA_VERSION, repo.currentSnapshot().schemaVersion)

        // Reloading from persisted schema v2 JSON keeps both legacy counts and real event intact
        val reloaded = CounterRepository(storage = storage, clockProvider = { Clock.fixed(tapTime, stockholm) })
            .currentSnapshot()
            .counterById("legacy-c1")!!
        assertEquals(afterTap, reloaded)
    }

    @Test
    fun adjustCounterRecordsUniqueTimestampsStaysOnSameDayNearMidnightAndResetClearsTodayEvents() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        val storage = InMemoryCounterStorage()
        // 2026-11-25 23:59:59.999 CET == 2026-11-25T22:59:59.999Z
        var clock = Clock.fixed(Instant.parse("2026-11-25T22:59:59.999Z"), stockholm)
        val repo = CounterRepository(storage = storage, clockProvider = { clock })

        val counter = repo.createCounter("Vatten", CounterMode.DAILY_RESET)
        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)
        repo.adjustCounter(counter.id, +1)

        val day1Item = repo.currentSnapshot().counterById(counter.id)!!
        assertEquals(3, day1Item.events.size)
        assertEquals(3, day1Item.events.map { it.timestampEpochMillis }.toSet().size)
        // Even at 23:59:59.999, unique timestamp allocation must stay on 2026-11-25
        assertTrue(day1Item.events.all { it.localDate(stockholm) == LocalDate.of(2026, 11, 25) })

        // Next day: clamped decrement at 0 must NOT record an event
        clock = Clock.fixed(Instant.parse("2026-11-26T08:15:00Z"), stockholm)
        repo.adjustCounter(counter.id, -1)
        assertEquals(3, repo.currentSnapshot().counterById(counter.id)!!.events.size)

        // Record +1 then -1 today (nets to 0 in dailyCounts, both events recorded)
        repo.adjustCounter(counter.id, +1)
        clock = Clock.fixed(Instant.parse("2026-11-26T08:16:00Z"), stockholm)
        repo.adjustCounter(counter.id, -1)
        val afterNetZero = repo.currentSnapshot().counterById(counter.id)!!
        assertEquals(0, afterNetZero.todayCount(LocalDate.of(2026, 11, 26)))
        assertEquals(2, afterNetZero.eventsForDate(LocalDate.of(2026, 11, 26), stockholm).size)

        // resetToday removes today's events while keeping yesterday's events & counts intact
        val afterReset = repo.resetToday(counter.id)!!
        assertTrue(afterReset.eventsForDate(LocalDate.of(2026, 11, 26), stockholm).isEmpty())
        assertEquals(3, afterReset.eventsForDate(LocalDate.of(2026, 11, 25), stockholm).size)
        assertEquals(3, afterReset.totalCount())
    }
}
