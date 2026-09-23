package kotlinx.io

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [SegmentRingChecker] and randomized short-sequence invariant checking
 * of buffer operations against a simple model.
 */
class SegmentRingInvariantTest {
    @BeforeTest
    fun enableChecker() {
        SegmentRingChecker.enabled = true
    }

    @AfterTest
    fun disableChecker() {
        SegmentRingChecker.enabled = false
    }

    @Test
    fun emptyBufferHasValidRing() {
        SegmentRingChecker.verify(Buffer())
    }

    @Test
    fun checkerIsNoOpWhenDisabled() {
        SegmentRingChecker.enabled = false
        val buffer = Buffer()
        buffer.sizeMut = 42 // deliberately inconsistent
        SegmentRingChecker.checkRing(buffer) // must not throw
    }

    @Test
    fun checkerDetectsInconsistentSize() {
        val buffer = Buffer()
        buffer.writeByte(1)
        buffer.sizeMut = 5
        assertFailsWith<IllegalStateException> { SegmentRingChecker.verify(buffer) }
    }

    @Test
    fun checkerDetectsNonEmptyRingWithNullTail() {
        val buffer = Buffer()
        buffer.writeByte(1)
        buffer.tail = null
        assertFailsWith<IllegalStateException> { SegmentRingChecker.verify(buffer) }
    }

    @Test
    fun checkerDetectsCycle() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE + 1))
        buffer.tail!!.next = buffer.head
        assertFailsWith<IllegalStateException> { SegmentRingChecker.verify(buffer) }
    }

    @Test
    fun checkerDetectsBrokenPrevLink() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE + 1))
        buffer.tail!!.prev = null
        assertFailsWith<IllegalStateException> { SegmentRingChecker.verify(buffer) }
    }

    @Test
    fun checkerDetectsBrokenPosLimitInvariant() {
        val buffer = Buffer()
        buffer.writeByte(1)
        buffer.head!!.pos = buffer.head!!.limit + 1
        assertFailsWith<IllegalStateException> { SegmentRingChecker.verify(buffer) }
    }

    @Test
    fun randomShortSequencesKeepRingValid() {
        for (seed in 0 until 32) {
            runRandomSequence(seed)
        }
    }

    private fun runRandomSequence(seed: Int) {
        val random = Random(seed)
        val buffer = Buffer()
        val model = ArrayDeque<Byte>()

        repeat(200) {
            when (random.nextInt(12)) {
                0 -> {
                    val value = random.nextInt().toByte()
                    buffer.writeByte(value)
                    model.addLast(value)
                }
                1 -> {
                    val value = random.nextInt()
                    buffer.writeInt(value)
                    model.addLast((value ushr 24).toByte())
                    model.addLast((value ushr 16).toByte())
                    model.addLast((value ushr 8).toByte())
                    model.addLast(value.toByte())
                }
                2 -> {
                    val data = ByteArray(random.nextInt(0, 130)) { random.nextInt().toByte() }
                    buffer.write(data)
                    data.forEach(model::addLast)
                }
                3 -> if (model.isNotEmpty()) {
                    assertEquals(model.removeFirst(), buffer.readByte())
                }
                4 -> if (model.size >= 4) {
                    val expected = (model.removeFirst().toInt() and 0xff shl 24) or
                            (model.removeFirst().toInt() and 0xff shl 16) or
                            (model.removeFirst().toInt() and 0xff shl 8) or
                            (model.removeFirst().toInt() and 0xff)
                    assertEquals(expected, buffer.readInt())
                }
                5 -> {
                    val toSkip = random.nextInt(0, model.size + 1)
                    buffer.skip(toSkip.toLong())
                    repeat(toSkip) { model.removeFirst() }
                }
                6 -> {
                    val dst = ByteArray(random.nextInt(1, 64))
                    val read = buffer.readAtMostTo(dst, 0, dst.size)
                    if (model.isEmpty()) {
                        assertEquals(-1, read)
                    } else {
                        assertEquals(minOf(dst.size, model.size), read)
                        for (i in 0 until read) {
                            assertEquals(model.removeFirst(), dst[i])
                        }
                    }
                }
                7 -> {
                    val copy = buffer.copy()
                    SegmentRingChecker.verify(copy)
                    assertContentEquals(model.toByteArray(), copy.readByteArray())
                }
                8 -> {
                    val out = Buffer()
                    buffer.copyTo(out)
                    SegmentRingChecker.verify(out)
                    assertContentEquals(model.toByteArray(), out.readByteArray())
                }
                9 -> {
                    val data = ByteArray(random.nextInt(0, 40)) { random.nextInt().toByte() }
                    val other = Buffer()
                    other.write(data)
                    val toMove = random.nextInt(0, data.size + 1)
                    buffer.write(other, toMove.toLong())
                    SegmentRingChecker.verify(other)
                    for (i in 0 until toMove) {
                        model.addLast(data[i])
                    }
                }
                10 -> {
                    buffer.clear()
                    model.clear()
                }
                11 -> if (model.isNotEmpty()) {
                    val peek = buffer.peek()
                    val toRead = random.nextInt(1, model.size + 1)
                    val peeked = peek.readByteArray(toRead)
                    assertContentEquals(model.take(toRead).toByteArray(), peeked)
                    // Peeking must not consume the upstream buffer.
                    assertEquals(model.size.toLong(), buffer.size)
                }
            }
            SegmentRingChecker.verify(buffer)
            assertEquals(model.size.toLong(), buffer.size, "seed=$seed")
        }

        assertContentEquals(model.toByteArray(), buffer.readByteArray())
        SegmentRingChecker.verify(buffer)
    }
}
