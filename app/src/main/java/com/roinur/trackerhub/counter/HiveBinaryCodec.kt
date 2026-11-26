package com.roinur.trackerhub.counter

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Pure-JVM reader and writer for Hive 2.x binary box files (such as `pref02.hive` and
 * `myobject02.hive` inside legacy manifest v3 backup archives).
 *
 * Frame layout:
 * - uint32 LE: total frame length (including this 4-byte length and trailing 4-byte CRC-32)
 * - uint8: key type (0 = uint32 LE key, 1 = uint8 len + ASCII string key)
 * - key bytes
 * - optional value payload (absent means delete/tombstone frame)
 * - uint32 LE: IEEE CRC-32 over bytes [0 .. frameLength - 4)
 */
object HiveBinaryCodec {

    private const val KEY_TYPE_UINT = 0
    private const val KEY_TYPE_ASCII = 1

    private const val TYPE_NULL = 0
    private const val TYPE_INT = 1
    private const val TYPE_DOUBLE = 2
    private const val TYPE_BOOL = 3
    private const val TYPE_STRING = 4
    private const val TYPE_BYTE_LIST = 5
    private const val TYPE_INT_LIST = 6
    private const val TYPE_DOUBLE_LIST = 7
    private const val TYPE_BOOL_LIST = 8
    private const val TYPE_STRING_LIST = 9
    private const val TYPE_LIST = 10
    private const val TYPE_MAP = 11
    private const val TYPE_DATE_TIME = 16
    private const val TYPE_BIG_INT = 17
    private const val TYPE_DATE_TIME_TZ = 18
    private const val TYPE_HIVE_LIST = 19

    private const val CUSTOM_TYPE_OFFSET = 32

    /** Flutter ColorAdapter observed in `myobject02.hive` (typeId 4 -> tag 36): 8-byte float64 ARGB. */
    const val TYPE_ID_COLOR = 4

    /** Generated Hive enum observed in `pref02.hive` / `myobject02.hive` (typeId 7 -> tag 39): 1-byte index. */
    const val TYPE_ID_ENUM_7 = 7

    private const val MAX_FRAME_SIZE = 16 * 1024 * 1024
    private const val MAX_COLLECTION_SIZE = 500_000
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024
    private const val MAX_DEPTH = 32

    class HiveCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

    sealed class HiveKey {
        data class IntKey(val value: Long) : HiveKey()
        data class StringKey(val value: String) : HiveKey()
    }

    sealed class HiveValue {
        data object NullValue : HiveValue()
        data class IntValue(val value: Long) : HiveValue()
        data class DoubleValue(val value: Double) : HiveValue()
        data class BoolValue(val value: Boolean) : HiveValue()
        data class StringValue(val value: String) : HiveValue()
        data class BytesValue(val bytes: ByteArray) : HiveValue() {
            override fun equals(other: Any?): Boolean = other is BytesValue && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
        data class IntListValue(val values: List<Long>) : HiveValue()
        data class DoubleListValue(val values: List<Double>) : HiveValue()
        data class BoolListValue(val values: List<Boolean>) : HiveValue()
        data class StringListValue(val values: List<String>) : HiveValue()
        data class ListValue(val items: List<HiveValue>) : HiveValue()
        data class MapValue(val entries: List<Pair<HiveValue, HiveValue>>) : HiveValue()
        data class DateTimeValue(
            val epochMillis: Long,
            val isUtc: Boolean = false,
            val hasTimezoneFlag: Boolean = true
        ) : HiveValue()
        data class BigIntValue(val decimalString: String) : HiveValue()
        data class HiveRefListValue(val boxName: String, val keys: List<HiveKey>) : HiveValue()
        data class ColorValue(val argb: Long) : HiveValue()
        data class EnumValue(val typeId: Int, val index: Int) : HiveValue()
        data class ObjectValue(val typeId: Int, val fields: LinkedHashMap<Int, HiveValue>) : HiveValue()
    }

