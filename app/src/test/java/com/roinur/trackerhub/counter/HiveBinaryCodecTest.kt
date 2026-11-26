package com.roinur.trackerhub.counter

import com.roinur.trackerhub.counter.HiveBinaryCodec.HiveCodecException
import com.roinur.trackerhub.counter.HiveBinaryCodec.HiveKey
import com.roinur.trackerhub.counter.HiveBinaryCodec.HiveValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HiveBinaryCodecTest {

    @Test
    fun roundTripSupportsAllPrimitiveCompositeDateTimeColorEnumAndObjectTypes() {
        val nestedObj = HiveValue.ObjectValue(
            typeId = 3,
            fields = linkedMapOf(
                0 to HiveValue.IntValue(108L),
                1 to HiveValue.StringValue("kDarkBlue"),
                10 to HiveValue.ColorValue(0xFF7192CEL)
            )
        )
        val rootObj = HiveValue.ObjectValue(
            typeId = 2,
            fields = linkedMapOf(
                0 to HiveValue.IntValue(42L),
                1 to HiveValue.StringValue("Räknare ÅÄÖ"),
                2 to HiveValue.DoubleValue(3.5),
                3 to HiveValue.BoolValue(true),
                4 to HiveValue.NullValue,
                5 to HiveValue.BytesValue(byteArrayOf(1, 2, 3, 4)),
                6 to HiveValue.IntListValue(listOf(10L, 20L, -5L)),
                7 to HiveValue.DoubleListValue(listOf(1.25, 2.75)),
                8 to HiveValue.BoolListValue(listOf(true, false, true)),
                9 to HiveValue.StringListValue(listOf("ett", "två")),
                10 to HiveValue.ListValue(listOf(HiveValue.IntValue(7L), HiveValue.StringValue("hej"))),
                11 to HiveValue.EnumValue(typeId = HiveBinaryCodec.TYPE_ID_ENUM_7, index = 1),
                12 to nestedObj,
                92 to HiveValue.MapValue(
                    listOf(
                        HiveValue.DateTimeValue(1_764_154_800_000L, isUtc = false, hasTimezoneFlag = true) to
                            HiveValue.DoubleValue(4.0),
                        HiveValue.DateTimeValue(1_764_241_200_000L, isUtc = true, hasTimezoneFlag = true) to
                            HiveValue.DoubleValue(2.0)
                    )
                )
            )
        )

        val encoded = HiveBinaryCodec.encode(
            listOf(
                HiveKey.IntKey(1L) to rootObj,
                HiveKey.StringKey("meta") to HiveValue.DateTimeValue(1_700_000_000_000L, isUtc = false, hasTimezoneFlag = false)
            )
        )

        val decoded = HiveBinaryCodec.decode(encoded)
        assertEquals(2, decoded.totalFrames)
        assertEquals(0, decoded.deletedFrames)
        assertEquals(2, decoded.liveEntries.size)
        assertEquals(rootObj, decoded.liveEntries[HiveKey.IntKey(1L)])
        assertEquals(
            HiveValue.DateTimeValue(1_700_000_000_000L, isUtc = false, hasTimezoneFlag = false),
            decoded.liveEntries[HiveKey.StringKey("meta")]
        )
    }

    @Test
    fun appendOnlyReplayAppliesLatestWriteAndHandlesDeleteTombstones() {
        val encoded = HiveBinaryCodec.encode(
            listOf(
                HiveKey.IntKey(1L) to HiveValue.StringValue("v1"),
                HiveKey.IntKey(2L) to HiveValue.StringValue("keep"),
                HiveKey.IntKey(1L) to HiveValue.StringValue("v2"),
                HiveKey.IntKey(3L) to HiveValue.StringValue("temp"),
                HiveKey.IntKey(3L) to null // tombstone delete
            )
        )

        val decoded = HiveBinaryCodec.decode(encoded)
        assertEquals(5, decoded.totalFrames)
        assertEquals(1, decoded.deletedFrames)
        assertEquals(2, decoded.liveEntries.size)
        assertEquals(HiveValue.StringValue("v2"), decoded.liveEntries[HiveKey.IntKey(1L)])
        assertEquals(HiveValue.StringValue("keep"), decoded.liveEntries[HiveKey.IntKey(2L)])
        assertFalse(decoded.liveEntries.containsKey(HiveKey.IntKey(3L)))
    }

    @Test
    fun corruptedCrcIsRejected() {
        val encoded = HiveBinaryCodec.encode(
            listOf(HiveKey.IntKey(0L) to HiveValue.StringValue("valid"))
        )
        // Flip one payload byte
        encoded[encoded.size - 6] = (encoded[encoded.size - 6].toInt() xor 0xFF).toByte()

        try {
            HiveBinaryCodec.decode(encoded)
            fail("Expected HiveCodecException on CRC mismatch")
        } catch (e: HiveCodecException) {
            assertTrue(e.message.orEmpty().contains("CRC"))
        }
    }

    @Test
    fun truncatedFrameIsRejected() {
        val encoded = HiveBinaryCodec.encode(
            listOf(HiveKey.IntKey(0L) to HiveValue.StringValue("hello"))
        )
        val truncated = encoded.copyOf(encoded.size - 3)

        try {
            HiveBinaryCodec.decode(truncated)
            fail("Expected HiveCodecException on truncated frame")
        } catch (_: HiveCodecException) {
            // expected
        }
    }
}
