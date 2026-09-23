/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

/**
 * Ownership state of a [Segment].
 *
 * The ownership state is the single source of truth for what operations are allowed
 * on a segment and whether the segment may return to [SegmentPool].
 * The state is derived from the segment's `owner` flag and its copy tracker
 * (the reference generation tracking shared copies), so deriving it never allocates.
 *
 * All segment read/write/copy/snapshot operations are expressed as a small number of
 * transitions between these states:
 *  - [SegmentPool.take] hands out an [EXCLUSIVE] segment;
 *  - [Segment.sharedCopy] transitions a segment (and its new copy) to [SHARED];
 *  - wrapping an external array (see `UnsafeBufferOperations.moveToTail`) produces
 *    a [SHARED] segment that can never become exclusive again;
 *  - [SegmentPool.recycle] only accepts segments for which [releaseForPooling]
 *    reports that the last shared reference generation was released.
 */
internal enum class SegmentOwnership {
    /**
     * The segment solely owns its byte array: it may be read, written and appended to,
     * and it is eligible for recycling into [SegmentPool].
     */
    EXCLUSIVE,

    /**
     * The segment's byte array is shared with other segments or byte strings.
     * The owning segment may still append at its `limit`, shared copies are read-only.
     * A shared segment must never return to [SegmentPool] while any reference
     * generation is still alive.
     */
    SHARED,

    /**
     * The segment is a read-only view over a byte array it does not own
     * (a shared copy, an externally owned array, or a sentinel).
     * It may be read, but never written to and never recycled.
     */
    FROZEN
}

/**
 * Returns the current [SegmentOwnership] of this segment.
 */
internal val Segment.ownership: SegmentOwnership
    get() = when {
        shared -> SegmentOwnership.SHARED
        owner -> SegmentOwnership.EXCLUSIVE
        else -> SegmentOwnership.FROZEN
    }

/**
 * `true` if this segment may be written to (appending data at `limit`).
 * Only the owner of the underlying byte array may write.
 */
internal val Segment.canAppend: Boolean
    get() = owner

/**
 * `true` if this segment is currently eligible to return to [SegmentPool].
 *
 * This is a read-only snapshot of the pool eligibility rule: only [SegmentOwnership.EXCLUSIVE]
 * segments may be pooled. Shared segments must never return to the pool.
 * The pool itself should use [releaseForPooling], which also releases the segment's
 * reference generation.
 */
internal val Segment.isPoolEligible: Boolean
    get() = ownership == SegmentOwnership.EXCLUSIVE

/**
 * Releases this segment's reference generation and reports whether the segment
 * may return to [SegmentPool].
 *
 * Returns `false` if the segment is still shared after this call, meaning it must not
 * be recycled. A segment without a copy tracker was never shared and is always accepted.
 */
internal fun Segment.releaseForPooling(): Boolean {
    val tracker = copyTracker ?: return true
    return !tracker.removeCopy()
}

/**
 * Converts this value to [Int], throwing [IllegalArgumentException] if the value
 * does not fit into an [Int]. Intended for checked `Long`/`Int` conversions at
 * buffer/segment boundaries, where sizes may approach or exceed 2 GiB.
 */
internal fun Long.toIntChecked(): Int {
    require(this in Int.MIN_VALUE..Int.MAX_VALUE) {
        "Value ($this) does not fit into an Int"
    }
    return toInt()
}

/**
 * Converts this non-negative value to [Int], throwing [IllegalArgumentException] if
 * the value is negative or does not fit into an [Int].
 */
internal fun Long.toNonNegativeIntChecked(): Int {
    require(this in 0..Int.MAX_VALUE) {
        "Value ($this) is not within the range [0..${Int.MAX_VALUE}]"
    }
    return toInt()
}
