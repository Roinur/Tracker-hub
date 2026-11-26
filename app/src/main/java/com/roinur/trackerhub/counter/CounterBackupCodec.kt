package com.roinur.trackerhub.counter

import com.roinur.trackerhub.counter.HiveBinaryCodec.HiveKey
import com.roinur.trackerhub.counter.HiveBinaryCodec.HiveValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Backup importer & exporter supporting Tracker Hub's native JSON format as well as
 * legacy manifest v3 `.zip` archives (`manifest.json`, `pref02.hive`, `myobject02.hive`,
 * and `categories.json`).
 *
 * Pure JVM (no Android framework dependencies) so it can be thoroughly unit-tested and
 * run identically on Android and host JVM.
 */
object CounterBackupCodec {

    const val SUPPORTED_FORMAT_VERSION = 3
    const val JSON_BACKUP_FORMAT = "trackerhub-backup"
    const val JSON_BACKUP_SCHEMA_VERSION = 1
    private const val TRACKERHUB_EXPORT_APP_VERSION = "1.0"
    private const val EXPORT_APP_VERSION = "16.3.0"
    private const val EXPORT_BUILD_NUMBER = "232"
    private const val EXPORT_PREF_DB_VERSION = 1500

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PREF_HIVE = "pref02.hive"
    const val ENTRY_OBJECTS_HIVE = "myobject02.hive"
    const val ENTRY_CATEGORIES = "categories.json"

    private const val MANIFEST_SYNTHETIC_TIMESTAMPS_KEY = "trackerHubSyntheticTimestamps"
    private const val EMPTY_CATEGORIES_JSON = "{\"version\":1,\"groups\":[]}"

    private const val MAX_ZIP_ENTRIES = 64
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

    private val BACKUP_FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

    class BackupValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class BackupCompatibilityMetadata(
        val perTapNotesCount: Int = 0,
        val categoryGroupsCount: Int = 0,
        val fractionalValuesRounded: Int = 0,
        val zeroValueEventsSkipped: Int = 0,
        val legacyFallbackEventsUsed: Int = 0,
        val sameDayMergedEventsCount: Int = 0,
        val timestampedEventsPreserved: Int = 0
    )

    enum class BackupSourceFormat {
        TRACKERHUB_JSON,
        LEGACY_ZIP
    }

    data class BackupInspectionResult(
        val formatVersion: Int,
        val generationId: String?,
        val createdAtIso: String?,
        val appVersion: String?,
        val buildNumber: String?,
        val prefDbVersion: Int?,
        val verifiedFilesCount: Int,
        val counters: List<CounterItem>,
        val totalEvents: Int,
        val totalActiveDays: Int,
        val totalCountSum: Long,
        val warnings: List<String>,
        val metadata: BackupCompatibilityMetadata,
        val sourceFormat: BackupSourceFormat = BackupSourceFormat.LEGACY_ZIP
    )

    data class BackupExportSummary(
        val countersCount: Int,
        val totalActiveDays: Int,
        val totalCountSum: Long,
        val archiveBytes: Int,
        val generationId: String,
        val createdAtIso: String,
        val totalEventsExported: Int = 0,
        val untimedFallbackDaysExported: Int = 0
    )

    private data class ColorThemePreset(
        val colorIndex: Int,
        val themeId: Long,
        val themeName: String,
        val accentArgb: Long
    )

    // Real TypeAdapter(3) presets observed in `myobject02.hive`, mapped 1-to-1 with TrackerHub colorIndex 0..5
    private val COLOR_PRESETS = listOf(
        ColorThemePreset(colorIndex = 0, themeId = 103L, themeName = "kDarkBeige", accentArgb = 0xFFF5F5DCL),
        ColorThemePreset(colorIndex = 1, themeId = 108L, themeName = "kDarkBlue", accentArgb = 0xFF7192CEL),
        ColorThemePreset(colorIndex = 2, themeId = 109L, themeName = "kDarkOrange", accentArgb = 0xFFF3A950L),
        ColorThemePreset(colorIndex = 3, themeId = 107L, themeName = "kDarkGreen2", accentArgb = 0xFF6EFF6EL),
        ColorThemePreset(colorIndex = 4, themeId = 110L, themeName = "kDarkGray", accentArgb = 0xFFADADADL),
        ColorThemePreset(colorIndex = 5, themeId = 104L, themeName = "kDarkRed", accentArgb = 0xFFD37A81L)
    )

    fun suggestedFileName(clock: Clock = Clock.systemDefaultZone()): String {
        val stamp = LocalDateTime.now(clock).format(BACKUP_FILENAME_FORMATTER)
        return "dc${stamp}.zip"
    }

    fun suggestedJsonFileName(clock: Clock = Clock.systemDefaultZone()): String {
        val stamp = LocalDateTime.now(clock).format(BACKUP_FILENAME_FORMATTER)
        return "trackerhub-backup-${stamp}.json"
    }

    fun exportJsonToBytes(
        counters: List<CounterItem>,
        clock: Clock = Clock.systemDefaultZone(),
        generationId: String = UUID.randomUUID().toString()
    ): Pair<ByteArray, BackupExportSummary> {
        val baos = ByteArrayOutputStream()
        val summary = exportJson(
            counters = counters,
            output = baos,
            clock = clock,
            generationId = generationId
        )
        val bytes = baos.toByteArray()
        return bytes to summary.copy(archiveBytes = bytes.size)
    }

