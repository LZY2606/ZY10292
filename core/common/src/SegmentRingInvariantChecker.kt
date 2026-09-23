/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

/**
 * Debug-time checker for segment and segment-ring invariants.
 *
 * The checker is never invoked from production code paths; it exists so that tests
 * (and ad-hoc debugging) can validate the ownership and linking invariants maintained
 * by the segment-ownership core:
 *  - `0 <= pos <= limit <= data.size` for every segment;
 *  - the ring is a well-formed doubly-linked list anchored at `head`/`tail`;
 *  - the sum of readable bytes over the ring equals the buffer's size;
 *  - ownership states are consistent with the copy tracker (reference generation).
 */
internal object SegmentRingInvariantChecker {
    /**
     * Validates invariants of a single segment.
     */
    fun checkSegment(segment: Segment) {
        check(segment.pos >= 0) { "pos (${segment.pos}) < 0" }
        check(segment.limit >= segment.pos) { "limit (${segment.limit}) < pos (${segment.pos})" }
        check(segment.limit <= segment.dataAsByteArray(false).size) {
            "limit (${segment.limit}) exceeds data size (${segment.dataAsByteArray(false).size})"
        }
        // Ownership must be consistent with the reference generation tracking:
        // a segment reports shared state only when a copy tracker is attached.
        if (segment.shared) {
            check(segment.copyTracker != null) { "shared segment without a copy tracker" }
        }
        // A non-owning segment is a frozen view and must never be exclusive.
        if (!segment.owner) {
            check(segment.ownership != SegmentOwnership.EXCLUSIVE) {
                "exclusive segment must own its data"
            }
        }
    }

    /**
     * Traverses the ring starting at [head] and validates structural invariants:
     * links are consistent in both directions, the ring is anchored by [head] and [tail],
     * contains no cycles, and the total number of readable bytes equals [expectedSize].
     */
    fun checkRing(head: Segment?, tail: Segment?, expectedSize: Long) {
        if (head == null) {
            check(tail == null) { "tail is not null while head is null" }
            check(expectedSize == 0L) { "size ($expectedSize) is not zero for an empty ring" }
            return
        }
        check(tail != null) { "tail is null while head is not null" }
        check(head.prev == null) { "head has a predecessor" }
        check(tail.next == null) { "tail has a successor" }

        val visited = HashSet<Segment>()
        var size = 0L
        var previous: Segment? = null
        var current: Segment? = head
        while (current != null) {
            check(visited.add(current)) { "cycle detected in the segment ring" }
            checkSegment(current)
            check(current.prev === previous) { "broken back link" }
            size += current.size
            previous = current
            current = current.next
        }
        check(previous === tail) { "last reachable segment is not the tail" }
        check(size == expectedSize) { "ring byte count ($size) != buffer size ($expectedSize)" }
    }
}

/**
 * Validates all segment-ring invariants of this buffer. For use in tests and debugging only.
 */
internal fun Buffer.checkInvariants() {
    SegmentRingInvariantChecker.checkRing(head, tail, size)
}
