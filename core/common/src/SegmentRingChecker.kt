package kotlinx.io

/**
 * Debug-only checker for buffer segment-ring invariants.
 *
 * The checker traverses the whole ring (the doubly-linked segment chain spanning from
 * [Buffer.head] to [Buffer.tail]) and verifies its structural integrity:
 *
 *  - `head == null` iff `tail == null`, and an empty ring has zero size;
 *  - `head.prev == null` and `tail.next == null`;
 *  - `prev`/`next` links are consistent in both directions and the ring contains no cycles;
 *  - every segment satisfies the `pos`/`limit` invariant (see [checkPosLimitInvariant]);
 *  - the sum of segment sizes equals [Buffer.size].
 *
 * The checker is not a part of the production data path: when [enabled] is `false`
 * (the default), [checkRing] is a no-op and nothing is allocated. Tests may enable it
 * to validate the ring after selected buffer operations, or call [verify] directly.
 */
internal object SegmentRingChecker {
    /**
     * Master switch for [checkRing]. `false` by default, so production code pays
     * no cost beyond a single field read.
     */
    var enabled: Boolean = false

    /**
     * Verifies the ring invariants of [buffer] if the checker is [enabled], does nothing otherwise.
     */
    fun checkRing(buffer: Buffer) {
        if (enabled) verify(buffer)
    }

    /**
     * Traverses the segment ring of [buffer] and verifies all ring invariants.
     *
     * Throws [IllegalStateException] on the first violated invariant.
     */
    fun verify(buffer: Buffer) {
        val head = buffer.head
        val tail = buffer.tail
        if (head == null) {
            check(tail == null) { "Ring invariant violated: head is null, but tail is not" }
            check(buffer.sizeMut == 0L) {
                "Ring invariant violated: empty ring with non-zero size (${buffer.sizeMut})"
            }
            return
        }
        checkNotNull(tail) { "Ring invariant violated: tail is null, but head is not" }
        check(head.prev == null) { "Ring invariant violated: head has a predecessor" }
        check(tail.next == null) { "Ring invariant violated: tail has a successor" }

        val visited = HashSet<Segment>()
        var segment: Segment? = head
        var previous: Segment? = null
        var totalSize = 0L
        while (segment != null) {
            check(visited.add(segment)) { "Ring invariant violated: cycle detected" }
            check(segment.prev === previous) { "Ring invariant violated: broken prev link" }
            segment.checkPosLimitInvariant()
            totalSize += segment.size
            previous = segment
            segment = segment.next
        }
        check(previous === tail) { "Ring invariant violated: tail is unreachable from head" }
        check(totalSize == buffer.sizeMut) {
            "Ring invariant violated: size is ${buffer.sizeMut}, but segments contain $totalSize bytes"
        }
    }
}
