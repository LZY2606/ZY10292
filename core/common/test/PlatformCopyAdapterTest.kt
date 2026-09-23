/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [PlatformCopyAdapter]: every platform implementation must
 * behave exactly the same over previously validated ranges.
 */
class PlatformCopyAdapterTest {

    @Test
    fun copyTransfersBytes() {
        val src = ByteArray(16) { it.toByte() }
        val dst = ByteArray(16)
        PlatformCopyAdapter.copy(src, 2, dst, 5, 10)
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0),
            dst
        )
    }

    @Test
    fun copyOfZeroLengthIsNoOp() {
        val src = ByteArray(4) { it.toByte() }
        val dst = ByteArray(4)
        PlatformCopyAdapter.copy(src, 0, dst, 0, 0)
        assertContentEquals(ByteArray(4), dst)
        // Zero-length copies at the very end of the arrays are also valid.
        PlatformCopyAdapter.copy(src, 4, dst, 4, 0)
        assertContentEquals(ByteArray(4), dst)
    }

    @Test
    fun copyHandlesOverlappingRegions() {
        // Both forward (dstPos > srcPos) and backward overlaps must behave
        // as if the source region was snapshotted into a temporary buffer.
        val forward = ByteArray(16) { it.toByte() }
        PlatformCopyAdapter.copy(forward, 0, forward, 4, 8)
        assertContentEquals(
            byteArrayOf(0, 1, 2, 3, 0, 1, 2, 3, 4, 5, 6, 7, 12, 13, 14, 15),
            forward
        )

        val backward = ByteArray(16) { it.toByte() }
        PlatformCopyAdapter.copy(backward, 4, backward, 0, 8)
        assertContentEquals(
            byteArrayOf(4, 5, 6, 7, 8, 9, 10, 11, 8, 9, 10, 11, 12, 13, 14, 15),
            backward
        )
    }

    @Test
    fun compareReportsEquality() {
        val a = ByteArray(16) { it.toByte() }
        val b = ByteArray(16) { (it + 4).toByte() }
        assertTrue(PlatformCopyAdapter.compare(a, 4, b, 0, 12))
        assertTrue(PlatformCopyAdapter.compare(a, 0, a, 0, 16))
        assertFalse(PlatformCopyAdapter.compare(a, 0, b, 0, 16))
    }

    @Test
    fun compareOfZeroLengthIsAlwaysTrue() {
        val a = ByteArray(4)
        val b = ByteArray(4) { 1 }
        assertTrue(PlatformCopyAdapter.compare(a, 0, b, 0, 0))
        assertTrue(PlatformCopyAdapter.compare(a, 4, b, 4, 0))
    }

    @Test
    fun compareDetectsSingleByteDifference() {
        val a = ByteArray(32) { it.toByte() }
        val b = ByteArray(32) { it.toByte() }
        b[17] = -1
        assertFalse(PlatformCopyAdapter.compare(a, 0, b, 0, 32))
        assertTrue(PlatformCopyAdapter.compare(a, 0, b, 0, 17))
        assertTrue(PlatformCopyAdapter.compare(a, 18, b, 18, 14))
    }

    @Test
    fun checkedCopyValidatesRanges() {
        val src = ByteArray(8)
        val dst = ByteArray(8)
        assertFailsWith<IllegalArgumentException> { checkedArrayCopy(src, 0, dst, 0, -1) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCopy(src, -1, dst, 0, 1) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCopy(src, 0, dst, 0, 9) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCopy(src, 0, dst, 7, 2) }
        // Offsets near 2 GiB must be rejected by checked arithmetic, not silently overflow.
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCopy(src, Int.MAX_VALUE, dst, 0, 1) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCopy(src, 0, dst, Int.MAX_VALUE, 1) }
    }

    @Test
    fun checkedCompareValidatesRanges() {
        val a = ByteArray(8)
        val b = ByteArray(8)
        assertFailsWith<IllegalArgumentException> { checkedArrayCompare(a, 0, b, 0, -1) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCompare(a, 0, b, 0, 9) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCompare(a, Int.MAX_VALUE, b, 0, 1) }
        assertFailsWith<IndexOutOfBoundsException> { checkedArrayCompare(a, 0, b, Int.MAX_VALUE, 1) }
    }

    @Test
    fun checkedCopyWithinBoundsSucceeds() {
        val src = ByteArray(8) { (it + 1).toByte() }
        val dst = ByteArray(8)
        checkedArrayCopy(src, 0, dst, 0, 8)
        assertContentEquals(src, dst)
    }
}