    data class DecodeResult(
        val liveEntries: LinkedHashMap<HiveKey, HiveValue>,
        val totalFrames: Int,
        val deletedFrames: Int
    )

    fun decode(data: ByteArray): DecodeResult {
        val live = LinkedHashMap<HiveKey, HiveValue>()
        var pos = 0
        var totalFrames = 0
        var deletedFrames = 0

        while (pos < data.size) {
            if (pos + 8 > data.size) {
                throw HiveCodecException("Truncated Hive frame header at offset $pos")
            }
            val frameLength = readU32LE(data, pos).toInt()
            if (frameLength < 8 || frameLength > MAX_FRAME_SIZE || pos + frameLength > data.size) {
                throw HiveCodecException("Invalid Hive frame length $frameLength at offset $pos")
            }

            val storedCrc = readU32LE(data, pos + frameLength - 4)
            val computedCrc = computeCrc32(data, pos, frameLength - 4)
            if (storedCrc != computedCrc) {
                throw HiveCodecException(
                    "Hive frame CRC-32 mismatch at frame #${totalFrames + 1} (offset $pos)"
                )
            }

            val reader = BinaryReader(data, pos + 4, pos + frameLength - 4)
            val key = reader.readKey()
            totalFrames += 1

            if (reader.remaining == 0) {
                live.remove(key)
                deletedFrames += 1
            } else {
                val value = reader.readValue(depth = 0)
                if (reader.remaining != 0) {
                    throw HiveCodecException(
                        "Hive frame #$totalFrames has ${reader.remaining} unexpected trailing bytes"
                    )
                }
                live[key] = value
            }

            pos += frameLength
        }

        return DecodeResult(
            liveEntries = live,
            totalFrames = totalFrames,
            deletedFrames = deletedFrames
        )
    }

    fun encode(entries: List<Pair<HiveKey, HiveValue?>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val body = ByteArrayOutputStream()
            val writer = BinaryWriter(body)
            writer.writeKey(key)
            if (value != null) {
                writer.writeValue(value, depth = 0)
            }
            val bodyBytes = body.toByteArray()
            val frameLength = 4 + bodyBytes.size + 4
            if (frameLength > MAX_FRAME_SIZE) {
                throw HiveCodecException("Encoded Hive frame exceeds maximum size ($frameLength bytes)")
            }

            val frame = ByteArray(frameLength)
            writeU32LE(frame, 0, frameLength.toLong())
            System.arraycopy(bodyBytes, 0, frame, 4, bodyBytes.size)
            val crc = computeCrc32(frame, 0, frameLength - 4)
            writeU32LE(frame, frameLength - 4, crc)
            out.write(frame)
        }
        return out.toByteArray()
    }

