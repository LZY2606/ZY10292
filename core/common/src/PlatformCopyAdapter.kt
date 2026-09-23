/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

/**
 * Platform adapter for bulk byte-array operations.
 *
 * The adapter only executes copy/compare over ranges that were already validated
 * by the common segment-ownership core (see [checkedArrayCopy] and [checkedArrayCompare]).
 * It does not decide anything about pooling, sharing or segment ownership;
 * those decisions belong to the core.
 *
 * Implementations must handle overlapping regions when `src === dst` the same way
 * [ByteArray.copyInto] does: the copy behaves as if the source region was first
 * snapshotted into a temporary buffer.
 */
internal expect object PlatformCopyAdapter {
    /**
     * Copies [length] bytes from [src] starting at [srcPos] into [dst] starting at [dstPos].
     * All ranges are assumed to be validated by the caller.
     */
    fun copy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int)

    /**
     * Compares [length] bytes of [left] starting at [leftPos] with [length] bytes
     * of [right] starting at [rightPos]. All ranges are assumed to be validated by the caller.
     *
     * @return `true` if both regions contain the same byte sequence.
     */
    fun compare(left: ByteArray, leftPos: Int, right: ByteArray, rightPos: Int, length: Int): Boolean
}

/**
 * Validates the source and destination ranges and then delegates to [PlatformCopyAdapter.copy].
 *
 * All arithmetic is performed using [Long] so that offsets and lengths near or above
 * 2 GiB cannot overflow before being checked.
 *
 * @throws IllegalArgumentException when [length] is negative.
 * @throws IndexOutOfBoundsException when a range falls outside of its array.
 */
internal fun checkedArrayCopy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int) {
    require(length >= 0) { "length ($length) < 0" }
    checkBounds(src.size.toLong(), srcPos.toLong(), srcPos.toLong() + length)
    checkBounds(dst.size.toLong(), dstPos.toLong(), dstPos.toLong() + length)
    PlatformCopyAdapter.copy(src, srcPos, dst, dstPos, length)
}

/**
 * Validates both ranges and then delegates to [PlatformCopyAdapter.compare].
 *
 * All arithmetic is performed using [Long] so that offsets and lengths near or above
 * 2 GiB cannot overflow before being checked.
 *
 * @throws IllegalArgumentException when [length] is negative.
 * @throws IndexOutOfBoundsException when a range falls outside of its array.
 */
internal fun checkedArrayCompare(
    left: ByteArray,
    leftPos: Int,
    right: ByteArray,
    rightPos: Int,
    length: Int
): Boolean {
    require(length >= 0) { "length ($length) < 0" }
    checkBounds(left.size.toLong(), leftPos.toLong(), leftPos.toLong() + length)
    checkBounds(right.size.toLong(), rightPos.toLong(), rightPos.toLong() + length)
    return PlatformCopyAdapter.compare(left, leftPos, right, rightPos, length)
}
