/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UtilsTest {
    @Test
    fun hexNumberLength() {
        val num2length: Map<Long, Int> = mapOf(
            0x1L to 1,
            0x10L to 2,
            0x100L to 3,
            0x1000L to 4,
            0x10000L to 5,
            0x100000L to 6,
            0x1000000L to 7,
            0x10000000L to 8,
            0x100000000L to 9,
            0x1000000000L to 10,
            0x10000000000L to 11,
            0x100000000000L to 12,
            0x1000000000000L to 13,
            0x10000000000000L to 14,
            0x100000000000000L to 15,
            0x1000000000000000L to 16,
            -1L to 16,
            0x3fL to 2,
            0x7fL to 2,
            0xffL to 2,
            0L to 1
        )

        num2length.forEach { (num, length) ->
            assertEquals(length, hexNumberLength(num), "Wrong length for 0x${num.toString(16)}")
        }
    }

    @Test
    fun checkByteCountBoundaries() {
        checkByteCount(0L)
        checkByteCount(Long.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { checkByteCount(-1L) }
        assertFailsWith<IllegalArgumentException> { checkByteCount(Long.MIN_VALUE) }
    }

    @Test
    fun checkBoundsEmptyRanges() {
        // Empty (ZST-like) ranges are always valid within bounds.
        checkBounds(0L, 0L, 0L)
        checkBounds(10L, 0L, 0L)
        checkBounds(10L, 10L, 10L)
        assertFailsWith<IndexOutOfBoundsException> { checkBounds(0L, 0L, 1L) }
        assertFailsWith<IndexOutOfBoundsException> { checkBounds(0L, -1L, 0L) }
        assertFailsWith<IllegalArgumentException> { checkBounds(10L, 5L, 4L) }
    }

    @Test
    fun checkBoundsAround2GB() {
        // Sizes around the 2GB mark must be handled using Long arithmetic.
        val size = Int.MAX_VALUE.toLong() + 1L // 2GB
        checkBounds(size, 0L, size)
        checkBounds(size, size, size)
        checkBounds(size, Int.MAX_VALUE.toLong(), size)
        assertFailsWith<IndexOutOfBoundsException> { checkBounds(size, 0L, size + 1L) }
        assertFailsWith<IndexOutOfBoundsException> { checkBounds(size, -1L, size) }
    }

    @Test
    fun checkOffsetAndCountBoundaries() {
        checkOffsetAndCount(0L, 0L, 0L)
        checkOffsetAndCount(10L, 10L, 0L)
        // 2GB+ sizes and offsets must not overflow into acceptance or rejection of valid ranges.
        val size = Int.MAX_VALUE.toLong() * 3L
        checkOffsetAndCount(size, 0L, size)
        checkOffsetAndCount(size, size, 0L)
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(size, 0L, size + 1L) }
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(size, size, 1L) }
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(size, -1L, 0L) }
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(size, 0L, -1L) }
        // offset + byteCount may overflow a Long; the check must still reject it.
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(Long.MAX_VALUE, Long.MAX_VALUE, 1L) }
        assertFailsWith<IllegalArgumentException> { checkOffsetAndCount(Long.MAX_VALUE, Long.MAX_VALUE - 1L, 2L) }
    }
}
