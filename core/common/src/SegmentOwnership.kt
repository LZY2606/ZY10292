package kotlinx.io

/**
 * Segment ownership core.
 *
 * This file centralizes every ownership invariant and every ownership transition for [Segment]
 * so that they can be reviewed in one place. Buffer operations (read, write, copy, snapshot,
 * peek, split, compact) are only allowed to change segment ownership through the small set of
 * conversions defined here and in [Segment]:
 *
 *  - acquisition: [SegmentPool.take] hands out an [SegmentOwnership.Exclusive] segment;
 *  - sharing: [Segment.sharedCopy] freezes a view over a segment's array and moves the segment
 *    itself to [SegmentOwnership.Shared];
 *  - release: [releaseToPool] returns a segment to [SegmentPool] if and only if it is
 *    [poolEligible]; shared segments are never pooled, only their reference generation
 *    (see [SegmentCopyTracker]) is rolled back;
 *  - mechanical re-linking: [Segment.split], [Segment.compact], [Segment.push] and [Segment.pop]
 *    move bytes or nodes between rings without changing array ownership on their own.
 *
 * The core is stateless: it derives everything from [Segment.copyTracker] and [Segment.owner]
 * and never attaches additional objects to a segment.
 */

/**
 * Ownership state of a [Segment]'s backing byte array.
 */
internal enum class SegmentOwnership {
    /**
     * The segment is the sole owner of its backing array.
     * It may be read, written, appended to at `limit`, and recycled into [SegmentPool].
     */
    Exclusive,

    /**
     * The segment owns its backing array and may append to it at `limit`,
     * but the array is also referenced by [Frozen] views (other segments or byte strings).
     * The segment must never be recycled into [SegmentPool] while shared.
     */
    Shared,

    /**
     * The segment is a read-only view over an array owned (or previously owned) by another segment.
     * Only `pos`/`limit` adjustments are allowed; the segment must never be written to
     * and must never be recycled into [SegmentPool].
     */
    Frozen
}

/**
 * The current ownership state of this segment, derived from its copy tracker and owner flag.
 */
internal val Segment.ownership: SegmentOwnership
    get() = when {
        !shared -> SegmentOwnership.Exclusive
        owner -> SegmentOwnership.Shared
        else -> SegmentOwnership.Frozen
    }

/**
 * `true` if this segment may be returned to [SegmentPool].
 *
 * A segment is pool-eligible only while its backing array is not shared, i.e. while its
 * reference generation (tracked by [Segment.copyTracker]) shows no outstanding shared copies.
 */
internal val Segment.poolEligible: Boolean
    get() = !shared

/**
 * Checks the `pos`/`limit` invariant every segment has to satisfy:
 * `0 <= pos <= limit <= data.size`.
 *
 * Throws [IllegalStateException] if the invariant is violated.
 */
internal fun Segment.checkPosLimitInvariant() {
    check(pos >= 0) { "Segment invariant violated: negative pos ($pos)" }
    check(limit >= pos) { "Segment invariant violated: limit ($limit) < pos ($pos)" }
    check(limit <= dataAsByteArray(false).size) {
        "Segment invariant violated: limit ($limit) exceeds data size (${dataAsByteArray(false).size})"
    }
}

/**
 * Releases this segment back to [SegmentPool] if it is [poolEligible].
 *
 * If the segment's backing array is still shared, the segment is *not* pooled; instead the
 * reference generation recorded in [Segment.copyTracker] is rolled back by one step and the
 * segment is left to garbage collection. This is the only way buffer code may dispose of
 * a segment, which guarantees that a shared segment never re-enters the pool.
 *
 * The caller must unlink the segment from its ring before calling this function.
 */
internal fun Segment.releaseToPool() {
    if (copyTracker?.removeCopy() == true) return // Still shared: must not be pooled.
    SegmentPool.recycle(this)
}
