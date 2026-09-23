/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.bytestring.ByteString
import kotlinx.io.unsafe.UnsafeBufferOperations

@OptIn(UnsafeIoApi::class)
class SegmentRingInvariantCheckerTest {

    @Test
    fun emptyBufferPasses() {
        Buffer().checkInvariants()
    }

    @Test
    fun singleSegmentBufferPasses() {
        val buffer = Buffer()
        buffer.write(ByteArray(100) { it.toByte() })
        buffer.checkInvariants()
    }

    @Test
    fun multiSegmentBufferPasses() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 3 + 7) { it.toByte() })
        buffer.checkInvariants()
        buffer.skip(Segment.SIZE + 1L)
        buffer.checkInvariants()
    }

    @Test
    fun sharedSegmentsPass() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 2) { it.toByte() })
        val snapshot = buffer.copy()
        buffer.checkInvariants()
        snapshot.checkInvariants()
        snapshot.clear()
        buffer.clear()
    }

    @Test
    fun externallyOwnedSegmentsPass() {
        val buffer = Buffer()
        UnsafeBufferOperations.moveToTail(buffer, ByteArray(Segment.SIZE * 2 + 5) { it.toByte() })
        buffer.checkInvariants()
        buffer.writeByte(1)
        buffer.checkInvariants()
    }

    @Test
    fun brokenBackLinkIsDetected() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 2) { it.toByte() })
        buffer.head!!.next!!.prev = null
        assertFailsWith<IllegalStateException> { buffer.checkInvariants() }
    }

    @Test
    fun sizeMismatchIsDetected() {
        val buffer = Buffer()
        buffer.write(ByteArray(10))
        buffer.sizeMut += 1
        assertFailsWith<IllegalStateException> { buffer.checkInvariants() }
    }

    @Test
    fun corruptedPosLimitIsDetected() {
        val buffer = Buffer()
        buffer.write(ByteArray(10))
        buffer.skip(1)
        buffer.head!!.limit = buffer.head!!.pos - 1
        assertFailsWith<IllegalStateException> { buffer.checkInvariants() }
    }

    @Test
    fun tailWithSuccessorIsDetected() {
        val buffer = Buffer()
        buffer.write(ByteArray(10))
        buffer.tail!!.next = SegmentPool.take()
        assertFailsWith<IllegalStateException> { buffer.checkInvariants() }
    }

    @Test
    fun cycleIsDetected() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 2) { it.toByte() })
        // Create a cycle in the middle of the ring, keeping head/tail anchors intact.
        buffer.head!!.next!!.next = buffer.head
        assertFailsWith<IllegalStateException> { buffer.checkInvariants() }
    }

    @Test
    fun ringRemainsConsistentAfterExceptions() {
        val buffer = Buffer()
        assertFailsWith<EOFException> { buffer.readByte() }
        buffer.checkInvariants()

        buffer.write(ByteArray(100) { it.toByte() })
        // skip consumes the whole buffer and then fails midway.
        assertFailsWith<EOFException> { buffer.skip(1000) }
        assertEquals(0, buffer.size)
        buffer.checkInvariants()

        // Failed argument validation must not mutate the ring.
        assertFailsWith<IllegalArgumentException> { buffer.write(ByteArray(10), 5, 2) }
        assertFailsWith<IndexOutOfBoundsException> { buffer.copyTo(Buffer(), 0, 1) }
        buffer.checkInvariants()

        // readTo exhausts the buffer and then fails; the ring must stay consistent.
        buffer.write(ByteArray(50) { it.toByte() })
        val sink = Buffer()
        assertFailsWith<EOFException> { buffer.readTo(sink, 1000) }
        assertEquals(0, buffer.size)
        assertEquals(50, sink.size)
        buffer.checkInvariants()
        sink.checkInvariants()

        // A failed source transfer leaves both rings consistent.
        buffer.write(ByteArray(10) { it.toByte() })
        val shortSource = Buffer()
        shortSource.write(ByteArray(5) { it.toByte() })
        // The RawSource overload transfers as much as possible and then fails with EOF.
        assertFailsWith<EOFException> { buffer.write(shortSource as RawSource, 10) }
        buffer.checkInvariants()
        shortSource.checkInvariants()
        assertEquals(15, buffer.size)
        assertEquals(0, shortSource.size)
    }

    @Test
    fun randomShortSequencesKeepRingIntact() {
        // Fixed seeds: the test must be deterministic and must not depend on wall clock.
        for (seed in 0 until 10) {
            runRandomSequence(seed)
        }
    }

    private fun runRandomSequence(seed: Int) {
        val random = Random(seed)
        val buffer = Buffer()
        val shadow = ArrayDeque<Byte>()

        fun drainShadow(count: Int): ByteArray {
            val result = ByteArray(count)
            for (i in 0 until count) {
                result[i] = shadow.removeFirst()
            }
            return result
        }

        fun assertConsistent() {
            buffer.checkInvariants()
            assertEquals(shadow.size.toLong(), buffer.size, "seed=$seed")
        }

        repeat(200) { step ->
            when (random.nextInt(12)) {
                0 -> {
                    val b = random.nextInt(256).toByte()
                    buffer.writeByte(b)
                    shadow.add(b)
                }
                1 -> {
                    val data = ByteArray(random.nextInt(0, Segment.SIZE * 2 + 17)) { random.nextInt(256).toByte() }
                    buffer.write(data)
                    data.forEach { shadow.add(it) }
                }
                2 -> {
                    val value = random.nextInt()
                    buffer.writeInt(value)
                    shadow.add((value ushr 24).toByte())
                    shadow.add((value ushr 16).toByte())
                    shadow.add((value ushr 8).toByte())
                    shadow.add(value.toByte())
                }
                3 -> {
                    if (shadow.isNotEmpty()) {
                        val expected = shadow.removeFirst()
                        assertEquals(expected, buffer.readByte(), "seed=$seed")
                    }
                }
                4 -> {
                    if (shadow.isNotEmpty()) {
                        val count = random.nextInt(1, shadow.size + 1)
                        val dst = ByteArray(count)
                        val read = buffer.readAtMostTo(dst)
                        assertTrue(read > 0)
                        assertContentEquals(drainShadow(read), dst.copyOf(read), "seed=$seed")
                    }
                }
                5 -> {
                    if (shadow.isNotEmpty()) {
                        val count = random.nextInt(1, shadow.size + 1)
                        buffer.skip(count.toLong())
                        val _ = drainShadow(count)
                    }
                }
                6 -> {
                    // Move segments between buffers, exercising split/compact.
                    val other = Buffer()
                    val data = ByteArray(random.nextInt(1, Segment.SIZE + 100)) { random.nextInt(256).toByte() }
                    other.write(data)
                    val toMove = random.nextInt(1, data.size + 1)
                    buffer.write(other, toMove.toLong())
                    other.checkInvariants()
                    for (i in 0 until toMove) shadow.add(data[i])
                }
                7 -> {
                    // Zero-copy snapshot must see the same content and stay independent.
                    val snapshot = buffer.copy()
                    snapshot.checkInvariants()
                    assertEquals(shadow.size.toLong(), snapshot.size, "seed=$seed")
                    snapshot.clear()
                }
                8 -> {
                    val data = ByteArray(random.nextInt(0, 200)) { random.nextInt(256).toByte() }
                    buffer.write(ByteString(*data))
                    data.forEach { shadow.add(it) }
                }
                9 -> {
                    if (shadow.isNotEmpty()) {
                        val peek = buffer.peek()
                        val expected = shadow.removeFirst()
                        assertEquals(expected, peek.readByte(), "seed=$seed")
                        buffer.skip(1)
                    }
                }
                10 -> {
                    val data = ByteArray(random.nextInt(0, Segment.SIZE + 1)) { random.nextInt(256).toByte() }
                    UnsafeBufferOperations.moveToTail(buffer, data)
                    data.forEach { shadow.add(it) }
                }
                11 -> {
                    buffer.clear()
                    shadow.clear()
                }
            }
            assertConsistent()
            // Periodically verify the full content through a zero-copy snapshot.
            if (step % 25 == 24) {
                val snapshot = buffer.copy()
                assertContentEquals(shadow.toByteArray(), snapshot.readByteArray(), "seed=$seed")
            }
        }

        assertContentEquals(shadow.toByteArray(), buffer.readByteArray(), "seed=$seed")
        buffer.checkInvariants()
    }
}
