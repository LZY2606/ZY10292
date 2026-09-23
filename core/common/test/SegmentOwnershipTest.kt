/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.unsafe.UnsafeBufferOperations

@OptIn(UnsafeIoApi::class)
class SegmentOwnershipTest {

    @Test
    fun freshSegmentIsExclusive() {
        val segment = SegmentPool.take()
        assertEquals(SegmentOwnership.EXCLUSIVE, segment.ownership)
        assertTrue(segment.canAppend)
        assertTrue(segment.isPoolEligible)
        SegmentPool.recycle(segment)
    }

    @Test
    fun sharedCopyTransitionsBothSegmentsToShared() {
        val segment = SegmentPool.take()
        segment.write(ByteArray(128), 0, 128)

        val copy = segment.sharedCopy()

        assertEquals(SegmentOwnership.SHARED, segment.ownership)
        assertEquals(SegmentOwnership.SHARED, copy.ownership)
        // The owner may still append, the copy is frozen for writes.
        assertTrue(segment.canAppend)
        assertFalse(copy.canAppend)
        // Neither may return to the pool while the reference generation is alive.
        assertFalse(segment.isPoolEligible)
        assertFalse(copy.isPoolEligible)

        SegmentPool.recycle(segment)
        SegmentPool.recycle(copy)
    }

    @Test
    fun wrappedExternalArrayIsNotExclusive() {
        val data = ByteArray(100) { it.toByte() }
        val segment = Segment.new(data, 0, data.size, AlwaysSharedCopyTracker, owner = false)

        assertEquals(SegmentOwnership.SHARED, segment.ownership)
        assertFalse(segment.canAppend)
        assertFalse(segment.isPoolEligible)
    }

    @Test
    fun segmentWithoutOwnerAndTrackerIsFrozen() {
        val segment = Segment.new(ByteArray(16), 0, 0, null, owner = false)

        assertEquals(SegmentOwnership.FROZEN, segment.ownership)
        assertFalse(segment.canAppend)
        assertFalse(segment.isPoolEligible)
    }

    @Test
    fun moveToTailProducesNonExclusiveSegment() {
        val buffer = Buffer()
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(64) { it.toByte() })

        val head = buffer.head!!
        assertEquals(SegmentOwnership.SHARED, head.ownership)
        assertFalse(head.canAppend)
        assertFalse(head.isPoolEligible)
        buffer.checkInvariants()
    }

    @Test
    fun releaseForPoolingAcceptsNeverSharedSegment() {
        val segment = SegmentPool.take()
        assertTrue(segment.releaseForPooling())
        SegmentPool.recycle(segment)
    }

    @Test
    fun releaseForPoolingRejectsSharedSegment() {
        val segment = SegmentPool.take()
        val copy = segment.sharedCopy()
        // The reference generation is still alive: the segment must not be pooled.
        assertFalse(segment.releaseForPooling())
        SegmentPool.recycle(copy)
    }

    @Test
    fun copyTrackerTracksReferenceGenerations() {
        val tracker = SegmentPool.tracker()
        if (tracker === AlwaysSharedCopyTracker) {
            // Platforms without a pooling-capable tracker always report shared state.
            assertTrue(tracker.shared)
            assertTrue(tracker.removeCopy())
            return
        }
        assertFalse(tracker.shared)
        tracker.addCopy()
        assertTrue(tracker.shared)
        tracker.addCopy()
        assertTrue(tracker.shared)
        // Releasing one generation keeps the tracker shared.
        val _ = tracker.removeCopy()
        assertTrue(tracker.shared)
    }

    @Test
    fun snapshotSharesAllSegments() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 2 + 1) { it.toByte() })

        val snapshot = buffer.copy()

        var segment = buffer.head
        while (segment != null) {
            assertEquals(SegmentOwnership.SHARED, segment.ownership)
            segment = segment.next
        }
        buffer.checkInvariants()
        snapshot.checkInvariants()
        assertEquals(buffer.size, snapshot.size)

        buffer.clear()
        snapshot.clear()
    }

    @Test
    fun toIntCheckedAcceptsIntRange() {
        assertEquals(0, 0L.toIntChecked())
        assertEquals(Int.MAX_VALUE, Int.MAX_VALUE.toLong().toIntChecked())
        assertEquals(Int.MIN_VALUE, Int.MIN_VALUE.toLong().toIntChecked())
        assertEquals(1_999_999_999, 1_999_999_999L.toIntChecked())
    }

    @Test
    fun toIntCheckedRejectsValuesOutsideIntRange() {
        // 2 GiB boundary: the first value not representable as an Int.
        assertFailsWith<IllegalArgumentException> { (Int.MAX_VALUE.toLong() + 1).toIntChecked() }
        assertFailsWith<IllegalArgumentException> { (Int.MIN_VALUE.toLong() - 1).toIntChecked() }
        assertFailsWith<IllegalArgumentException> { Long.MAX_VALUE.toIntChecked() }
    }

    @Test
    fun toNonNegativeIntCheckedBoundaries() {
        assertEquals(0, 0L.toNonNegativeIntChecked())
        assertEquals(Int.MAX_VALUE, Int.MAX_VALUE.toLong().toNonNegativeIntChecked())
        assertFailsWith<IllegalArgumentException> { (-1L).toNonNegativeIntChecked() }
        assertFailsWith<IllegalArgumentException> { (Int.MAX_VALUE.toLong() + 1).toNonNegativeIntChecked() }
        assertFailsWith<IllegalArgumentException> { (1L shl 31).toNonNegativeIntChecked() }
    }

    @Test
    fun emptyAndZeroSizeSegmentsKeepValidOwnership() {
        // Empty buffer: no segments at all.
        val buffer = Buffer()
        buffer.checkInvariants()

        // Zero-length writes must not create segments.
        buffer.write(ByteArray(0))
        assertEquals(0, buffer.size)
        buffer.checkInvariants()

        // Zero-length external array: a frozen empty view.
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(0))
        assertEquals(0, buffer.size)
        buffer.checkInvariants()
        val head = buffer.head!!
        assertFalse(head.canAppend)
        buffer.clear()
        buffer.checkInvariants()
    }
}
