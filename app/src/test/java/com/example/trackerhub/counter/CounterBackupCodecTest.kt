package com.example.trackerhub.counter

import com.example.trackerhub.counter.CounterBackupCodec.BackupValidationException
import com.example.trackerhub.counter.HiveBinaryCodec.HiveKey
import com.example.trackerhub.counter.HiveBinaryCodec.HiveValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CounterBackupCodecTest {

    private val stockholm: ZoneId = ZoneId.of("Europe/Stockholm")

    private class MemoryStorage : CounterStorage {
        var raw: String? = null
        override fun readRaw(): String? = raw
        override fun writeRaw(payload: String) {
            raw = payload
        }
    }

    @Test
    fun manifestV3ZipRoundTripPreservesCountersModesColorsIdsAndDailyCounts() {
        val clock = Clock.fixed(Instant.parse("2026-11-26T00:25:39Z"), stockholm)
        val original = listOf(
            CounterItem(
                id = "11111111-1111-4111-8111-111111111111",
                name = "Vatten ÅÄÖ",
                mode = CounterMode.DAILY_RESET,
                colorIndex = 0,
                createdAtEpochMillis = 1_760_000_000_000L,
                updatedAtEpochMillis = 1_795_560_000_000L,
                dailyCounts = mapOf(
                    "2026-03-29" to 4, // DST spring forward in Stockholm
                    "2026-10-25" to 6, // DST fall back in Stockholm
                    "2026-11-25" to 3
                )
            ),
            CounterItem(
                id = "22222222-2222-4222-8222-222222222222",
                name = "Böcker \"Lästa\"",
                mode = CounterMode.CUMULATIVE,
                colorIndex = 1,
                createdAtEpochMillis = 1_760_000_000_000L,
                updatedAtEpochMillis = 1_795_561_000_000L,
                dailyCounts = mapOf(
                    "2026-11-20" to 5,
                    "2026-11-21" to -1, // cross-day cumulative decrement
                    "2026-11-25" to 2
                )
            ),
            CounterItem(
                id = "33333333-3333-4333-8333-333333333333",
                name = "Tom räknare",
                mode = CounterMode.DAILY_RESET,
                colorIndex = 5,
                createdAtEpochMillis = 1_795_500_000_000L,
                updatedAtEpochMillis = 1_795_500_000_000L,
                dailyCounts = emptyMap()
            ),
            CounterItem(
                id = "44444444-4444-4444-8444-444444444444",
                name = "Färg 2",
                mode = CounterMode.CUMULATIVE,
                colorIndex = 2,
                createdAtEpochMillis = 1_795_500_000_000L,
                updatedAtEpochMillis = 1_795_500_000_000L,
                dailyCounts = mapOf("2026-11-24" to 7)
            ),
            CounterItem(
                id = "55555555-5555-4555-8555-555555555555",
                name = "Färg 3",
                mode = CounterMode.DAILY_RESET,
                colorIndex = 3,
                createdAtEpochMillis = 1_795_500_000_000L,
                updatedAtEpochMillis = 1_795_500_000_000L,
                dailyCounts = mapOf("2026-11-24" to 1)
            ),
            CounterItem(
                id = "66666666-6666-4666-8666-666666666666",
                name = "Färg 4",
                mode = CounterMode.CUMULATIVE,
                colorIndex = 4,
                createdAtEpochMillis = 1_795_500_000_000L,
                updatedAtEpochMillis = 1_795_500_000_000L,
                dailyCounts = mapOf("2026-11-24" to 9)
            )
        )

        val (zipBytes, exportSummary) = CounterBackupCodec.exportZipToBytes(
            counters = original,
            clock = clock,
            zoneId = stockholm
        )
        assertEquals(original.size, exportSummary.countersCount)
        assertEquals(9, exportSummary.totalActiveDays)
        assertEquals(36L, exportSummary.totalCountSum)
        assertTrue(exportSummary.archiveBytes > 0)

        // Verify expected archive entry names
        val zipEntries = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                zipEntries.add(e.name)
                zis.closeEntry()
            }
        }
        assertEquals(
            setOf("manifest.json", "pref02.hive", "myobject02.hive", "categories.json"),
            zipEntries
        )

        // Legacy untimed counters export fallback entries for Hive compatibility
        assertEquals(9, exportSummary.untimedFallbackDaysExported)

        // Import back and verify full fidelity WITHOUT inventing timestamps for legacy untimed days
        val inspected = CounterBackupCodec.inspectZipBytes(zipBytes, zoneId = stockholm)
        assertEquals(3, inspected.formatVersion)
        assertEquals(3, inspected.verifiedFilesCount)
        assertEquals(original.size, inspected.counters.size)
        assertEquals(9, inspected.totalActiveDays)
        assertEquals(36L, inspected.totalCountSum)
        assertEquals(0, inspected.metadata.timestampedEventsPreserved)

        original.forEachIndexed { idx, expected ->
            val actual = inspected.counters[idx]
            assertEquals(expected.id, actual.id)
            assertEquals(expected.name, actual.name)
            assertEquals(expected.mode, actual.mode)
            assertEquals(expected.colorIndex, actual.colorIndex)
            assertEquals(expected.dailyCounts, actual.dailyCounts)
            assertEquals(expected.updatedAtEpochMillis, actual.updatedAtEpochMillis)
            assertTrue("Untimed legacy counter must not gain synthetic timestamps", actual.events.isEmpty())
        }
    }

    @Test
    fun authenticHiveZipRoundTripPreservesRealMultiEventTimestampsInField92AndField8() {
        val clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), stockholm)
        val spring1 = LocalDate.of(2026, 3, 29).atTime(8, 12, 5).atZone(stockholm).toInstant().toEpochMilli() + 123L
        val spring2 = LocalDate.of(2026, 3, 29).atTime(19, 44, 18).atZone(stockholm).toInstant().toEpochMilli() + 456L
        val fall1 = LocalDate.of(2026, 10, 25).atTime(7, 3, 0).atZone(stockholm).toInstant().toEpochMilli()
        val fall2 = LocalDate.of(2026, 10, 25).atTime(21, 55, 11).atZone(stockholm).toInstant().toEpochMilli()
        val novNeg = LocalDate.of(2026, 11, 21).atTime(16, 10, 0).atZone(stockholm).toInstant().toEpochMilli()

        val original = listOf(
            CounterItem(
                id = "aaaa1111-1111-4111-8111-111111111111",
                name = "Kaffe",
                mode = CounterMode.DAILY_RESET,
                colorIndex = 2,
                createdAtEpochMillis = spring1,
                updatedAtEpochMillis = fall2,
                dailyCounts = mapOf("2026-03-29" to 2, "2026-10-25" to 3),
                events = listOf(
                    CounterEvent(spring1, +1),
                    CounterEvent(spring2, +1),
                    CounterEvent(fall1, +1),
                    CounterEvent(fall2, +2)
                )
            ),
            CounterItem(
                id = "bbbb2222-2222-4222-8222-222222222222",
                name = "Böcker",
                mode = CounterMode.CUMULATIVE,
                colorIndex = 1,
                createdAtEpochMillis = fall1,
                updatedAtEpochMillis = novNeg,
                dailyCounts = mapOf("2026-10-25" to 2, "2026-11-21" to -1),
                events = listOf(
                    CounterEvent(fall1, +2),
                    CounterEvent(novNeg, -1)
                )
            )
        )

        val (zipBytes, exportSummary) = CounterBackupCodec.exportZipToBytes(
            counters = original,
            clock = clock,
            zoneId = stockholm
        )
        assertEquals(6, exportSummary.totalEventsExported)
        assertEquals(0, exportSummary.untimedFallbackDaysExported)

        // Verify raw myobject02.hive contains exact authentic timestamps in field 92 and field 8,
        // and manifest.json has NO synthetic timestamp metadata when all days are timed.
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        val manifestText = String(entries["manifest.json"]!!, Charsets.UTF_8)
        assertFalse(manifestText.contains("trackerHubSyntheticTimestamps"))

        val decodedHive = HiveBinaryCodec.decode(entries["myobject02.hive"]!!)
        val firstObj = decodedHive.liveEntries[HiveKey.IntKey(1L)] as HiveValue.ObjectValue
        val f92 = firstObj.fields[92] as HiveValue.MapValue
        assertEquals(
            listOf(spring1 to 1.0, spring2 to 1.0, fall1 to 1.0, fall2 to 2.0),
            f92.entries.map { (k, v) -> (k as HiveValue.DateTimeValue).epochMillis to (v as HiveValue.DoubleValue).value }
        )
        val f8 = firstObj.fields[8] as HiveValue.MapValue
        assertEquals(2, f8.entries.size)
        val springInner = f8.entries[0].second as HiveValue.MapValue
        assertEquals(
            listOf(spring1 to 1L, spring2 to 1L),
            springInner.entries.map { (k, v) -> (k as HiveValue.DateTimeValue).epochMillis to (v as HiveValue.IntValue).value }
        )

        // Re-import and verify exact timestamp and daily count parity
        val inspected = CounterBackupCodec.inspectZipBytes(zipBytes, zoneId = stockholm)
        assertEquals(6, inspected.metadata.timestampedEventsPreserved)
        assertEquals(0, inspected.metadata.sameDayMergedEventsCount)
        original.forEachIndexed { idx, expected ->
            val actual = inspected.counters[idx]
            assertEquals(expected.id, actual.id)
            assertEquals(expected.dailyCounts, actual.dailyCounts)
            assertEquals(expected.events, actual.events)
        }
    }

    @Test
    fun suggestedFileNameFollowsDcTimestampConvention() {
        val clock = Clock.fixed(Instant.parse("2026-11-25T23:25:39Z"), stockholm) // 2026-11-26 00:25:39 CET
        assertEquals("dc20261126_002539.zip", CounterBackupCodec.suggestedFileName(clock))
    }

    @Test
    fun legacyField8AndField13MergeWithoutDoubleCountingOverlappingField92Events() {
        val day1Noon = LocalDate.of(2026, 11, 20).atTime(12, 0).atZone(stockholm).toInstant().toEpochMilli()
        val day1Evening = LocalDate.of(2026, 11, 20).atTime(18, 30).atZone(stockholm).toInstant().toEpochMilli()
        val day2Noon = LocalDate.of(2026, 11, 21).atTime(12, 0).atZone(stockholm).toInstant().toEpochMilli()
        val day1Midnight = LocalDate.of(2026, 11, 20).atStartOfDay(stockholm).toInstant().toEpochMilli()
        val day2Midnight = LocalDate.of(2026, 11, 21).atStartOfDay(stockholm).toInstant().toEpochMilli()

        val dt1 = HiveValue.DateTimeValue(day1Noon, isUtc = false, hasTimezoneFlag = true)
        val dt1b = HiveValue.DateTimeValue(day1Evening, isUtc = false, hasTimezoneFlag = true)
        val dt2 = HiveValue.DateTimeValue(day2Noon, isUtc = false, hasTimezoneFlag = true)

        val noteMeta = HiveValue.ObjectValue(
            typeId = 8,
            fields = linkedMapOf(
                0 to HiveValue.IntValue(1L),
                1 to HiveValue.IntValue(day1Noon),
                2 to HiveValue.StringValue("sample note"),
                8 to HiveValue.IntValue(60L)
            )
        )

        val counterObj = HiveValue.ObjectValue(
            typeId = 2,
            fields = linkedMapOf(
                0 to HiveValue.IntValue(10L),
                1 to HiveValue.StringValue("Mixed Legacy"),
                8 to HiveValue.MapValue(
                    listOf(
                        // overlaps with field 92 -> must NOT double-count
                        HiveValue.DateTimeValue(day1Midnight, false, true) to HiveValue.MapValue(
                            listOf(dt1 to HiveValue.IntValue(2L))
                        ),
                        // only in field 8 -> MUST be recovered
                        HiveValue.DateTimeValue(day2Midnight, false, true) to HiveValue.MapValue(
                            listOf(dt2 to HiveValue.IntValue(3L))
                        )
                    )
                ),
                11 to HiveValue.EnumValue(typeId = HiveBinaryCodec.TYPE_ID_ENUM_7, index = 0),
                22 to HiveValue.IntValue(1L),
                90 to HiveValue.StringValue("legacy-uuid-1"),
                92 to HiveValue.MapValue(
                    listOf(
                        dt1 to HiveValue.DoubleValue(2.0),
                        dt1b to HiveValue.DoubleValue(1.5) // fractional value -> rounded warning
                    )
                ),
                93 to HiveValue.MapValue(listOf(dt1 to noteMeta))
            )
        )

        val objBytes = HiveBinaryCodec.encode(listOf(HiveKey.IntKey(1L) to counterObj))
        val prefBytes = HiveBinaryCodec.encode(
            listOf(HiveKey.IntKey(0L) to HiveValue.ObjectValue(0, linkedMapOf(1 to HiveValue.IntValue(10L))))
        )
        val catBytes = "{\"version\":1,\"groups\":[{\"id\":\"g1\"}]}".toByteArray(Charsets.UTF_8)

        val zipBytes = buildCustomManifestV3Zip(prefBytes, objBytes, catBytes)
        val result = CounterBackupCodec.inspectZipBytes(zipBytes, zoneId = stockholm)

        assertEquals(1, result.counters.size)
        val item = result.counters.single()
        assertEquals("legacy-uuid-1", item.id)
        assertEquals("Mixed Legacy", item.name)
        // day1: 2.0 + 1.5 = 3.5 -> rounds to 4 (NOT doubled to 6!); day2: 3 from legacy field 8
        assertEquals(mapOf("2026-11-20" to 4, "2026-11-21" to 3), item.dailyCounts)
        assertEquals(
            listOf(
                CounterEvent(day1Noon, 2),
                CounterEvent(day1Evening, 2),
                CounterEvent(day2Noon, 3)
            ),
            item.events
        )
        assertEquals(1, result.metadata.legacyFallbackEventsUsed)
        assertEquals(1, result.metadata.perTapNotesCount)
        assertEquals(1, result.metadata.categoryGroupsCount)
        assertEquals(1, result.metadata.fractionalValuesRounded)
        assertEquals(0, result.metadata.sameDayMergedEventsCount)
        assertEquals(3, result.metadata.timestampedEventsPreserved)
        assertFalse(result.warnings.any { it.contains("slås ihop till dagssummor") })
        assertTrue(result.warnings.size >= 3)
    }

    @Test
    fun tamperedPayloadFailsSha256ValidationBeforeImport() {
        val (zipBytes, _) = CounterBackupCodec.exportZipToBytes(
            counters = listOf(
                CounterItem("id-1", "Kaffe", CounterMode.DAILY_RESET, 0, 1000L, 1000L, mapOf("2026-11-25" to 2))
            ),
            zoneId = stockholm
        )

        // Extract entries and tamper with myobject02.hive without updating manifest.json
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        val tamperedObj = entries["myobject02.hive"]!!.copyOf()
        tamperedObj[tamperedObj.size - 1] = (tamperedObj[tamperedObj.size - 1].toInt() xor 0x01).toByte()
        entries["myobject02.hive"] = tamperedObj

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }

        try {
            CounterBackupCodec.inspectZipBytes(baos.toByteArray(), zoneId = stockholm)
            fail("Expected BackupValidationException on SHA-256 mismatch")
        } catch (e: BackupValidationException) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
    }

    @Test
    fun corruptNonZipOrMissingManifestIsRejected() {
        try {
            CounterBackupCodec.inspectZipBytes("not a zip".toByteArray(), zoneId = stockholm)
            fail("Expected BackupValidationException for non-zip")
        } catch (_: BackupValidationException) {
            // expected
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("unrelated.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        try {
            CounterBackupCodec.inspectZipBytes(baos.toByteArray(), zoneId = stockholm)
            fail("Expected BackupValidationException for missing manifest.json")
        } catch (e: BackupValidationException) {
            assertTrue(e.message.orEmpty().contains("manifest.json"))
        }
    }

    @Test
    fun repositoryMergeNonDestructiveIsIdempotentAndPreservesExistingCountersAndWidgets() {
        val storage = MemoryStorage()
        val clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), stockholm)
        val repo = CounterRepository(storage = storage, clockProvider = { clock })

        val localOnly = repo.createCounter("Lokal", CounterMode.DAILY_RESET)
        repo.adjustCounter(localOnly.id, +3)
        repo.bindWidget(77, localOnly.id)

        val sharedCounter = CounterItem(
            id = "shared-1",
            name = "Vatten",
            mode = CounterMode.DAILY_RESET,
            colorIndex = 1,
            createdAtEpochMillis = 1_700_000_000_000L,
            updatedAtEpochMillis = 1_700_000_000_000L,
            dailyCounts = mapOf("2026-11-24" to 4, "2026-11-25" to 2)
        )

        // First merge import adds sharedCounter while keeping localOnly + widget 77
        val first = repo.importCounters(listOf(sharedCounter), CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(1, first.addedCount)
        assertEquals(0, first.mergedCount)
        assertEquals(2, first.totalCountersAfter)
        assertEquals(localOnly.id, repo.getBoundCounterId(77))

        // Local user increments shared-1 today (2026-11-26) and also has higher count on 2026-11-25
        repo.adjustCounter("shared-1", +5)
        repo.bindWidget(88, "shared-1")

        // Re-importing the same backup again MUST NOT double counts or wipe 2026-11-26
        val updatedBackupCounter = sharedCounter.copy(
            dailyCounts = mapOf("2026-11-23" to 1, "2026-11-24" to 4, "2026-11-25" to 6)
        )
        val second = repo.importCounters(listOf(updatedBackupCounter), CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(0, second.addedCount)
        assertEquals(1, second.mergedCount)
        assertEquals(2, second.totalCountersAfter)

        val mergedItem = repo.currentSnapshot().counterById("shared-1")!!
        assertEquals(
            mapOf("2026-11-23" to 1, "2026-11-24" to 4, "2026-11-25" to 6, "2026-11-26" to 5),
            mergedItem.dailyCounts
        )
        assertEquals(localOnly.id, repo.getBoundCounterId(77))
        assertEquals("shared-1", repo.getBoundCounterId(88))

        // Replace-all keeps widget 88 (whose counter exists in import) and unbinds widget 77
        val replaced = repo.importCounters(listOf(sharedCounter), CounterRepository.ImportStrategy.REPLACE_ALL)
        assertEquals(1, replaced.replacedCount)
        assertEquals(1, replaced.totalCountersAfter)
        assertEquals("shared-1", repo.getBoundCounterId(88))
        assertEquals(null, repo.getBoundCounterId(77))
        assertFalse(repo.currentSnapshot().counters.any { it.id == localOnly.id })
    }

    @Test
    fun reimportingTimestampedBackupOntoOld14StoreBackfillsTimesIdempotentlyWithoutDoubling() {
        val d20a = LocalDate.of(2026, 11, 20).atTime(8, 15).atZone(stockholm).toInstant().toEpochMilli()
        val d20b = LocalDate.of(2026, 11, 20).atTime(13, 40).atZone(stockholm).toInstant().toEpochMilli()
        val d20c = LocalDate.of(2026, 11, 20).atTime(19, 5).atZone(stockholm).toInstant().toEpochMilli()
        val d25a = LocalDate.of(2026, 11, 25).atTime(9, 10).atZone(stockholm).toInstant().toEpochMilli()
        val d25b = LocalDate.of(2026, 11, 25).atTime(17, 25).atZone(stockholm).toInstant().toEpochMilli()

        // Simulate existing TrackerHub 1.4 local store (v1 JSON with dailyCounts only, NO events)
        val v14StoreState = CounterStoreState(
            schemaVersion = 1,
            lastObservedDate = "2026-11-25",
            counters = listOf(
                CounterItem(
                    id = "uuid-v14-counter",
                    name = "Kaffe",
                    mode = CounterMode.DAILY_RESET,
                    colorIndex = 2,
                    createdAtEpochMillis = d20a,
                    updatedAtEpochMillis = d25b,
                    dailyCounts = mapOf(
                        "2026-11-20" to 3, // matches original ZIP exactly
                        "2026-11-25" to 4  // 2 from original ZIP + 2 tapped locally in v1.4
                    ),
                    events = emptyList()
                )
            ),
            widgetBindings = mapOf(55 to "uuid-v14-counter")
        )
        val storage = MemoryStorage().apply { raw = CounterJsonCodec.encode(v14StoreState) }
        val localTapTime = LocalDate.of(2026, 11, 26).atTime(11, 30).atZone(stockholm).toInstant()
        val repo = CounterRepository(
            storage = storage,
            clockProvider = { Clock.fixed(localTapTime, stockholm) }
        )

        // User also taps +1 on a new day after upgrading to 1.5
        repo.adjustCounter("uuid-v14-counter", +1)

        val originalBackupCounter = CounterItem(
            id = "uuid-v14-counter",
            name = "Kaffe",
            mode = CounterMode.DAILY_RESET,
            colorIndex = 2,
            createdAtEpochMillis = d20a,
            updatedAtEpochMillis = d25b,
            dailyCounts = mapOf("2026-11-20" to 3, "2026-11-25" to 2),
            events = listOf(
                CounterEvent(d20a, +1),
                CounterEvent(d20b, +1),
                CounterEvent(d20c, +1),
                CounterEvent(d25a, +1),
                CounterEvent(d25b, +1)
            )
        )

        // First re-import of original timestamped backup onto old 1.4 state
        val firstMerge = repo.importCounters(listOf(originalBackupCounter), CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(0, firstMerge.addedCount)
        assertEquals(1, firstMerge.mergedCount)
        assertEquals(5, firstMerge.totalEventsImported)

        val afterFirst = repo.currentSnapshot().counterById("uuid-v14-counter")!!
        // Counts must NOT double! 2026-11-20 stays 3, 2026-11-25 stays 4, 2026-11-26 stays 1
        assertEquals(mapOf("2026-11-20" to 3, "2026-11-25" to 4, "2026-11-26" to 1), afterFirst.dailyCounts)
        // All 5 real backup timestamps + 1 new local v1.5 tap are present
        assertEquals(
            listOf(
                CounterEvent(d20a, +1),
                CounterEvent(d20b, +1),
                CounterEvent(d20c, +1),
                CounterEvent(d25a, +1),
                CounterEvent(d25b, +1),
                CounterEvent(localTapTime.toEpochMilli(), +1)
            ),
            afterFirst.events
        )
        // Residual untimed counts: 0 on Nov 20, 2 on Nov 25 (from 1.4 local taps), 0 on Nov 26
        assertEquals(0, afterFirst.untimedCountForDate(LocalDate.of(2026, 11, 20), stockholm))
        assertEquals(2, afterFirst.untimedCountForDate(LocalDate.of(2026, 11, 25), stockholm))
        assertEquals(0, afterFirst.untimedCountForDate(LocalDate.of(2026, 11, 26), stockholm))
        assertEquals("uuid-v14-counter", repo.getBoundCounterId(55))

        // Second re-import must be 100% idempotent
        val secondMerge = repo.importCounters(listOf(originalBackupCounter), CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(0, secondMerge.addedCount)
        assertEquals(1, secondMerge.mergedCount)
        assertEquals(afterFirst, repo.currentSnapshot().counterById("uuid-v14-counter")!!)

        // Local decrement (undo) on today followed by re-importing backup with only the earlier +1 does not resurrect it
        val undoTime = localTapTime.plusSeconds(60)
        val undoRepo = CounterRepository(
            storage = storage,
            clockProvider = { Clock.fixed(undoTime, stockholm) }
        )
        undoRepo.adjustCounter("uuid-v14-counter", -1)
        val afterUndo = undoRepo.currentSnapshot().counterById("uuid-v14-counter")!!
        assertEquals(0, afterUndo.todayCount(LocalDate.of(2026, 11, 26)))
        val olderBackupWithTodayTap = originalBackupCounter.copy(
            dailyCounts = originalBackupCounter.dailyCounts + ("2026-11-26" to 1),
            events = originalBackupCounter.events + CounterEvent(localTapTime.toEpochMilli(), +1)
        )
        undoRepo.importCounters(listOf(olderBackupWithTodayTap), CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        val afterOlderReimport = undoRepo.currentSnapshot().counterById("uuid-v14-counter")!!
        assertEquals(0, afterOlderReimport.todayCount(LocalDate.of(2026, 11, 26)))
        assertEquals(afterUndo.events, afterOlderReimport.events)
    }

    /**
     * Optional private verification test that runs ONLY when the environment variable
     * `TRACKERHUB_PRIVATE_SAMPLE_ZIP` points to a local backup zip. Never logs or prints
     * personal counter names or values.
     */
    @Test
    fun optionalPrivateRealSampleValidationAndRoundTrip() {
        val samplePath = System.getenv("TRACKERHUB_PRIVATE_SAMPLE_ZIP") ?: return
        val file = File(samplePath)
        if (!file.isFile) return

        val sampleBytes = file.readBytes()
        val inspected = CounterBackupCodec.inspectZipBytes(sampleBytes, zoneId = stockholm)
        assertEquals(3, inspected.formatVersion)
        assertEquals(3, inspected.verifiedFilesCount)
        assertTrue(inspected.counters.isNotEmpty())
        assertTrue(inspected.totalEvents > 0)
        assertTrue(inspected.totalActiveDays > 0)
        assertTrue(inspected.totalCountSum > 0L)
        assertTrue(inspected.metadata.timestampedEventsPreserved > 0)
        assertTrue(inspected.counters.any { it.events.isNotEmpty() })

        // Verify re-importing onto simulated TrackerHub 1.4 untimed state backfills real timestamps without doubling
        val legacyUntimedCounters = inspected.counters.map { it.copy(events = emptyList()) }
        val legacyJson = CounterJsonCodec.encode(
            CounterStoreState(
                schemaVersion = 1,
                counters = legacyUntimedCounters,
                lastObservedDate = "2026-11-25"
            )
        )
        val upgradeRepo = CounterRepository(
            storage = MemoryStorage().apply { raw = legacyJson },
            clockProvider = { Clock.fixed(Instant.parse("2026-11-26T00:30:00Z"), stockholm) }
        )
        assertTrue(upgradeRepo.currentSnapshot().counters.all { it.events.isEmpty() })
        val backfillResult = upgradeRepo.importCounters(inspected.counters, CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(0, backfillResult.addedCount)
        assertEquals(inspected.counters.size, backfillResult.mergedCount)
        inspected.counters.forEachIndexed { idx, expected ->
            val upgraded = upgradeRepo.currentSnapshot().counters[idx]
            assertEquals(expected.dailyCounts, upgraded.dailyCounts)
            assertEquals(expected.events, upgraded.events)
        }

        // Verify non-destructive import into fresh repository + idempotent second merge + export + re-import roundtrip
        val repo = CounterRepository(
            storage = MemoryStorage(),
            clockProvider = { Clock.fixed(Instant.parse("2026-11-26T00:30:00Z"), stockholm) }
        )
        val applyResult = repo.importCounters(inspected.counters, CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(inspected.counters.size, applyResult.addedCount)
        assertEquals(inspected.counters.size, repo.currentSnapshot().counters.size)

        // Second merge must be completely idempotent (0 added, exact same dailyCounts & events)
        val beforeSecond = repo.currentSnapshot().counters
        val secondApply = repo.importCounters(inspected.counters, CounterRepository.ImportStrategy.MERGE_NON_DESTRUCTIVE)
        assertEquals(0, secondApply.addedCount)
        assertEquals(inspected.counters.size, secondApply.mergedCount)
        assertEquals(beforeSecond, repo.currentSnapshot().counters)

        // Export to manifest v3 zip and re-import -> exact parity on all counters including real timestamps
        val (reExportedBytes, exportSummary) = CounterBackupCodec.exportZipToBytes(
            counters = repo.currentSnapshot().counters,
            clock = Clock.fixed(Instant.parse("2026-11-26T00:35:00Z"), stockholm),
            zoneId = stockholm
        )
        assertEquals(inspected.counters.size, exportSummary.countersCount)
        assertEquals(inspected.totalActiveDays, exportSummary.totalActiveDays)
        assertEquals(inspected.totalCountSum, exportSummary.totalCountSum)
        assertTrue(exportSummary.totalEventsExported >= inspected.metadata.timestampedEventsPreserved)

        val reImported = CounterBackupCodec.inspectZipBytes(reExportedBytes, zoneId = stockholm)
        assertEquals(3, reImported.formatVersion)
        assertEquals(3, reImported.verifiedFilesCount)
        assertEquals(inspected.counters.size, reImported.counters.size)
        assertEquals(inspected.totalActiveDays, reImported.totalActiveDays)
        assertEquals(inspected.totalCountSum, reImported.totalCountSum)

        inspected.counters.forEachIndexed { idx, expected ->
            val actual = reImported.counters[idx]
            assertNotNull(actual)
            assertEquals(expected.id, actual.id)
            assertEquals(expected.name, actual.name)
            assertEquals(expected.mode, actual.mode)
            assertEquals(expected.colorIndex, actual.colorIndex)
            assertEquals(expected.dailyCounts, actual.dailyCounts)
            assertEquals(expected.events, actual.events)
        }
    }

    private fun buildCustomManifestV3Zip(
        prefBytes: ByteArray,
        objectsBytes: ByteArray,
        categoriesBytes: ByteArray
    ): ByteArray {
        val manifest = buildString {
            append("{\"formatVersion\":3,\"generationId\":\"test-gen\",\"createdAt\":\"2026-11-26T00:00:00Z\",")
            append("\"appVersion\":\"16.3.0\",\"buildNumber\":\"232\",\"prefDbVersion\":1500,\"files\":{")
            append("\"pref02.hive\":{\"length\":${prefBytes.size},\"sha256\":\"${CounterBackupCodec.sha256Hex(prefBytes)}\"},")
            append("\"myobject02.hive\":{\"length\":${objectsBytes.size},\"sha256\":\"${CounterBackupCodec.sha256Hex(objectsBytes)}\"},")
            append("\"categories.json\":{\"length\":${categoriesBytes.size},\"sha256\":\"${CounterBackupCodec.sha256Hex(categoriesBytes)}\"")
            append("}}}")
        }.toByteArray(Charsets.UTF_8)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun add(name: String, b: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(b)
                zos.closeEntry()
            }
            add("manifest.json", manifest)
            add("pref02.hive", prefBytes)
            add("myobject02.hive", objectsBytes)
            add("categories.json", categoriesBytes)
        }
        return baos.toByteArray()
    }
}
