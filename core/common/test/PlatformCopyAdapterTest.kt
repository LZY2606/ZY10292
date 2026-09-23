package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [PlatformCopyAdapter]. The adapter assumes that all ranges were
 * validated by the caller, so these tests only exercise valid ranges.
 */
class PlatformCopyAdapterTest {
    @Test
    fun copyWholeArray() {
        val src = ByteArray(256) { it.toByte() }
        val dst = ByteArray(256)
        PlatformCopyAdapter.copy(src, 0, dst, 0, src.size)
        assertContentEquals(src, dst)
    }

    @Test
    fun copySubrange() {
        val src = ByteArray(100) { it.toByte() }
        val dst = ByteArray(100) { 0x7f }
        PlatformCopyAdapter.copy(src, 10, dst, 42, 33)
        for (i in dst.indices) {
            val expected = if (i in 42 until 75) (i - 42 + 10).toByte() else 0x7f.toByte()
            assertEquals(expected, dst[i], "Mismatch at index $i")
        }
    }

    @Test
    fun copyZeroLength() {
        val src = ByteArray(8) { it.toByte() }
        val dst = ByteArray(8) { -1 }
        PlatformCopyAdapter.copy(src, 0, dst, 0, 0)
        PlatformCopyAdapter.copy(src, 8, dst, 8, 0)
        assertContentEquals(ByteArray(8) { -1 }, dst)
    }

    @Test
    fun copySingleByte() {
        val src = ByteArray(16) { it.toByte() }
        val dst = ByteArray(16)
        PlatformCopyAdapter.copy(src, 15, dst, 0, 1)
        assertEquals(15.toByte(), dst[0])
        for (i in 1 until dst.size) {
            assertEquals(0, dst[i], "Mismatch at index $i")
        }
    }

    @Test
    fun copyOverlappingSelfBackward() {
        // The only overlap direction the core relies on (see Segment.writeTo compaction shift).
        val data = ByteArray(32) { it.toByte() }
        PlatformCopyAdapter.copy(data, 8, data, 0, 24)
        for (i in 0 until 24) {
            assertEquals((i + 8).toByte(), data[i], "Mismatch at index $i")
        }
    }

    @Test
    fun copyOntoItselfExactly() {
        val data = ByteArray(16) { it.toByte() }
        PlatformCopyAdapter.copy(data, 4, data, 4, 8)
        assertContentEquals(ByteArray(16) { it.toByte() }, data)
    }

    @Test
    fun rangeEqualsOnEqualRanges() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { (it - 20).toByte() }
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 10, b, 30, 20))
    }

    @Test
    fun rangeEqualsOnDifferentRanges() {
        val a = ByteArray(64) { it.toByte() }
        val b = ByteArray(64) { it.toByte() }
        b[40] = (b[40] + 1).toByte()
        assertFalse(PlatformCopyAdapter.rangeEquals(a, 0, b, 0, 64))
        // The difference is outside the compared range.
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 0, b, 0, 40))
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 41, b, 41, 23))
    }

    @Test
    fun rangeEqualsZeroLength() {
        val a = ByteArray(4)
        val b = ByteArray(4) { -1 }
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 0, b, 0, 0))
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 4, b, 4, 0))
    }

    @Test
    fun rangeEqualsSameArray() {
        val a = ByteArray(32) { (it % 16).toByte() }
        assertTrue(PlatformCopyAdapter.rangeEquals(a, 0, a, 16, 16))
        assertFalse(PlatformCopyAdapter.rangeEquals(a, 0, a, 15, 16))
    }

}