    private fun computeCrc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value and 0xFFFFFFFFL
    }

    private fun readU32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun writeU32LE(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFFL).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
    }

    private class BinaryReader(
        private val data: ByteArray,
        private var pos: Int,
        private val end: Int
    ) {
        val remaining: Int get() = end - pos

        private fun requireBytes(count: Int) {
            if (count < 0 || pos + count > end) {
                throw HiveCodecException("Unexpected end of Hive frame (needed $count bytes, remaining $remaining)")
            }
        }

        private fun readU8(): Int {
            requireBytes(1)
            return data[pos++].toInt() and 0xFF
        }

        private fun readU32(): Int {
            requireBytes(4)
            val v = readU32LE(data, pos)
            pos += 4
            if (v > Int.MAX_VALUE) {
                throw HiveCodecException("Unsigned 32-bit length overflow: $v")
            }
            return v.toInt()
        }

        private fun readU32Long(): Long {
            requireBytes(4)
            val v = readU32LE(data, pos)
            pos += 4
            return v
        }

        private fun readFloat64(): Double {
            requireBytes(8)
            val bits = (data[pos].toLong() and 0xFFL) or
                ((data[pos + 1].toLong() and 0xFFL) shl 8) or
                ((data[pos + 2].toLong() and 0xFFL) shl 16) or
                ((data[pos + 3].toLong() and 0xFFL) shl 24) or
                ((data[pos + 4].toLong() and 0xFFL) shl 32) or
                ((data[pos + 5].toLong() and 0xFFL) shl 40) or
                ((data[pos + 6].toLong() and 0xFFL) shl 48) or
                ((data[pos + 7].toLong() and 0xFFL) shl 56)
            pos += 8
            return Double.fromBits(bits)
        }

        private fun readBytes(count: Int): ByteArray {
            if (count < 0 || count > MAX_STRING_BYTES) {
                throw HiveCodecException("Invalid byte slice length: $count")
            }
            requireBytes(count)
            val out = data.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        fun readKey(): HiveKey {
            return when (val keyType = readU8()) {
                KEY_TYPE_UINT -> HiveKey.IntKey(readU32Long())
                KEY_TYPE_ASCII -> {
                    val len = readU8()
                    val bytes = readBytes(len)
                    HiveKey.StringKey(String(bytes, Charsets.US_ASCII))
                }
                else -> throw HiveCodecException("Unsupported Hive key type: $keyType")
            }
        }

        fun readValue(depth: Int): HiveValue {
            if (depth > MAX_DEPTH) {
                throw HiveCodecException("Hive nesting depth limit exceeded")
            }
            return when (val tag = readU8()) {
                TYPE_NULL -> HiveValue.NullValue
                TYPE_INT -> HiveValue.IntValue(readFloat64().toLong())
                TYPE_DOUBLE -> HiveValue.DoubleValue(readFloat64())
                TYPE_BOOL -> HiveValue.BoolValue(readU8() != 0)
                TYPE_STRING -> {
                    val len = readU32()
                    HiveValue.StringValue(String(readBytes(len), Charsets.UTF_8))
                }
                TYPE_BYTE_LIST -> {
                    val len = readU32()
                    HiveValue.BytesValue(readBytes(len))
                }
                TYPE_INT_LIST -> {
                    val len = readCollectionLength()
                    val list = ArrayList<Long>(len)
                    repeat(len) { list.add(readFloat64().toLong()) }
                    HiveValue.IntListValue(list)
                }
                TYPE_DOUBLE_LIST -> {
                    val len = readCollectionLength()
                    val list = ArrayList<Double>(len)
                    repeat(len) { list.add(readFloat64()) }
                    HiveValue.DoubleListValue(list)
                }
                TYPE_BOOL_LIST -> {
                    val len = readCollectionLength()
                    val list = ArrayList<Boolean>(len)
                    repeat(len) { list.add(readU8() != 0) }
                    HiveValue.BoolListValue(list)
                }
                TYPE_STRING_LIST -> {
                    val len = readCollectionLength()
                    val list = ArrayList<String>(len)
                    repeat(len) {
                        val sLen = readU32()
                        list.add(String(readBytes(sLen), Charsets.UTF_8))
                    }
                    HiveValue.StringListValue(list)
                }
                TYPE_LIST -> {
                    val len = readCollectionLength()
                    val list = ArrayList<HiveValue>(len)
                    repeat(len) { list.add(readValue(depth + 1)) }
                    HiveValue.ListValue(list)
                }
                TYPE_MAP -> {
                    val len = readCollectionLength()
                    val pairs = ArrayList<Pair<HiveValue, HiveValue>>(len)
                    repeat(len) {
                        val k = readValue(depth + 1)
                        val v = readValue(depth + 1)
                        pairs.add(k to v)
                    }
                    HiveValue.MapValue(pairs)
                }
                TYPE_DATE_TIME -> {
                    val ms = readFloat64().toLong()
                    HiveValue.DateTimeValue(epochMillis = ms, isUtc = false, hasTimezoneFlag = false)
                }
                TYPE_BIG_INT -> {
                    val len = readU8()
                    HiveValue.BigIntValue(String(readBytes(len), Charsets.UTF_8))
                }
                TYPE_DATE_TIME_TZ -> {
                    val ms = readFloat64().toLong()
                    val isUtc = readU8() != 0
                    HiveValue.DateTimeValue(epochMillis = ms, isUtc = isUtc, hasTimezoneFlag = true)
                }
                TYPE_HIVE_LIST -> {
                    val len = readCollectionLength()
                    val boxNameLen = readU8()
                    val boxName = String(readBytes(boxNameLen), Charsets.US_ASCII)
                    val keys = ArrayList<HiveKey>(len)
                    repeat(len) { keys.add(readKey()) }
                    HiveValue.HiveRefListValue(boxName = boxName, keys = keys)
                }
                else -> {
                    if (tag < CUSTOM_TYPE_OFFSET) {
                        throw HiveCodecException("Unsupported Hive value type tag: $tag")
                    }
                    val typeId = tag - CUSTOM_TYPE_OFFSET
                    when (typeId) {
                        TYPE_ID_COLOR -> HiveValue.ColorValue(readFloat64().toLong() and 0xFFFFFFFFL)
                        TYPE_ID_ENUM_7 -> HiveValue.EnumValue(typeId = typeId, index = readU8())
                        else -> {
                            val numFields = readU8()
                            val fields = LinkedHashMap<Int, HiveValue>(numFields)
                            repeat(numFields) {
                                val fieldId = readU8()
                                fields[fieldId] = readValue(depth + 1)
                            }
                            HiveValue.ObjectValue(typeId = typeId, fields = fields)
                        }
                    }
                }
            }
        }

        private fun readCollectionLength(): Int {
            val len = readU32()
            if (len < 0 || len > MAX_COLLECTION_SIZE) {
                throw HiveCodecException("Hive collection length out of bounds: $len")
            }
            return len
        }
    }

    private class BinaryWriter(private val out: ByteArrayOutputStream) {
        private val buf8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        private fun writeU8(v: Int) {
            out.write(v and 0xFF)
        }

        private fun writeU32(v: Long) {
            out.write((v and 0xFFL).toInt())
            out.write(((v ushr 8) and 0xFFL).toInt())
            out.write(((v ushr 16) and 0xFFL).toInt())
            out.write(((v ushr 24) and 0xFFL).toInt())
        }

        private fun writeFloat64(v: Double) {
            buf8.clear()
            buf8.putDouble(v)
            out.write(buf8.array(), 0, 8)
        }

        fun writeKey(key: HiveKey) {
            when (key) {
                is HiveKey.IntKey -> {
                    if (key.value < 0 || key.value > 0xFFFFFFFFL) {
                        throw HiveCodecException("Hive integer key out of uint32 range: ${key.value}")
                    }
                    writeU8(KEY_TYPE_UINT)
                    writeU32(key.value)
                }
                is HiveKey.StringKey -> {
                    val bytes = key.value.toByteArray(Charsets.US_ASCII)
                    if (bytes.size > 255) {
                        throw HiveCodecException("Hive ASCII key too long: ${bytes.size}")
                    }
                    writeU8(KEY_TYPE_ASCII)
                    writeU8(bytes.size)
                    out.write(bytes)
                }
            }
        }

        fun writeValue(value: HiveValue, depth: Int) {
            if (depth > MAX_DEPTH) {
                throw HiveCodecException("Hive nesting depth limit exceeded while writing")
            }
            when (value) {
                HiveValue.NullValue -> writeU8(TYPE_NULL)
                is HiveValue.IntValue -> {
                    writeU8(TYPE_INT)
                    writeFloat64(value.value.toDouble())
                }
                is HiveValue.DoubleValue -> {
                    writeU8(TYPE_DOUBLE)
                    writeFloat64(value.value)
                }
                is HiveValue.BoolValue -> {
                    writeU8(TYPE_BOOL)
                    writeU8(if (value.value) 1 else 0)
                }
                is HiveValue.StringValue -> {
                    val bytes = value.value.toByteArray(Charsets.UTF_8)
                    writeU8(TYPE_STRING)
                    writeU32(bytes.size.toLong())
                    out.write(bytes)
                }
                is HiveValue.BytesValue -> {
                    writeU8(TYPE_BYTE_LIST)
                    writeU32(value.bytes.size.toLong())
                    out.write(value.bytes)
                }
                is HiveValue.IntListValue -> {
                    writeU8(TYPE_INT_LIST)
                    writeU32(value.values.size.toLong())
                    for (item in value.values) writeFloat64(item.toDouble())
                }
                is HiveValue.DoubleListValue -> {
                    writeU8(TYPE_DOUBLE_LIST)
                    writeU32(value.values.size.toLong())
                    for (item in value.values) writeFloat64(item)
                }
                is HiveValue.BoolListValue -> {
                    writeU8(TYPE_BOOL_LIST)
                    writeU32(value.values.size.toLong())
                    for (item in value.values) writeU8(if (item) 1 else 0)
                }
                is HiveValue.StringListValue -> {
                    writeU8(TYPE_STRING_LIST)
                    writeU32(value.values.size.toLong())
                    for (item in value.values) {
                        val bytes = item.toByteArray(Charsets.UTF_8)
                        writeU32(bytes.size.toLong())
                        out.write(bytes)
                    }
                }
                is HiveValue.ListValue -> {
                    writeU8(TYPE_LIST)
                    writeU32(value.items.size.toLong())
                    for (item in value.items) writeValue(item, depth + 1)
                }
                is HiveValue.MapValue -> {
                    writeU8(TYPE_MAP)
                    writeU32(value.entries.size.toLong())
                    for ((k, v) in value.entries) {
                        writeValue(k, depth + 1)
                        writeValue(v, depth + 1)
                    }
                }
                is HiveValue.DateTimeValue -> {
                    if (value.hasTimezoneFlag) {
                        writeU8(TYPE_DATE_TIME_TZ)
                        writeFloat64(value.epochMillis.toDouble())
                        writeU8(if (value.isUtc) 1 else 0)
                    } else {
                        writeU8(TYPE_DATE_TIME)
                        writeFloat64(value.epochMillis.toDouble())
                    }
                }
                is HiveValue.BigIntValue -> {
                    val bytes = value.decimalString.toByteArray(Charsets.UTF_8)
                    if (bytes.size > 255) {
                        throw HiveCodecException("BigInt string too long: ${bytes.size}")
                    }
                    writeU8(TYPE_BIG_INT)
                    writeU8(bytes.size)
                    out.write(bytes)
                }
                is HiveValue.HiveRefListValue -> {
                    val boxBytes = value.boxName.toByteArray(Charsets.US_ASCII)
                    if (boxBytes.size > 255) {
                        throw HiveCodecException("HiveList boxName too long")
                    }
                    writeU8(TYPE_HIVE_LIST)
                    writeU32(value.keys.size.toLong())
                    writeU8(boxBytes.size)
                    out.write(boxBytes)
                    for (k in value.keys) writeKey(k)
                }
                is HiveValue.ColorValue -> {
                    writeU8(CUSTOM_TYPE_OFFSET + TYPE_ID_COLOR)
                    writeFloat64((value.argb and 0xFFFFFFFFL).toDouble())
                }
                is HiveValue.EnumValue -> {
                    writeU8(CUSTOM_TYPE_OFFSET + value.typeId)
                    writeU8(value.index)
                }
                is HiveValue.ObjectValue -> {
                    if (value.fields.size > 255) {
                        throw HiveCodecException("Hive object has too many fields: ${value.fields.size}")
                    }
                    writeU8(CUSTOM_TYPE_OFFSET + value.typeId)
                    writeU8(value.fields.size)
                    for ((fieldId, fieldValue) in value.fields) {
                        writeU8(fieldId)
                        writeValue(fieldValue, depth + 1)
                    }
                }
            }
        }
    }
}
