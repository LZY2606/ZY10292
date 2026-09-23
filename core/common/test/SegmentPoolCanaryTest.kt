/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Canary tests for segment pool reuse: they detect regressions in the pool's
 * ability to recycle exclusive segments and in the rule that shared segments
 * must never return to the pool.
 *
 * On platforms with a no-op pool (`MAX_SIZE == 0`) the canaries are not applicable
 * and the tests return early.
 */
@OptIn(UnsafeIoApi::class)
class SegmentPoolCanaryTest {

    private val poolingSupported: Boolean
        get() = SegmentPool.MAX_SIZE > 0

    /**
     * Empties the calling thread's first-level pool bucket so that subsequent
     * byteCount observations are deterministic.
     */
    private fun drainPool() {
        while (SegmentPool.byteCount > 0) {
            val _ = SegmentPool.take()
        }
    }

    @Test
    fun recycledSegmentIsReused() {
        if (!poolingSupported) return
        drainPool()
        val segment = SegmentPool.take()
        SegmentPool.recycle(segment)
        // Canary: the pool must hand the very same segment back instead of allocating.
        assertSame(segment, SegmentPool.take())
    }

    @Test
    fun exclusiveSegmentsReturnToPool() {
        if (!poolingSupported) return
        drainPool()
        assertEquals(0, SegmentPool.byteCount)
        val buffer = Buffer()
        buffer.writeByte(1)
        buffer.clear()
        assertEquals(Segment.SIZE, SegmentPool.byteCount)
        // The next buffer reuses the pooled segment; clearing it returns it again.
        buffer.writeByte(1)
        buffer.clear()
        assertEquals(Segment.SIZE, SegmentPool.byteCount)
    }

    @Test
    fun sharedSegmentsNeverReturnToPool() {
        if (!poolingSupported) return
        drainPool()
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE) { it.toByte() })
        // Zero-copy snapshot: all segments become shared.
        val snapshot = buffer.copy()
        buffer.clear()
        // The shared segment must not have been pooled.
        assertEquals(0, SegmentPool.byteCount)
        // Releasing the last reference generation makes the segment poolable again.
        snapshot.clear()
        assertEquals(Segment.SIZE, SegmentPool.byteCount)
    }

    @Test
    fun externallyOwnedSegmentsNeverReturnToPool() {
        if (!poolingSupported) return
        drainPool()
        val buffer = Buffer()
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(100) { it.toByte() })
        buffer.clear()
        assertEquals(0, SegmentPool.byteCount)
    }
}
