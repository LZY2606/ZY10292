package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.unsafe.UnsafeBufferOperations

@OptIn(UnsafeIoApi::class)
class SegmentOwnershipTest {
    @Test
    fun freshSegmentIsExclusiveAndPoolEligible() {
        val segment = SegmentPool.take()
        assertEquals(SegmentOwnership.Exclusive, segment.ownership)
        assertTrue(segment.poolEligible)
        segment.releaseToPool()
    }

    @Test
    fun sharingTransitionsToSharedAndFrozen() {
        val segment = SegmentPool.take()
        val copy = segment.sharedCopy()

        assertEquals(SegmentOwnership.Shared, segment.ownership)
        assertEquals(SegmentOwnership.Frozen, copy.ownership)
        assertFalse(segment.poolEligible)
        assertFalse(copy.poolEligible)

        segment.releaseToPool()
        copy.releaseToPool()
    }

    @Test
    fun arrayMovedToTailBecomesFrozen() {
        val buffer = Buffer()
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(10) { it.toByte() })

        val head = buffer.head!!
        assertEquals(SegmentOwnership.Frozen, head.ownership)
        assertFalse(head.poolEligible)
        SegmentRingChecker.verify(buffer)

        assertContentEquals(ByteArray(10) { it.toByte() }, buffer.readByteArray())
        SegmentRingChecker.verify(buffer)
    }

    @Test
    fun splitOfLargePrefixSharesAndFreezes() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE) { it.toByte() })
        val head = buffer.head!!

        val prefix = head.split(Segment.SHARE_MINIMUM)
        assertEquals(SegmentOwnership.Frozen, prefix.ownership)
        assertEquals(SegmentOwnership.Shared, head.ownership)
        assertFalse(prefix.poolEligible)
        assertFalse(head.poolEligible)
    }

    @Test
    fun splitOfSmallPrefixCopiesAndStaysExclusive() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE) { it.toByte() })
        val head = buffer.head!!

        val prefix = head.split(1)
        assertEquals(SegmentOwnership.Exclusive, prefix.ownership)
        assertEquals(SegmentOwnership.Exclusive, head.ownership)
        assertTrue(prefix.poolEligible)
        assertTrue(head.poolEligible)
    }

    @Test
    fun sharedSegmentsAreNeverPooled() {
        val buffer = Buffer()
        buffer.write(ByteArray(100) { it.toByte() })
        val snapshot = buffer.copy()

        buffer.clear()
        // The snapshot's data must survive the release of the original segments.
        assertContentEquals(ByteArray(100) { it.toByte() }, snapshot.readByteArray())
    }

    @Test
    fun ringSurvivesFailedWrite() {
        val buffer = Buffer()
        buffer.write(ByteArray(100) { it.toByte() })
        val sizeBefore = buffer.size

        try {
            val _ = UnsafeBufferOperations.writeToTail(buffer, 1) { _, _, _ ->
                throw RuntimeException("simulated failure")
            }
        } catch (_: RuntimeException) {
            // expected
        }

        // The ring must not be broken after an exception: size is unchanged and the
        // buffer remains fully readable.
        assertEquals(sizeBefore, buffer.size)
        SegmentRingChecker.verify(buffer)
        assertContentEquals(ByteArray(100) { it.toByte() }, buffer.readByteArray())
    }
}
