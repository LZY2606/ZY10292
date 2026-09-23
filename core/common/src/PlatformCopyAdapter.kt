package kotlinx.io

/**
 * Platform adapter for bulk byte-array operations.
 *
 * The adapter is the only place where platform-specific array/memory copy and compare
 * implementations live. It is deliberately dumb: all ranges passed to it are already
 * validated by the segment ownership core, and the adapter never decides pooling,
 * sharing, or ownership of segments.
 *
 * Contract for all functions (callers guarantee it, the adapter may assume it):
 *  - all offsets and lengths are non-negative and within the corresponding array bounds;
 *  - [copy] supports overlapping ranges within a single array as long as
 *    `dstPos <= srcPos` (the only overlap direction the core relies on).
 */
internal expect object PlatformCopyAdapter {
    /**
     * Copies [length] bytes from [src] starting at [srcPos] into [dst] starting at [dstPos].
     */
    fun copy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int)

    /**
     * Returns `true` if the [length] bytes of [a] starting at [aPos] are equal to
     * the [length] bytes of [b] starting at [bPos].
     */
    fun rangeEquals(a: ByteArray, aPos: Int, b: ByteArray, bPos: Int, length: Int): Boolean
}

/**
 * Default [PlatformCopyAdapter.copy] implementation for platforms without a specialized
 * bulk-copy primitive.
 */
internal fun copyBytesCommon(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int) {
    src.copyInto(dst, dstPos, srcPos, srcPos + length)
}

/**
 * Default [PlatformCopyAdapter.rangeEquals] implementation for platforms without
 * a specialized bulk-compare primitive.
 */
internal fun rangeEqualsCommon(a: ByteArray, aPos: Int, b: ByteArray, bPos: Int, length: Int): Boolean {
    for (i in 0 until length) {
        if (a[aPos + i] != b[bPos + i]) return false
    }
    return true
}