    fun exportJson(
        counters: List<CounterItem>,
        output: OutputStream,
        clock: Clock = Clock.systemDefaultZone(),
        generationId: String = UUID.randomUUID().toString()
    ): BackupExportSummary {
        val createdAtIso = DateTimeFormatter.ISO_INSTANT.format(clock.instant())
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"format\":")
        CounterJsonCodec.appendJsonString(sb, JSON_BACKUP_FORMAT)
        sb.append(",\"schemaVersion\":").append(JSON_BACKUP_SCHEMA_VERSION)
        sb.append(",\"appVersion\":")
        CounterJsonCodec.appendJsonString(sb, TRACKERHUB_EXPORT_APP_VERSION)
        sb.append(",\"generationId\":")
        CounterJsonCodec.appendJsonString(sb, generationId)
        sb.append(",\"exportedAt\":")
        CounterJsonCodec.appendJsonString(sb, createdAtIso)
        sb.append(",\"counters\":[")
        counters.forEachIndexed { index, counter ->
            if (index > 0) sb.append(',')
            CounterJsonCodec.encodeCounter(sb, counter)
        }
        sb.append("]}")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()

        val totalDays = counters.sumOf { c -> c.dailyCounts.values.count { it != 0 } }
        val totalSum = counters.sumOf { c -> c.dailyCounts.values.sumOf { it.toLong() } }
        val totalEvents = counters.sumOf { it.events.size }

