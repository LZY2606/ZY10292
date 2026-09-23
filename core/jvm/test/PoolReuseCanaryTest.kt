package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Canary tests for segment pool reuse: recycled exclusive segments must come back
 * from the pool, while shared segments must never enter it.
 */
class PoolReuseCanaryTest {
    private fun drainCurrentThreadBucket() {
        while (SegmentPool.byteCount > 0) {
            val _ = SegmentPool.take()
        }
    }

    @Test
    fun recycledSegmentIsReusedWithDataIntact() {
        drainCurrentThreadBucket()
        val segment = SegmentPool.take()
        val data = segment.dataAsByteArray(false)
        data[0] = 0x5a
        data[data.size - 1] = 0x3c
        SegmentPool.recycle(segment)
        // The canary segment is back in the pool, byte-for-byte.
        assertEquals(Segment.SIZE, SegmentPool.byteCount)
        val reused = SegmentPool.take()
        assertSame(segment, reused)
        val reusedData = reused.dataAsByteArray(false)
        assertEquals(0x5a.toByte(), reusedData[0])
        assertEquals(0x3c.toByte(), reusedData[reusedData.size - 1])
        SegmentPool.recycle(reused)
    }

    @Test
    fun sharedSegmentIsPooledOnlyAfterLastRelease() {
        drainCurrentThreadBucket()
        val buffer = Buffer()
        buffer.writeByte(1)
        val snapshot = buffer.copy()
        assertEquals(0, SegmentPool.byteCount)
        // The first release must not pool anything: the array is still shared.
        buffer.clear()
        assertEquals(0, SegmentPool.byteCount)
        // The last release returns exactly one segment to the pool.
        snapshot.clear()
        assertEquals(Segment.SIZE, SegmentPool.byteCount)
    }

    @OptIn(UnsafeIoApi::class)
    @Test
    fun frozenSegmentFromMoveToTailIsNeverPooled() {
        drainCurrentThreadBucket()
        val buffer = Buffer()
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(4) { it.toByte() })
        buffer.clear()
        assertEquals(0, SegmentPool.byteCount)
    }
}
