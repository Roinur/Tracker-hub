package com.example.trackerhub.counter

/**
 * Dependency-free JSON codec for [CounterStoreState] so persistence works identically
 * on Android and local JVM unit tests without mocking org.json.
 */
object CounterJsonCodec {

    fun encode(state: CounterStoreState): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"schemaVersion\":").append(state.schemaVersion)
        if (state.lastObservedDate != null) {
            sb.append(",\"lastObservedDate\":")
            appendJsonString(sb, state.lastObservedDate)
        }
        sb.append(",\"counters\":[")
        state.counters.forEachIndexed { index, counter ->
            if (index > 0) sb.append(',')
            encodeCounter(sb, counter)
        }
        sb.append(']')
        sb.append(",\"widgetBindings\":{")
        val sortedBindings = state.widgetBindings.entries.sortedBy { it.key }
        sortedBindings.forEachIndexed { index, (widgetId, counterId) ->
            if (index > 0) sb.append(',')
            appendJsonString(sb, widgetId.toString())
            sb.append(':')
            appendJsonString(sb, counterId)
        }
        sb.append("}}")
        return sb.toString()
    }

    fun decode(json: String): CounterStoreState = decodeOrNull(json) ?: CounterStoreState()

    fun decodeOrNull(json: String): CounterStoreState? {
        if (json.isBlank()) return null
        val parsed = JsonParser(json).parseRootObject() ?: return null
        val schemaVersion = (parsed["schemaVersion"] as? Number)?.toInt()
            ?: CounterStoreState.CURRENT_SCHEMA_VERSION
        val lastObservedDate = parsed["lastObservedDate"] as? String

        val countersRaw = parsed["counters"] as? List<*> ?: emptyList<Any?>()
        val counters = countersRaw.mapNotNull { entry ->
            decodeCounter(entry as? Map<*, *>)
        }

        val bindingsRaw = parsed["widgetBindings"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val bindings = linkedMapOf<Int, String>()
        for ((k, v) in bindingsRaw) {
            val widgetId = (k as? String)?.toIntOrNull() ?: continue
            val counterId = v as? String ?: continue
            if (counterId.isNotBlank()) {
                bindings[widgetId] = counterId
            }
        }

        return CounterStoreState(
            schemaVersion = schemaVersion,
            counters = counters,
            widgetBindings = bindings,
            lastObservedDate = lastObservedDate
        )
    }

    internal fun encodeCounter(sb: StringBuilder, counter: CounterItem) {
        sb.append('{')
        sb.append("\"id\":")
        appendJsonString(sb, counter.id)
        sb.append(",\"name\":")
        appendJsonString(sb, counter.name)
        sb.append(",\"mode\":")
        appendJsonString(sb, counter.mode.storageKey)
        sb.append(",\"colorIndex\":").append(counter.colorIndex)
        sb.append(",\"createdAtEpochMillis\":").append(counter.createdAtEpochMillis)
        sb.append(",\"updatedAtEpochMillis\":").append(counter.updatedAtEpochMillis)
        sb.append(",\"dailyCounts\":{")
        val sortedDays = counter.dailyCounts.entries.filter { it.value != 0 }.sortedBy { it.key }
        sortedDays.forEachIndexed { index, (dateKey, value) ->
            if (index > 0) sb.append(',')
            appendJsonString(sb, dateKey)
            sb.append(':').append(value)
        }
        sb.append('}')
        val validEvents = counter.events
            .filter { it.delta != 0 && it.timestampEpochMillis > 0L }
            .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
        if (validEvents.isNotEmpty()) {
            sb.append(",\"events\":[")
            validEvents.forEachIndexed { index, event ->
                if (index > 0) sb.append(',')
                sb.append("{\"t\":").append(event.timestampEpochMillis)
                sb.append(",\"d\":").append(event.delta).append('}')
            }
            sb.append(']')
        }
        val validExcluded = counter.excludedStatsDates
            .filter { CounterStatsCalculator.parseLocalDateOrNull(it) != null }
            .sorted()
        if (validExcluded.isNotEmpty()) {
            sb.append(",\"excludedStatsDates\":[")
            validExcluded.forEachIndexed { index, dateKey ->
                if (index > 0) sb.append(',')
                appendJsonString(sb, dateKey)
            }
            sb.append(']')
        }
        val bgFile = counter.widgetBackgroundFile?.trim()?.takeIf { it.isNotEmpty() }
        if (bgFile != null) {
            sb.append(",\"widgetBackgroundFile\":")
            appendJsonString(sb, bgFile)
        }
        sb.append('}')
    }

    internal fun decodeCounter(map: Map<*, *>?): CounterItem? {
        if (map == null) return null
        val id = (map["id"] as? String)?.trim().orEmpty()
        val name = (map["name"] as? String)?.trim().orEmpty()
        if (id.isEmpty() || name.isEmpty()) return null
        val mode = CounterMode.fromStorageKey(map["mode"] as? String)
        val colorIndex = (map["colorIndex"] as? Number)?.toInt() ?: 0
        val createdAt = (map["createdAtEpochMillis"] as? Number)?.toLong() ?: 0L
        val updatedAt = (map["updatedAtEpochMillis"] as? Number)?.toLong() ?: createdAt

        val countsRaw = map["dailyCounts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val dailyCounts = linkedMapOf<String, Int>()
        for ((k, v) in countsRaw) {
            val dateKey = k as? String ?: continue
            val count = (v as? Number)?.toInt() ?: continue
            if (count != 0 && CounterStatsCalculator.parseLocalDateOrNull(dateKey) != null) {
                dailyCounts[dateKey] = count
            }
        }

        val eventsRaw = map["events"] as? List<*>
        val events = if (eventsRaw == null) {
            emptyList()
        } else {
            eventsRaw.mapNotNull { entry -> decodeEvent(entry) }
                .sortedWith(compareBy<CounterEvent>({ it.timestampEpochMillis }, { it.delta }))
        }

        val excludedRaw = map["excludedStatsDates"] as? List<*>
        val excludedStatsDates = if (excludedRaw == null) {
            emptySet()
        } else {
            excludedRaw.mapNotNull { entry ->
                val dateKey = entry as? String ?: return@mapNotNull null
                if (CounterStatsCalculator.parseLocalDateOrNull(dateKey) != null) dateKey else null
            }.toSet()
        }

        val widgetBackgroundFile = (map["widgetBackgroundFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return CounterItem(
            id = id,
            name = name,
            mode = mode,
            colorIndex = colorIndex,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = updatedAt,
            dailyCounts = dailyCounts,
            events = events,
            excludedStatsDates = excludedStatsDates,
            widgetBackgroundFile = widgetBackgroundFile
        )
    }

    private fun decodeEvent(raw: Any?): CounterEvent? {
        return when (raw) {
            is Map<*, *> -> {
                val ts = ((raw["t"] ?: raw["timestampEpochMillis"]) as? Number)?.toLong() ?: return null
                val delta = ((raw["d"] ?: raw["delta"]) as? Number)?.toInt() ?: 1
                if (ts <= 0L || delta == 0) null else CounterEvent(timestampEpochMillis = ts, delta = delta)
            }
            is List<*> -> {
                val ts = (raw.getOrNull(0) as? Number)?.toLong() ?: return null
                val delta = (raw.getOrNull(1) as? Number)?.toInt() ?: 1
                if (ts <= 0L || delta == 0) null else CounterEvent(timestampEpochMillis = ts, delta = delta)
            }
            else -> null
        }
    }

    internal fun appendJsonString(sb: StringBuilder, value: String) {
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
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class JsonParser(private val input: String) {
        private var pos = 0

        fun parseRootObject(): Map<String, Any?>? {
            return try {
                skipWhitespace()
                if (pos >= input.length || input[pos] != '{') return null
                val obj = parseObject()
                skipWhitespace()
                obj
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of JSON")
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
            skipWhitespace()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
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
                                if (pos + 4 > input.length) throw IllegalArgumentException("Invalid unicode escape")
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
            var isFloating = false
            if (peek('.')) {
                isFloating = true
                pos++
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            if (peek('e') || peek('E')) {
                isFloating = true
                pos++
                if (peek('+') || peek('-')) pos++
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            val token = input.substring(start, pos)
            return if (isFloating) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number $token")
            } else {
                token.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer $token")
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!input.startsWith(literal, pos)) {
                throw IllegalArgumentException("Expected $literal at $pos")
            }
            pos += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos] in " \t\r\n") {
                pos++
            }
        }

        private fun peek(expected: Char): Boolean {
            return pos < input.length && input[pos] == expected
        }

        private fun expect(expected: Char) {
            if (!peek(expected)) {
                throw IllegalArgumentException("Expected '$expected' at $pos")
            }
            pos++
        }
    }
}