        return BackupExportSummary(
            countersCount = counters.size,
            totalActiveDays = totalDays,
            totalCountSum = totalSum,
            archiveBytes = bytes.size,
            generationId = generationId,
            createdAtIso = createdAtIso,
            totalEventsExported = totalEvents,
            untimedFallbackDaysExported = 0
        )
    }

    /**
     * Auto-detects whether [input] is a native TrackerHub JSON backup or a legacy ZIP archive
     * and validates it accordingly.
     */
    fun inspectBackup(
        input: InputStream,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): BackupInspectionResult {
        val rawBytes = readBoundedBytes(input, MAX_TOTAL_BYTES)
        return inspectBackupBytes(rawBytes, zoneId = zoneId, nowMillis = nowMillis)
    }

    fun inspectBackupBytes(
        bytes: ByteArray,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): BackupInspectionResult {
        if (bytes.isEmpty()) {
            throw BackupValidationException("Vald fil är tom.")
        }
        val isZip = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte()) &&
            (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte())
        return if (isZip) {
            inspectZipBytes(bytes, zoneId = zoneId, nowMillis = nowMillis)
        } else {
            inspectJsonBytes(bytes)
        }
    }

    fun inspectJsonBytes(bytes: ByteArray): BackupInspectionResult {
        if (bytes.size > MAX_ENTRY_BYTES) {
            throw BackupValidationException("JSON-filen är för stor (max 32 MB).")
        }
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (!text.startsWith("{")) {
            throw BackupValidationException("Okänt filformat. Välj en giltig säkerhetskopia (.json eller .zip).")
        }
        val root = BackupJsonHelper.parseRootObject(text)
            ?: throw BackupValidationException("Kunde inte tolka JSON-filen. Filen kan vara skadad.")

        val format = root["format"] as? String
        if (format != null && format != JSON_BACKUP_FORMAT) {
            throw BackupValidationException("Okänt backupformat \"$format\".")
        }
        val schemaVersion = (root["schemaVersion"] as? Number)?.toInt() ?: JSON_BACKUP_SCHEMA_VERSION
        if (format == JSON_BACKUP_FORMAT && schemaVersion > JSON_BACKUP_SCHEMA_VERSION) {
            throw BackupValidationException("Säkerhetskopian använder schemaversion $schemaVersion som inte stöds av denna version av Tracker Hub.")
        }

        val countersRaw = root["counters"] as? List<*>
            ?: throw BackupValidationException("Ogiltig JSON-säkerhetskopia: listan \"counters\" saknas.")

        val decoded = countersRaw.mapNotNull { entry ->
            CounterJsonCodec.decodeCounter(entry as? Map<*, *>)
        }
        val seenIds = HashSet<String>()
        val finalCounters = decoded.map { item ->
            var id = item.id
            if (!seenIds.add(id)) {
                var suffix = 2
                while (!seenIds.add("${id}_$suffix")) suffix += 1
                item.copy(id = "${id}_$suffix")
            } else {
                item
            }
        }

        val totalEvents = finalCounters.sumOf { it.events.size }
        val totalActiveDays = finalCounters.sumOf { c -> c.dailyCounts.values.count { it != 0 } }
        val totalCountSum = finalCounters.sumOf { c -> c.dailyCounts.values.sumOf { it.toLong() } }

        return BackupInspectionResult(
            formatVersion = schemaVersion,
            generationId = root["generationId"] as? String,
            createdAtIso = (root["exportedAt"] as? String) ?: (root["createdAt"] as? String),
            appVersion = root["appVersion"] as? String,
            buildNumber = null,
            prefDbVersion = null,
            verifiedFilesCount = 1,
            counters = finalCounters,
            totalEvents = totalEvents,
            totalActiveDays = totalActiveDays,
            totalCountSum = totalCountSum,
            warnings = emptyList(),
            metadata = BackupCompatibilityMetadata(timestampedEventsPreserved = totalEvents),
            sourceFormat = BackupSourceFormat.TRACKERHUB_JSON
        )
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw BackupValidationException("Filen är för stor för att läsas som säkerhetskopia.")
            }
            baos.write(buf, 0, read)
        }
        return baos.toByteArray()
    }

    fun exportZipToBytes(
        counters: List<CounterItem>,
        clock: Clock = Clock.systemDefaultZone(),
        zoneId: ZoneId = clock.zone,
        generationId: String = UUID.randomUUID().toString()
    ): Pair<ByteArray, BackupExportSummary> {
        val baos = ByteArrayOutputStream()
        val summary = exportZip(
            counters = counters,
            output = baos,
            clock = clock,
            zoneId = zoneId,
            generationId = generationId
        )
        val bytes = baos.toByteArray()
        return bytes to summary.copy(archiveBytes = bytes.size)
    }

    fun exportZip(
        counters: List<CounterItem>,
        output: OutputStream,
        clock: Clock = Clock.systemDefaultZone(),
        zoneId: ZoneId = clock.zone,
        generationId: String = UUID.randomUUID().toString()
    ): BackupExportSummary {
        val nowInstant = clock.instant()
        val createdAtIso = DateTimeFormatter.ISO_INSTANT.format(nowInstant)

        val maxNumericId = maxOf(counters.size, 1).toLong()
        val prefBytes = buildPrefHiveBytes(maxNumericId)
        val builtObjects = buildMyObjectsHiveBytes(counters, zoneId)
        val objectsBytes = builtObjects.bytes
        val categoriesBytes = EMPTY_CATEGORIES_JSON.toByteArray(Charsets.UTF_8)

        val manifestJson = buildManifestJson(
            generationId = generationId,
            createdAtIso = createdAtIso,
            files = linkedMapOf(
                ENTRY_PREF_HIVE to prefBytes,
                ENTRY_OBJECTS_HIVE to objectsBytes,
                ENTRY_CATEGORIES to categoriesBytes
            ),
            syntheticTimestampsByCounterId = builtObjects.syntheticTimestampsByCounterId
        )
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)

        val countingOut = CountingOutputStream(output)
        ZipOutputStream(countingOut).use { zos ->
            writeZipEntry(zos, ENTRY_MANIFEST, manifestBytes)
            writeZipEntry(zos, ENTRY_PREF_HIVE, prefBytes)
            writeZipEntry(zos, ENTRY_OBJECTS_HIVE, objectsBytes)
            writeZipEntry(zos, ENTRY_CATEGORIES, categoriesBytes)
        }

        val totalDays = counters.sumOf { c -> c.dailyCounts.values.count { it != 0 } }
        val totalSum = counters.sumOf { c -> c.dailyCounts.values.sumOf { it.toLong() } }

        return BackupExportSummary(
            countersCount = counters.size,
            totalActiveDays = totalDays,
            totalCountSum = totalSum,
            archiveBytes = countingOut.bytesWritten,
            generationId = generationId,
            createdAtIso = createdAtIso,
            totalEventsExported = builtObjects.totalEventsExported,
            untimedFallbackDaysExported = builtObjects.untimedFallbackDaysExported
        )
    }

    fun inspectZipBytes(
        bytes: ByteArray,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): BackupInspectionResult {
        return inspectZip(ByteArrayInputStream(bytes), zoneId = zoneId, nowMillis = nowMillis)
    }

    fun inspectZip(
        input: InputStream,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis()
    ): BackupInspectionResult {
        val entries = readZipSafely(input)

        val manifestBytes = entries[ENTRY_MANIFEST]
            ?: throw BackupValidationException("Ogiltig säkerhetskopia: $ENTRY_MANIFEST saknas i arkivet.")

        val manifestText = String(manifestBytes, Charsets.UTF_8)
        val manifestObj = BackupJsonHelper.parseRootObject(manifestText)
            ?: throw BackupValidationException("Ogiltig säkerhetskopia: $ENTRY_MANIFEST kunde inte tolkas som JSON.")

        val formatVersion = (manifestObj["formatVersion"] as? Number)?.toInt()
            ?: throw BackupValidationException("Ogiltig säkerhetskopia: formatVersion saknas i $ENTRY_MANIFEST.")
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw BackupValidationException(
                "Säkerhetskopian använder formatversion $formatVersion som inte stöds (endast version $SUPPORTED_FORMAT_VERSION stöds)."
            )
        }

        val generationId = manifestObj["generationId"] as? String
        val createdAtIso = manifestObj["createdAt"] as? String
        val appVersion = manifestObj["appVersion"] as? String
        val buildNumber = manifestObj["buildNumber"] as? String
        val prefDbVersion = (manifestObj["prefDbVersion"] as? Number)?.toInt()

        val syntheticTimestampsByCounterId = HashMap<String, Set<Long>>()
        val rawSynthetic = manifestObj[MANIFEST_SYNTHETIC_TIMESTAMPS_KEY] as? Map<*, *>
        if (rawSynthetic != null) {
            for ((k, v) in rawSynthetic) {
                val cid = k as? String ?: continue
                val tsList = v as? List<*> ?: continue
                val tsSet = tsList.mapNotNull { (it as? Number)?.toLong() }.toSet()
                if (tsSet.isNotEmpty()) {
                    syntheticTimestampsByCounterId[cid] = tsSet
                }
            }
        }

        val filesMap = manifestObj["files"] as? Map<*, *>
            ?: throw BackupValidationException("Ogiltig säkerhetskopia: filförteckning saknas i $ENTRY_MANIFEST.")
        if (filesMap.isEmpty()) {
            throw BackupValidationException("Ogiltig säkerhetskopia: filförteckningen i $ENTRY_MANIFEST är tom.")
        }

        var verifiedFilesCount = 0
        for ((rawName, rawMeta) in filesMap) {
            val fileName = rawName as? String
                ?: throw BackupValidationException("Ogiltigt filnamn i $ENTRY_MANIFEST.")
            val meta = rawMeta as? Map<*, *>
                ?: throw BackupValidationException("Ogiltig metadata för $fileName i $ENTRY_MANIFEST.")
            val expectedLength = (meta["length"] as? Number)?.toInt()
                ?: throw BackupValidationException("Filstorlek saknas för $fileName i $ENTRY_MANIFEST.")
            val expectedSha256 = (meta["sha256"] as? String)?.trim()?.lowercase(Locale.ROOT)
                ?: throw BackupValidationException("SHA-256 saknas för $fileName i $ENTRY_MANIFEST.")

            val fileBytes = entries[fileName]
                ?: throw BackupValidationException("Filen $fileName som anges i $ENTRY_MANIFEST saknas i arkivet.")
            if (fileBytes.size != expectedLength) {
                throw BackupValidationException(
                    "Filstorleken för $fileName stämmer inte (förväntade $expectedLength byte, hittade ${fileBytes.size} byte)."
                )
            }
            val actualSha256 = sha256Hex(fileBytes)
            if (actualSha256 != expectedSha256) {
                throw BackupValidationException(
                    "Kontrollsumman (SHA-256) för $fileName stämmer inte. Filen kan vara skadad."
                )
            }
            verifiedFilesCount += 1
        }

        val warnings = mutableListOf<String>()

        // Validate pref02.hive if present
        val prefBytes = entries[ENTRY_PREF_HIVE]
        if (prefBytes != null) {
            try {
                HiveBinaryCodec.decode(prefBytes)
            } catch (e: HiveBinaryCodec.HiveCodecException) {
                throw BackupValidationException("Ogiltig $ENTRY_PREF_HIVE: ${e.message}", e)
            }
        } else {
            warnings.add("$ENTRY_PREF_HIVE saknas i arkivet; standardinställningar används.")
        }

        // Validate categories.json if present
        var categoryGroupsCount = 0
        val categoriesBytes = entries[ENTRY_CATEGORIES]
        if (categoriesBytes != null) {
            val catObj = BackupJsonHelper.parseRootObject(String(categoriesBytes, Charsets.UTF_8))
                ?: throw BackupValidationException("Ogiltig $ENTRY_CATEGORIES: kunde inte tolka JSON.")
            val groups = catObj["groups"] as? List<*>
            categoryGroupsCount = groups?.size ?: 0
            if (categoryGroupsCount > 0) {
                warnings.add("Kategorigrupper ($categoryGroupsCount st i $ENTRY_CATEGORIES) stöds inte ännu i Tracker Hub och hoppas över.")
            }
        }

        // Decode and validate myobject02.hive
        val objectsBytes = entries[ENTRY_OBJECTS_HIVE]
            ?: throw BackupValidationException("Ogiltig säkerhetskopia: $ENTRY_OBJECTS_HIVE saknas i arkivet.")

        val decodedObjects = try {
            HiveBinaryCodec.decode(objectsBytes)
        } catch (e: HiveBinaryCodec.HiveCodecException) {
            throw BackupValidationException("Kunde inte läsa $ENTRY_OBJECTS_HIVE: ${e.message}", e)
        }

        data class ParsedCounterCandidate(
            val orderIndex: Long,
            val numericId: Long,
            val hiveKeyOrder: Int,
            val item: CounterItem,
            val eventCount: Int
        )

        val candidates = mutableListOf<ParsedCounterCandidate>()
        var perTapNotesCount = 0
        var fractionalValuesRounded = 0
        var zeroValueEventsSkipped = 0
        var legacyFallbackEventsUsed = 0
        var timestampedEventsPreserved = 0

        var hiveKeySeq = 0
        for ((hiveKey, hiveVal) in decodedObjects.liveEntries) {
            hiveKeySeq += 1
            val obj = hiveVal as? HiveValue.ObjectValue ?: continue
            if (obj.typeId != 2) continue

            val fields = obj.fields
            val numericId = (fields[0] as? HiveValue.IntValue)?.value
                ?: when (hiveKey) {
                    is HiveKey.IntKey -> hiveKey.value
                    is HiveKey.StringKey -> hiveKeySeq.toLong()
                }
            val orderIndex = (fields[22] as? HiveValue.IntValue)?.value ?: Long.MAX_VALUE

            val rawName = (fields[1] as? HiveValue.StringValue)?.value.orEmpty()
            val sanitizedName = sanitizeImportedName(rawName, fallbackIndex = hiveKeySeq)

            val rawUuid = (fields[90] as? HiveValue.StringValue)?.value?.trim().orEmpty()
            val counterId = if (rawUuid.isNotEmpty()) rawUuid else "dc-legacy-$numericId"

            val modeEnum = (fields[11] as? HiveValue.EnumValue)?.index ?: 0
            val mode = if (modeEnum == 1) CounterMode.CUMULATIVE else CounterMode.DAILY_RESET

            val colorIndex = mapThemeToColorIndex(fields[6] as? HiveValue.ObjectValue, fallbackIndex = hiveKeySeq - 1)

            // Count per-tap notes metadata in field 93 (TypeAdapter 8) without leaking text
            val metaMap = fields[93] as? HiveValue.MapValue
            if (metaMap != null) {
                for ((_, metaVal) in metaMap.entries) {
                    val metaObj = metaVal as? HiveValue.ObjectValue ?: continue
                    val noteField = metaObj.fields[2]
                    if (noteField is HiveValue.StringValue && noteField.value.isNotBlank()) {
                        perTapNotesCount += 1
                    }
                }
            }

            // Extract canonical event map from field 92, then merge any events present ONLY in legacy field 8 or 13
            val eventMap = LinkedHashMap<Long, Double>()
            val field92 = fields[92] as? HiveValue.MapValue
            if (field92 != null) {
                for ((k, v) in field92.entries) {
                    val ts = (k as? HiveValue.DateTimeValue)?.epochMillis ?: continue
                    val num = extractNumber(v) ?: continue
                    eventMap[ts] = num
                }
            }

            // Legacy field 8: Map<DateTime(day), Map<DateTime(event), int>>
            val field8 = fields[8] as? HiveValue.MapValue
            if (field8 != null) {
                for ((_, innerVal) in field8.entries) {
                    val innerMap = innerVal as? HiveValue.MapValue ?: continue
                    for ((ek, ev) in innerMap.entries) {
                        val ts = (ek as? HiveValue.DateTimeValue)?.epochMillis ?: continue
                        val num = extractNumber(ev) ?: continue
                        if (!eventMap.containsKey(ts)) {
                            eventMap[ts] = num
                            legacyFallbackEventsUsed += 1
                        }
                    }
                }
            }

            // Legacy field 13: Map<DateTime(day), Map<DateTime(event), double>>
            val field13 = fields[13] as? HiveValue.MapValue
            if (field13 != null) {
                for ((_, innerVal) in field13.entries) {
                    val innerMap = innerVal as? HiveValue.MapValue ?: continue
                    for ((ek, ev) in innerMap.entries) {
                        val ts = (ek as? HiveValue.DateTimeValue)?.epochMillis ?: continue
                        val num = extractNumber(ev) ?: continue
                        if (!eventMap.containsKey(ts)) {
                            eventMap[ts] = num
                            legacyFallbackEventsUsed += 1
                        }
                    }
                }
            }

            val dayDoubleSums = LinkedHashMap<String, Double>()
            val syntheticForCounter = syntheticTimestampsByCounterId[counterId].orEmpty()
            val preservedEvents = ArrayList<CounterEvent>(eventMap.size)
            var earliestEventMs: Long? = null
            var latestEventMs: Long? = null

            for ((ts, value) in eventMap) {
                if (earliestEventMs == null || ts < earliestEventMs) earliestEventMs = ts
                if (latestEventMs == null || ts > latestEventMs) latestEventMs = ts
                if (value == 0.0) {
                    zeroValueEventsSkipped += 1
                    continue
                }
                val roundedEventDelta = value.roundToInt()
                if (abs(value - roundedEventDelta.toDouble()) > 1e-9) {
                    fractionalValuesRounded += 1
                }
                if (ts > 0L && roundedEventDelta != 0 && ts !in syntheticForCounter) {
                    preservedEvents.add(CounterEvent(timestampEpochMillis = ts, delta = roundedEventDelta))
                }
                val dateKey = Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate().toString()
                dayDoubleSums[dateKey] = (dayDoubleSums[dateKey] ?: 0.0) + value
            }

            preservedEvents.sortWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
            timestampedEventsPreserved += preservedEvents.size

            val dailyCounts = LinkedHashMap<String, Int>()
            for ((dateKey, sumDouble) in dayDoubleSums.entries.sortedBy { it.key }) {
                val rounded = sumDouble.roundToInt()
                if (rounded != 0) {
                    dailyCounts[dateKey] = rounded
                }
            }

            val field99UpdatedMs = (fields[99] as? HiveValue.IntValue)?.value?.takeIf { it > 0L }
            val updatedAtMs = field99UpdatedMs ?: latestEventMs ?: nowMillis
            val createdAtMs = earliestEventMs ?: updatedAtMs

            candidates.add(
                ParsedCounterCandidate(
                    orderIndex = orderIndex,
                    numericId = numericId,
                    hiveKeyOrder = hiveKeySeq,
                    item = CounterItem(
                        id = counterId,
                        name = sanitizedName,
                        mode = mode,
                        colorIndex = colorIndex,
                        createdAtEpochMillis = createdAtMs,
                        updatedAtEpochMillis = updatedAtMs,
                        dailyCounts = dailyCounts,
                        events = preservedEvents
                    ),
                    eventCount = eventMap.size
                )
            )
        }

        // Order counters by display order (field 22), then numericId (field 0), then Hive frame order
        val sortedCandidates = candidates.sortedWith(
            compareBy<ParsedCounterCandidate>({ it.orderIndex }, { it.numericId }, { it.hiveKeyOrder })
        )

        // Ensure no duplicate counter IDs in the imported batch
        val seenIds = HashSet<String>()
        val finalCounters = sortedCandidates.map { candidate ->
            var id = candidate.item.id
            if (!seenIds.add(id)) {
                var suffix = 2
                while (!seenIds.add("${id}_$suffix")) {
                    suffix += 1
                }
                id = "${id}_$suffix"
                candidate.item.copy(id = id)
            } else {
                candidate.item
            }
        }

        val totalEvents = sortedCandidates.sumOf { it.eventCount }
        val totalActiveDays = finalCounters.sumOf { c -> c.dailyCounts.values.count { it != 0 } }
        val totalCountSum = finalCounters.sumOf { c -> c.dailyCounts.values.sumOf { it.toLong() } }

        if (perTapNotesCount > 0) {
            warnings.add(
                "$perTapNotesCount anteckningar kopplade till enskilda registreringar stöds inte i Tracker Hub och importeras inte."
            )
        }
        if (fractionalValuesRounded > 0) {
            warnings.add(
                "$fractionalValuesRounded decimalvärden avrundas till närmaste heltal."
            )
        }
        if (finalCounters.isNotEmpty()) {
            warnings.add(
                "Räkneläge (Daglig/Kumulativ) mappas från säkerhetskopians fält 11 och kan ändras när som helst efter import utan dataförlust."
            )
        }

        return BackupInspectionResult(
            formatVersion = formatVersion,
            generationId = generationId,
            createdAtIso = createdAtIso,
            appVersion = appVersion,
            buildNumber = buildNumber,
            prefDbVersion = prefDbVersion,
            verifiedFilesCount = verifiedFilesCount,
            counters = finalCounters,
            totalEvents = totalEvents,
            totalActiveDays = totalActiveDays,
            totalCountSum = totalCountSum,
            warnings = warnings,
            metadata = BackupCompatibilityMetadata(
                perTapNotesCount = perTapNotesCount,
                categoryGroupsCount = categoryGroupsCount,
                fractionalValuesRounded = fractionalValuesRounded,
                zeroValueEventsSkipped = zeroValueEventsSkipped,
                legacyFallbackEventsUsed = legacyFallbackEventsUsed,
                sameDayMergedEventsCount = 0,
                timestampedEventsPreserved = timestampedEventsPreserved
            )
        )
    }

    private fun buildPrefHiveBytes(maxNumericId: Long): ByteArray {
        val fields = linkedMapOf<Int, HiveValue>(
            0 to HiveValue.BoolValue(true),
            1 to HiveValue.IntValue(maxNumericId),
            2 to HiveValue.IntValue(10L),
            3 to HiveValue.IntValue(1260L),
            4 to HiveValue.DoubleValue(3.0),
            5 to HiveValue.StringValue("en"),
            6 to HiveValue.BoolValue(true),
            7 to HiveValue.BoolValue(false),
            10 to HiveValue.NullValue,
            11 to HiveValue.BoolValue(true),
            12 to HiveValue.NullValue,
            13 to HiveValue.IntValue(0L),
            14 to HiveValue.NullValue,
            15 to HiveValue.BoolValue(true),
            16 to HiveValue.EnumValue(typeId = HiveBinaryCodec.TYPE_ID_ENUM_7, index = 0),
            17 to HiveValue.IntValue(1L),
            21 to HiveValue.NullValue,
            22 to HiveValue.IntValue(16L),
            23 to HiveValue.IntValue(1L),
            30 to HiveValue.NullValue,
            31 to HiveValue.NullValue,
            32 to HiveValue.NullValue,
            33 to HiveValue.BoolValue(false),
            34 to HiveValue.NullValue,
            35 to HiveValue.IntValue(1L),
            36 to HiveValue.BoolValue(true),
            37 to HiveValue.NullValue,
            38 to HiveValue.BoolValue(false),
            39 to HiveValue.IntValue(0L),
            40 to HiveValue.IntValue(1L),
            41 to HiveValue.IntValue(1L)
        )
        return HiveBinaryCodec.encode(
            listOf(HiveKey.IntKey(0L) to HiveValue.ObjectValue(typeId = 0, fields = fields))
        )
    }

    private data class BuiltObjectsHive(
        val bytes: ByteArray,
        val totalEventsExported: Int,
        val untimedFallbackDaysExported: Int,
        val syntheticTimestampsByCounterId: Map<String, List<Long>>
    )

    private fun buildMyObjectsHiveBytes(counters: List<CounterItem>, zoneId: ZoneId): BuiltObjectsHive {
        val entries = ArrayList<Pair<HiveKey, HiveValue?>>(counters.size)
        var totalEventsExported = 0
        var untimedFallbackDaysExported = 0
        val syntheticTimestampsByCounterId = LinkedHashMap<String, List<Long>>()

        counters.forEachIndexed { index, counter ->
            val numericId = (index + 1).toLong()
            val syntheticForCounter = ArrayList<Long>()

            // Collapse any accidental duplicate millisecond timestamps defensively so Hive map keys are unique
            val validEvents = counter.events
                .asSequence()
                .filter { it.delta != 0 && it.timestampEpochMillis > 0L }
                .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
                .toList()

            val eventsByDate = LinkedHashMap<LocalDate, MutableList<CounterEvent>>()
            val usedTimestamps = HashSet<Long>(validEvents.size)
            for (event in validEvents) {
                val date = event.localDate(zoneId)
                eventsByDate.getOrPut(date) { mutableListOf() }.add(event)
                usedTimestamps.add(event.timestampEpochMillis)
            }

            val dailyCountsByDate = LinkedHashMap<LocalDate, Int>()
            for ((key, count) in counter.dailyCounts) {
                if (count == 0) continue
                val date = CounterStatsCalculator.parseLocalDateOrNull(key) ?: continue
                dailyCountsByDate[date] = count
            }

            val allDates = linkedSetOf<LocalDate>().apply {
                addAll(dailyCountsByDate.keys)
                addAll(eventsByDate.keys)
            }.sorted()

            val field92Entries = ArrayList<Pair<HiveValue, HiveValue>>()
            val field8Entries = ArrayList<Pair<HiveValue, HiveValue>>()

            for (date in allDates) {
                val rawDayEvents = eventsByDate[date].orEmpty()
                // Combine any same-millisecond events so field 92 / field 8 map keys never collide
                val collapsedByTs = LinkedHashMap<Long, Int>()
                for (ev in rawDayEvents) {
                    collapsedByTs[ev.timestampEpochMillis] = (collapsedByTs[ev.timestampEpochMillis] ?: 0) + ev.delta
                }
                val dayExportPoints = ArrayList<Pair<Long, Int>>(collapsedByTs.size + 1)
                var dayEventsSum = 0
                for ((ts, delta) in collapsedByTs) {
                    if (delta != 0) {
                        dayExportPoints.add(ts to delta)
                        dayEventsSum += delta
                    }
                }

                val dayTotal = dailyCountsByDate[date] ?: 0
                val untimedResidual = dayTotal - dayEventsSum
                if (untimedResidual != 0) {
                    // Legacy untimed daily count fallback: local noon is DST-safe across transitions
                    var fallbackMs = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
                    while (fallbackMs in usedTimestamps &&
                        Instant.ofEpochMilli(fallbackMs).atZone(zoneId).toLocalDate() == date
                    ) {
                        fallbackMs += 1L
                    }
                    usedTimestamps.add(fallbackMs)
                    syntheticForCounter.add(fallbackMs)
                    dayExportPoints.add(fallbackMs to untimedResidual)
                    untimedFallbackDaysExported += 1
                }

                dayExportPoints.sortBy { it.first }

                val field8Inner = ArrayList<Pair<HiveValue, HiveValue>>()
                for ((ts, delta) in dayExportPoints) {
                    val eventDt = HiveValue.DateTimeValue(epochMillis = ts, isUtc = false, hasTimezoneFlag = true)
                    field92Entries.add(eventDt to HiveValue.DoubleValue(delta.toDouble()))
                    totalEventsExported += 1
                    if (delta > 0) {
                        field8Inner.add(eventDt to HiveValue.IntValue(delta.toLong()))
                    }
                }

                if (field8Inner.isNotEmpty()) {
                    val midnightMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val dayDt = HiveValue.DateTimeValue(epochMillis = midnightMs, isUtc = false, hasTimezoneFlag = true)
                    field8Entries.add(dayDt to HiveValue.MapValue(field8Inner))
                }
            }

            val updatedAt = maxOf(
                counter.updatedAtEpochMillis,
                counter.createdAtEpochMillis,
                counter.lastEventEpochMillis() ?: 0L
            )
            val modeIndex = if (counter.mode == CounterMode.CUMULATIVE) 1 else 0

            val fields = linkedMapOf<Int, HiveValue>(
                0 to HiveValue.IntValue(numericId),
                1 to HiveValue.StringValue(counter.name),
                2 to HiveValue.StringValue(""),
                3 to HiveValue.StringValue(""),
                4 to HiveValue.StringValue(""),
                5 to HiveValue.IntValue(1L),
                6 to buildColorThemeObject(counter.colorIndex),
                7 to HiveValue.MapValue(emptyList()),
                8 to HiveValue.MapValue(field8Entries),
                9 to HiveValue.BoolValue(true),
                10 to HiveValue.NullValue,
                11 to HiveValue.EnumValue(typeId = HiveBinaryCodec.TYPE_ID_ENUM_7, index = modeIndex),
                12 to HiveValue.BoolValue(false),
                13 to HiveValue.NullValue,
                14 to HiveValue.NullValue,
                15 to HiveValue.NullValue,
                16 to HiveValue.IntValue(1L),
                17 to HiveValue.BoolValue(true),
                18 to HiveValue.NullValue,
                19 to HiveValue.IntValue(0L),
                20 to HiveValue.IntValue(1L),
                21 to HiveValue.IntValue(1L),
                22 to HiveValue.IntValue(numericId),
                30 to HiveValue.NullValue,
                40 to HiveValue.NullValue,
                50 to HiveValue.NullValue,
                70 to HiveValue.NullValue,
                71 to HiveValue.NullValue,
                72 to HiveValue.NullValue,
                73 to HiveValue.NullValue,
                74 to HiveValue.NullValue,
                75 to HiveValue.NullValue,
                76 to HiveValue.NullValue,
                77 to HiveValue.NullValue,
                78 to HiveValue.NullValue,
                79 to HiveValue.BoolValue(false),
                80 to HiveValue.NullValue,
                81 to HiveValue.NullValue,
                82 to HiveValue.NullValue,
                83 to HiveValue.NullValue,
                90 to HiveValue.StringValue(counter.id),
                91 to HiveValue.NullValue,
                92 to HiveValue.MapValue(field92Entries),
                93 to HiveValue.NullValue,
                94 to HiveValue.NullValue,
                95 to HiveValue.BoolValue(false),
                96 to HiveValue.NullValue,
                97 to HiveValue.IntValue(1L),
                99 to HiveValue.IntValue(updatedAt)
            )
            if (syntheticForCounter.isNotEmpty()) {
                syntheticTimestampsByCounterId[counter.id] = syntheticForCounter
            }
            entries.add(HiveKey.IntKey(numericId) to HiveValue.ObjectValue(typeId = 2, fields = fields))
        }
        return BuiltObjectsHive(
            bytes = HiveBinaryCodec.encode(entries),
            totalEventsExported = totalEventsExported,
            untimedFallbackDaysExported = untimedFallbackDaysExported,
            syntheticTimestampsByCounterId = syntheticTimestampsByCounterId
        )
    }

    private fun buildColorThemeObject(colorIndex: Int): HiveValue.ObjectValue {
        val normalized = colorIndex.coerceIn(0, CounterRepository.MAX_COLOR_INDEX)
        val preset = COLOR_PRESETS[normalized]
        val fields = linkedMapOf<Int, HiveValue>(
            0 to HiveValue.IntValue(preset.themeId),
            1 to HiveValue.StringValue(preset.themeName),
            10 to HiveValue.ColorValue(preset.accentArgb),
            11 to HiveValue.ColorValue(0xFFE0E0E0L),
            20 to HiveValue.ColorValue(0xFF303030L),
            21 to HiveValue.ColorValue(0xFF424242L),
            30 to HiveValue.ColorValue(0xFF303030L),
            31 to HiveValue.ColorValue(0xFF424242L),
            40 to HiveValue.ColorValue(preset.accentArgb),
            41 to HiveValue.ColorValue(0xFFE0E0E0L)
        )
        return HiveValue.ObjectValue(typeId = 3, fields = fields)
    }

    private fun mapThemeToColorIndex(themeObj: HiveValue.ObjectValue?, fallbackIndex: Int): Int {
        if (themeObj == null) {
            return fallbackIndex.floorMod(CounterRepository.MAX_COLOR_INDEX + 1)
        }
        val themeId = (themeObj.fields[0] as? HiveValue.IntValue)?.value
        COLOR_PRESETS.firstOrNull { it.themeId == themeId }?.let { return it.colorIndex }

        val themeName = (themeObj.fields[1] as? HiveValue.StringValue)?.value?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "blue" in themeName || "indigo" in themeName -> 1
            "orange" in themeName || "yellow" in themeName || "amber" in themeName || "brown" in themeName -> 2
            "green" in themeName || "lime" in themeName -> 3
            "purple" in themeName || "violet" in themeName || "gray" in themeName || "grey" in themeName -> 4
            "red" in themeName || "pink" in themeName || "rose" in themeName -> 5
            "teal" in themeName || "cyan" in themeName || "beige" in themeName -> 0
            else -> {
                val argb = (themeObj.fields[10] as? HiveValue.ColorValue)?.argb
                if (argb != null) closestColorIndexForArgb(argb)
                else fallbackIndex.floorMod(CounterRepository.MAX_COLOR_INDEX + 1)
            }
        }
    }

    private fun closestColorIndexForArgb(argb: Long): Int {
        val r = ((argb ushr 16) and 0xFFL).toInt()
        val g = ((argb ushr 8) and 0xFFL).toInt()
        val b = (argb and 0xFFL).toInt()
        var bestIdx = 0
        var bestDist = Long.MAX_VALUE
        for (preset in COLOR_PRESETS) {
            val pr = ((preset.accentArgb ushr 16) and 0xFFL).toInt()
            val pg = ((preset.accentArgb ushr 8) and 0xFFL).toInt()
            val pb = (preset.accentArgb and 0xFFL).toInt()
            val dr = (r - pr).toLong()
            val dg = (g - pg).toLong()
            val db = (b - pb).toLong()
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = preset.colorIndex
            }
        }
        return bestIdx
    }

    private fun extractNumber(value: HiveValue): Double? {
        return when (value) {
            is HiveValue.DoubleValue -> if (value.value.isFinite()) value.value else null
            is HiveValue.IntValue -> value.value.toDouble()
            else -> null
        }
    }

    private fun sanitizeImportedName(raw: String, fallbackIndex: Int): String {
        val cleaned = raw.trim().replace(Regex("\\s+"), " ")
        return if (cleaned.isEmpty()) "Räknare $fallbackIndex" else cleaned.take(40)
    }

    private fun readZipSafely(input: InputStream): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var totalRead = 0
        var entryCount = 0

        try {
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw BackupValidationException("Arkivet innehåller för många filer (max $MAX_ZIP_ENTRIES).")
                    }
                    val rawName = entry.name.replace('\\', '/').trimStart('/')
                    if (entry.isDirectory || rawName.isEmpty()) {
                        zis.closeEntry()
                        continue
                    }
                    if (rawName.contains("..")) {
                        throw BackupValidationException("Osäkert filnamn i arkivet: $rawName")
                    }

                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        entryBytes += n
                        totalRead += n
                        if (entryBytes > MAX_ENTRY_BYTES || totalRead > MAX_TOTAL_BYTES) {
                            throw BackupValidationException("Arkivet är för stort för att importeras säkert.")
                        }
                        baos.write(buffer, 0, n)
                    }
                    zis.closeEntry()
                    result[rawName] = baos.toByteArray()
                }
            }
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: Exception) {
            throw BackupValidationException("Filen är inte ett giltigt ZIP-arkiv: ${e.message}", e)
        }

        if (result.isEmpty()) {
            throw BackupValidationException("Filen är tom eller inte ett giltigt ZIP-arkiv.")
        }
        return result
    }

    private fun writeZipEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun buildManifestJson(
        generationId: String,
        createdAtIso: String,
        files: LinkedHashMap<String, ByteArray>,
        syntheticTimestampsByCounterId: Map<String, List<Long>> = emptyMap()
    ): String {
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"formatVersion\":").append(SUPPORTED_FORMAT_VERSION)
        sb.append(",\"generationId\":")
        BackupJsonHelper.appendJsonString(sb, generationId)
        sb.append(",\"createdAt\":")
        BackupJsonHelper.appendJsonString(sb, createdAtIso)
        sb.append(",\"appVersion\":")
        BackupJsonHelper.appendJsonString(sb, EXPORT_APP_VERSION)
        sb.append(",\"buildNumber\":")
        BackupJsonHelper.appendJsonString(sb, EXPORT_BUILD_NUMBER)
        sb.append(",\"prefDbVersion\":").append(EXPORT_PREF_DB_VERSION)
        sb.append(",\"files\":{")
        var idx = 0
        for ((name, bytes) in files) {
            if (idx > 0) sb.append(',')
            BackupJsonHelper.appendJsonString(sb, name)
            sb.append(":{\"length\":").append(bytes.size)
            sb.append(",\"sha256\":")
            BackupJsonHelper.appendJsonString(sb, sha256Hex(bytes))
            sb.append('}')
            idx += 1
        }
        sb.append('}')
        if (syntheticTimestampsByCounterId.isNotEmpty()) {
            sb.append(",\"")
            sb.append(MANIFEST_SYNTHETIC_TIMESTAMPS_KEY)
            sb.append("\":{")
            var cIdx = 0
            for ((counterId, timestamps) in syntheticTimestampsByCounterId) {
                if (timestamps.isEmpty()) continue
                if (cIdx > 0) sb.append(',')
                BackupJsonHelper.appendJsonString(sb, counterId)
                sb.append(":[")
                timestamps.forEachIndexed { tIdx, ts ->
                    if (tIdx > 0) sb.append(',')
                    sb.append(ts)
                }
                sb.append(']')
                cIdx += 1
            }
            sb.append('}')
        }
        sb.append('}')
        return sb.toString()
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hexChars = "0123456789abcdef"
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return String(out)
    }

    private fun Int.floorMod(modulus: Int): Int {
        val r = this % modulus
        return if (r < 0) r + modulus else r
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Int = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}

