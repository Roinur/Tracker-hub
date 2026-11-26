package com.roinur.trackerhub.counter

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

interface CounterStorage {
    fun readRaw(): String?
    fun readBackupRaw(): String? = null
    fun writeRaw(payload: String)
}

class AtomicFileCounterStorage(
    private val file: File
) : CounterStorage {
    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private val tempFile = File(file.parentFile, "${file.name}.tmp")

    override fun readRaw(): String? {
        return readFileIfValidJson(file) ?: readFileIfValidJson(backupFile)
    }

    override fun readBackupRaw(): String? {
        return readFileIfValidJson(backupFile)
    }

    override fun writeRaw(payload: String) {
        file.parentFile?.mkdirs()
        tempFile.writeText(payload, Charsets.UTF_8)
        if (readFileIfValidJson(file) != null) {
            try {
                file.copyTo(backupFile, overwrite = true)
            } catch (_: IOException) {
                // Best-effort backup
            }
        }
        if (!tempFile.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    private fun readFileIfValidJson(target: File): String? {
        if (!target.exists() || target.length() == 0L) return null
        return try {
            val text = target.readText(Charsets.UTF_8)
            if (text.isBlank() || CounterJsonCodec.decodeOrNull(text) == null) null else text
        } catch (_: IOException) {
            null
        }
    }
}

class CounterRepository(
    private val storage: CounterStorage,
    private val clockProvider: () -> Clock = { Clock.systemDefaultZone() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private val _state: MutableStateFlow<CounterStoreState>
    val state: StateFlow<CounterStoreState>

    init {
        val loaded = loadFromStorage()
        _state = MutableStateFlow(loaded)
        state = _state.asStateFlow()
    }

    fun currentDate(): LocalDate = LocalDate.now(clockProvider())

    fun currentZoneId(): ZoneId = clockProvider().zone

    fun currentSnapshot(): CounterStoreState = synchronized(lock) { _state.value }

    fun reloadFromStorage(): CounterStoreState = synchronized(lock) {
        val loaded = loadFromStorage()
        _state.value = loaded
        loaded
    }

    /**
     * Records the current local date so timezone/midnight rollovers are tracked cleanly.
     * Daily counts are stored per ISO local date, so rolling over to a new local day
     * automatically resets DAILY_RESET display values to 0 while preserving all history
     * and cumulative totals.
     *
     * @return true if the observed local date changed since last sync.
     */
    fun syncDateRollover(): Boolean = synchronized(lock) {
        val todayStr = currentDate().toString()
        val current = _state.value
        if (current.lastObservedDate == todayStr) {
            return false
        }
        val updated = current.copy(lastObservedDate = todayStr)
        commitLocked(updated)
        return current.lastObservedDate != null
    }

    fun createCounter(
        name: String,
        mode: CounterMode,
        colorIndex: Int = nextAutoColorIndex(),
        widgetBackgroundFile: String? = null
    ): CounterItem = synchronized(lock) {
        val trimmed = sanitizeName(name)
        val nowMillis = clockProvider().millis()
        val todayStr = currentDate().toString()
        val item = CounterItem(
            id = idGenerator(),
            name = trimmed,
            mode = mode,
            colorIndex = colorIndex.coerceIn(0, MAX_COLOR_INDEX),
            createdAtEpochMillis = nowMillis,
            updatedAtEpochMillis = nowMillis,
            dailyCounts = emptyMap(),
            widgetBackgroundFile = widgetBackgroundFile?.trim()?.takeIf { it.isNotEmpty() }
        )
        val current = _state.value
        val updated = current.copy(
            counters = current.counters + item,
            lastObservedDate = todayStr
        )
        commitLocked(updated)
        item
    }

    fun updateCounter(
        counterId: String,
        name: String? = null,
        mode: CounterMode? = null,
        colorIndex: Int? = null,
        widgetBackgroundFile: String? = null,
        updateWidgetBackground: Boolean = false
    ): CounterItem? = synchronized(lock) {
        val current = _state.value
        val existing = current.counterById(counterId) ?: return null
        val nowMillis = clockProvider().millis()
        val nextBg = if (updateWidgetBackground) {
            widgetBackgroundFile?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            existing.widgetBackgroundFile
        }
        val updatedItem = existing.copy(
            name = name?.let { sanitizeName(it) } ?: existing.name,
            mode = mode ?: existing.mode,
            colorIndex = colorIndex?.coerceIn(0, MAX_COLOR_INDEX) ?: existing.colorIndex,
            widgetBackgroundFile = nextBg,
            updatedAtEpochMillis = nowMillis
        )
        val updatedList = current.counters.map { if (it.id == counterId) updatedItem else it }
        commitLocked(current.copy(counters = updatedList, lastObservedDate = currentDate().toString()))
        updatedItem
    }

    fun deleteCounter(counterId: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.counters.none { it.id == counterId }) return false
        val remainingCounters = current.counters.filterNot { it.id == counterId }
        val remainingBindings = current.widgetBindings.filterValues { it != counterId }
        commitLocked(
            current.copy(
                counters = remainingCounters,
                widgetBindings = remainingBindings,
                lastObservedDate = currentDate().toString()
            )
        )
        true
    }

    fun adjustCounter(counterId: String, delta: Int): CounterItem? = synchronized(lock) {
        if (delta == 0) return _state.value.counterById(counterId)
        val current = _state.value
        val existing = current.counterById(counterId) ?: return null
        val clock = clockProvider()
        val zone = clock.zone
        val nowMillis = clock.millis()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayKey = today.toString()

        val currentToday = existing.dailyCounts[todayKey] ?: 0
        val currentTotal = existing.dailyCounts.values.sum()

        val newToday = when (existing.mode) {
            CounterMode.DAILY_RESET -> if (currentToday < 0 && delta < 0) currentToday else (currentToday.coerceAtLeast(0) + delta).coerceAtLeast(0)
            CounterMode.CUMULATIVE -> {
                val otherDaysSum = currentTotal - currentToday
                val desiredTotal = (currentTotal + delta).coerceAtLeast(0)
                desiredTotal - otherDaysSum
            }
        }

        val appliedDelta = newToday - currentToday
        if (appliedDelta == 0 && current.lastObservedDate == todayKey) {
            return existing
        }

        val mutableCounts = existing.dailyCounts.toMutableMap()
        if (newToday == 0) {
            mutableCounts.remove(todayKey)
        } else {
            mutableCounts[todayKey] = newToday
        }

        val updatedEvents = if (appliedDelta != 0) {
            val uniqueTs = allocateUniqueEventTimestamp(existing.events, nowMillis, today, zone)
            (existing.events + CounterEvent(timestampEpochMillis = uniqueTs, delta = appliedDelta))
                .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
        } else {
            existing.events
        }

        val updatedItem = existing.copy(
            dailyCounts = mutableCounts,
            events = updatedEvents,
            updatedAtEpochMillis = maxOf(nowMillis, updatedEvents.lastOrNull()?.timestampEpochMillis ?: nowMillis)
        )
        val updatedList = current.counters.map { if (it.id == counterId) updatedItem else it }
        commitLocked(current.copy(counters = updatedList, lastObservedDate = todayKey))
        updatedItem
    }

    fun resetToday(counterId: String): CounterItem? = synchronized(lock) {
        val current = _state.value
        val existing = current.counterById(counterId) ?: return null
        val clock = clockProvider()
        val zone = clock.zone
        val nowMillis = clock.millis()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val todayKey = today.toString()
        val hasTodayEvents = existing.events.any { it.localDate(zone) == today }
        if (!existing.dailyCounts.containsKey(todayKey) && !hasTodayEvents) return existing
        val mutableCounts = existing.dailyCounts.toMutableMap()
        mutableCounts.remove(todayKey)
        val remainingEvents = if (hasTodayEvents) {
            existing.events.filterNot { it.localDate(zone) == today }
        } else {
            existing.events
        }
        val updatedItem = existing.copy(
            dailyCounts = mutableCounts,
            events = remainingEvents,
            updatedAtEpochMillis = nowMillis
        )
        val updatedList = current.counters.map { if (it.id == counterId) updatedItem else it }
        commitLocked(current.copy(counters = updatedList, lastObservedDate = todayKey))
        updatedItem
    }

    fun setDateExcludedFromStats(counterId: String, date: LocalDate, excluded: Boolean): CounterItem? = synchronized(lock) {
        val current = _state.value
        val existing = current.counterById(counterId) ?: return null
        val dateKey = date.toString()
        val alreadyExcluded = dateKey in existing.excludedStatsDates
        if (alreadyExcluded == excluded) return existing
        val updatedExcluded = if (excluded) {
            existing.excludedStatsDates + dateKey
        } else {
            existing.excludedStatsDates - dateKey
        }
        val updatedItem = existing.copy(
            excludedStatsDates = updatedExcluded,
            updatedAtEpochMillis = clockProvider().millis()
        )
        val updatedList = current.counters.map { if (it.id == counterId) updatedItem else it }
        commitLocked(current.copy(counters = updatedList, lastObservedDate = currentDate().toString()))
        updatedItem
    }

    fun bindWidget(appWidgetId: Int, counterId: String) = synchronized(lock) {
        val current = _state.value
        if (current.counterById(counterId) == null) return@synchronized
        val updatedBindings = current.widgetBindings.toMutableMap().apply {
            put(appWidgetId, counterId)
        }
        commitLocked(current.copy(widgetBindings = updatedBindings, lastObservedDate = currentDate().toString()))
    }

    fun unbindWidgets(appWidgetIds: IntArray) = synchronized(lock) {
        if (appWidgetIds.isEmpty()) return@synchronized
        val current = _state.value
        val updatedBindings = current.widgetBindings.toMutableMap()
        var changed = false
        for (id in appWidgetIds) {
            if (updatedBindings.remove(id) != null) {
                changed = true
            }
        }
        if (changed) {
            commitLocked(current.copy(widgetBindings = updatedBindings))
        }
    }

    fun getBoundCounterId(appWidgetId: Int): String? = synchronized(lock) {
        _state.value.widgetBindings[appWidgetId]
    }

    fun getWidgetIdsForCounter(counterId: String): List<Int> = synchronized(lock) {
        _state.value.widgetBindings.filterValues { it == counterId }.keys.toList()
    }

    enum class ImportStrategy {
        /**
         * Preserves all existing local counters and widget bindings. Adds new counters from the
         * backup and non-destructively merges daily history for matching counter IDs without
         * doubling counts on repeated imports.
         */
        MERGE_NON_DESTRUCTIVE,

        /**
         * Replaces local counters with the imported counters while preserving widget bindings
         * whose counter ID still exists in the imported set. Previous state is retained in the
         * atomic storage `.bak` file on disk.
         */
        REPLACE_ALL
    }

    data class ImportApplyResult(
        val strategy: ImportStrategy,
        val addedCount: Int,
        val mergedCount: Int,
        val replacedCount: Int,
        val totalCountersAfter: Int,
        val totalDaysImported: Int,
        val totalEventsImported: Int = 0
    )

    fun importCounters(
        importedCounters: List<CounterItem>,
        strategy: ImportStrategy = ImportStrategy.MERGE_NON_DESTRUCTIVE
    ): ImportApplyResult = synchronized(lock) {
        val current = _state.value
        val clock = clockProvider()
        val zone = clock.zone
        val nowMillis = clock.millis()
        val todayStr = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()

        val sanitizedIncoming = importedCounters.map { raw ->
            val sanitizedEvents = sanitizeEvents(raw.events)
            val validCounts = linkedMapOf<String, Int>()
            for ((dateKey, count) in raw.dailyCounts) {
                if (count != 0 && CounterStatsCalculator.parseLocalDateOrNull(dateKey) != null) {
                    validCounts[dateKey] = count
                }
            }
            if (sanitizedEvents.isNotEmpty()) {
                val eventSumsByDate = sanitizedEvents.groupBy { it.localDate(zone).toString() }
                for ((dateKey, dayEvents) in eventSumsByDate) {
                    if (!validCounts.containsKey(dateKey)) {
                        val sum = dayEvents.sumOf { it.delta }
                        if (sum != 0) {
                            validCounts[dateKey] = sum
                        }
                    }
                }
            }
            val safeId = raw.id.trim().ifEmpty { idGenerator() }
            raw.copy(
                id = safeId,
                name = sanitizeName(raw.name),
                colorIndex = raw.colorIndex.coerceIn(0, MAX_COLOR_INDEX),
                createdAtEpochMillis = if (raw.createdAtEpochMillis > 0L) raw.createdAtEpochMillis else nowMillis,
                updatedAtEpochMillis = if (raw.updatedAtEpochMillis > 0L) raw.updatedAtEpochMillis else nowMillis,
                dailyCounts = validCounts,
                events = sanitizedEvents,
                excludedStatsDates = raw.excludedStatsDates
                    .filter { CounterStatsCalculator.parseLocalDateOrNull(it) != null }
                    .toSet()
            )
        }

        val totalDaysImported = sanitizedIncoming.sumOf { it.dailyCounts.size }
        val totalEventsImported = sanitizedIncoming.sumOf { it.events.size }

        when (strategy) {
            ImportStrategy.MERGE_NON_DESTRUCTIVE -> {
                val existingById = current.counters.associateBy { it.id }.toMutableMap()
                val resultList = current.counters.toMutableList()
                var added = 0
                var merged = 0

                for (incoming in sanitizedIncoming) {
                    val existing = existingById[incoming.id]
                    if (existing == null) {
                        resultList.add(incoming)
                        existingById[incoming.id] = incoming
                        added += 1
                    } else {
                        val updatedItem = mergeCounterNonDestructively(existing, incoming, zone)
                        val idx = resultList.indexOfFirst { it.id == existing.id }
                        if (idx >= 0) {
                            resultList[idx] = updatedItem
                        }
                        existingById[existing.id] = updatedItem
                        merged += 1
                    }
                }

                commitLocked(
                    current.copy(
                        counters = resultList,
                        lastObservedDate = todayStr
                    )
                )
                ImportApplyResult(
                    strategy = strategy,
                    addedCount = added,
                    mergedCount = merged,
                    replacedCount = 0,
                    totalCountersAfter = resultList.size,
                    totalDaysImported = totalDaysImported,
                    totalEventsImported = totalEventsImported
                )
            }

            ImportStrategy.REPLACE_ALL -> {
                val keptIds = sanitizedIncoming.map { it.id }.toSet()
                val survivingBindings = current.widgetBindings.filterValues { it in keptIds }
                commitLocked(
                    current.copy(
                        counters = sanitizedIncoming,
                        widgetBindings = survivingBindings,
                        lastObservedDate = todayStr
                    )
                )
                ImportApplyResult(
                    strategy = strategy,
                    addedCount = 0,
                    mergedCount = 0,
                    replacedCount = sanitizedIncoming.size,
                    totalCountersAfter = sanitizedIncoming.size,
                    totalDaysImported = totalDaysImported,
                    totalEventsImported = totalEventsImported
                )
            }
        }
    }

    private fun mergeCounterNonDestructively(
        existing: CounterItem,
        incoming: CounterItem,
        zone: ZoneId
    ): CounterItem {
        val existingEventsByDate = existing.events.groupBy { it.localDate(zone).toString() }
        val incomingEventsByDate = incoming.events.groupBy { it.localDate(zone).toString() }

        val allDates = linkedSetOf<String>().apply {
            addAll(existing.dailyCounts.keys)
            addAll(incoming.dailyCounts.keys)
            addAll(existingEventsByDate.keys)
            addAll(incomingEventsByDate.keys)
        }.sorted()

        val mergedCounts = linkedMapOf<String, Int>()
        val mergedEvents = mutableListOf<CounterEvent>()

        for (dateKey in allDates) {
            val curVal = existing.dailyCounts[dateKey]
            val incVal = incoming.dailyCounts[dateKey]
            val curDayEvents = existingEventsByDate[dateKey].orEmpty()
            val incDayEvents = incomingEventsByDate[dateKey].orEmpty()

            val conflictingSign = curVal != null && incVal != null &&
                ((curVal < 0 && incVal > 0) || (curVal > 0 && incVal < 0))
            if (conflictingSign) {
                if (curVal != null && curVal != 0) mergedCounts[dateKey] = curVal
                mergedEvents.addAll(curDayEvents)
                continue
            }

            val dayEventsUnion = sanitizeEvents(curDayEvents + incDayEvents)
            val unionTimedSum = dayEventsUnion.sumOf { it.delta }

            val mergedDayCount: Int
            val chosenDayEvents: List<CounterEvent>

            when {
                curVal == null && incVal == null -> {
                    // The date can contain individually rounded events but still have a zero net total.
                    mergedDayCount = 0
                    chosenDayEvents = dayEventsUnion
                }
                curVal == null && incVal != null -> {
                    val curEventSet = curDayEvents.toSet()
                    if (curDayEvents.isNotEmpty() && incDayEvents.isNotEmpty() && incDayEvents.all { it in curEventSet }) {
                        // Local already contains all incoming events (plus e.g. local undo netting to 0)
                        mergedDayCount = 0
                        chosenDayEvents = curDayEvents
                    } else {
                        mergedDayCount = incVal
                        chosenDayEvents = dayEventsUnion
                    }
                }
                curVal != null && incVal == null -> {
                    mergedDayCount = curVal
                    chosenDayEvents = dayEventsUnion
                }
                curVal != null && incVal != null && curVal >= 0 && incVal >= 0 -> {
                    val curEventSet = curDayEvents.toSet()
                    if (curDayEvents.isNotEmpty() && incDayEvents.isNotEmpty() && incDayEvents.all { it in curEventSet }) {
                        // Re-importing older/equal subset of already-recorded local events
                        mergedDayCount = curVal
                        chosenDayEvents = curDayEvents
                    } else {
                        mergedDayCount = maxOf(curVal, incVal, unionTimedSum)
                        chosenDayEvents = dayEventsUnion
                    }
                }
                else -> {
                    // Both negative (e.g. cumulative cross-day decrement): preserve local count non-destructively
                    // while still attaching matching/backfilled timestamps from re-import.
                    mergedDayCount = curVal!!
                    chosenDayEvents = if (curDayEvents.isEmpty() && incVal == curVal) {
                        incDayEvents
                    } else if (curDayEvents.isNotEmpty() && incDayEvents.isNotEmpty()) {
                        dayEventsUnion
                    } else {
                        curDayEvents.ifEmpty { incDayEvents }
                    }
                }
            }

            if (mergedDayCount != 0) {
                mergedCounts[dateKey] = mergedDayCount
            }
            mergedEvents.addAll(chosenDayEvents)
        }

        val finalEvents = sanitizeEvents(mergedEvents)
        val mergedCreated = if (existing.createdAtEpochMillis > 0L) {
            minOf(existing.createdAtEpochMillis, incoming.createdAtEpochMillis)
        } else {
            incoming.createdAtEpochMillis
        }
        val mergedUpdated = maxOf(existing.updatedAtEpochMillis, incoming.updatedAtEpochMillis)
        return existing.copy(
            dailyCounts = mergedCounts,
            events = finalEvents,
            excludedStatsDates = existing.excludedStatsDates + incoming.excludedStatsDates,
            createdAtEpochMillis = mergedCreated,
            updatedAtEpochMillis = mergedUpdated
        )
    }

    private fun allocateUniqueEventTimestamp(
        existingEvents: List<CounterEvent>,
        nowMillis: Long,
        today: LocalDate,
        zone: ZoneId
    ): Long {
        val used = existingEvents.asSequence().map { it.timestampEpochMillis }.toHashSet()
        if (nowMillis !in used) return nowMillis
        var candidate = nowMillis + 1L
        while (candidate in used && Instant.ofEpochMilli(candidate).atZone(zone).toLocalDate() == today) {
            candidate += 1L
        }
        if (candidate !in used && Instant.ofEpochMilli(candidate).atZone(zone).toLocalDate() == today) {
            return candidate
        }
        candidate = nowMillis - 1L
        while (candidate in used && candidate > 0L && Instant.ofEpochMilli(candidate).atZone(zone).toLocalDate() == today) {
            candidate -= 1L
        }
        return candidate
    }

    private fun sanitizeEvents(events: List<CounterEvent>): List<CounterEvent> {
        if (events.isEmpty()) return emptyList()
        return events
            .asSequence()
            .filter { it.delta != 0 && it.timestampEpochMillis > 0L }
            .distinct()
            .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
            .toList()
    }

    private fun nextAutoColorIndex(): Int {
        return _state.value.counters.size % (MAX_COLOR_INDEX + 1)
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.isEmpty()) "Räknare" else trimmed.take(40)
    }

    private fun loadFromStorage(): CounterStoreState {
        val primaryRaw = storage.readRaw()
        if (primaryRaw != null) {
            CounterJsonCodec.decodeOrNull(primaryRaw)?.let { return it }
        }
        val backupRaw = storage.readBackupRaw()
        if (backupRaw != null) {
            CounterJsonCodec.decodeOrNull(backupRaw)?.let { return it }
        }
        return CounterStoreState()
    }

    private fun commitLocked(newState: CounterStoreState) {
        val upgraded = if (newState.schemaVersion < CounterStoreState.CURRENT_SCHEMA_VERSION) {
            newState.copy(schemaVersion = CounterStoreState.CURRENT_SCHEMA_VERSION)
        } else {
            newState
        }
        storage.writeRaw(CounterJsonCodec.encode(upgraded))
        _state.value = upgraded
    }

    companion object {
        const val MAX_COLOR_INDEX = 5
        private const val STORE_FILE_NAME = "counters_store_v1.json"

        @Volatile
        private var instance: CounterRepository? = null

        fun getInstance(context: Context): CounterRepository {
            return instance ?: synchronized(this) {
                instance ?: CounterRepository(
                    storage = AtomicFileCounterStorage(File(context.applicationContext.filesDir, STORE_FILE_NAME))
                ).also { instance = it }
            }
        }
    }
}