/**
 * Minimal dependency-free JSON parser/encoder for manifest.json & categories.json.
 */
internal object BackupJsonHelper {

    fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(Locale.ROOT, ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    fun parseRootObject(json: String): Map<String, Any?>? {
        if (json.isBlank()) return null
        return Parser(json).parseRoot()
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun parseRoot(): Map<String, Any?>? {
            return try {
                skipWs()
                if (pos >= input.length || input[pos] != '{') return null
                val obj = parseObject()
                skipWs()
                if (pos != input.length) return null
                obj
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= input.length) throw IllegalArgumentException("EOF")
            return when (input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected , or } at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWs()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected , or ] at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = input[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) throw IllegalArgumentException("Bad unicode")
                                val hex = input.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Unknown escape \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            while (pos < input.length && input[pos] in '0'..'9') pos++
            var floating = false
            if (peek('.')) {
                floating = true
                pos++
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            if (peek('e') || peek('E')) {
                floating = true
                pos++
                if (peek('+') || peek('-')) pos++
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            val token = input.substring(start, pos)
            return if (floating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Bad float $token")
            } else {
                token.toLongOrNull() ?: throw IllegalArgumentException("Bad int $token")
            }
        }

        private fun parseLiteral(lit: String, value: Any?): Any? {
            if (!input.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit")
            pos += lit.length
            return value
        }

        private fun skipWs() {
            while (pos < input.length && input[pos] in " \t\r\n") pos++
        }

        private fun peek(c: Char): Boolean = pos < input.length && input[pos] == c

        private fun expect(c: Char) {
            if (!peek(c)) throw IllegalArgumentException("Expected '$c' at $pos")
            pos++
        }
    }
}
