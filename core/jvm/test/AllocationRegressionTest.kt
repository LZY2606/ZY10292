/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests checking that the most common buffer read/write operations
 * do not allocate in steady state: segments must be recycled through the pool
 * and no per-operation objects may be introduced by the segment-ownership core
 * or the platform copy adapter.
 */
class AllocationRegressionTest {

    private val allocatedBytes: Long
        get() {
            val bean = ManagementFactory.getThreadMXBean()
            check(bean is com.sun.management.ThreadMXBean) { "com.sun.management.ThreadMXBean is required" }
            return bean.currentThreadAllocatedBytes
        }

    @Test
    fun primitiveReadWriteDoesNotAllocate() {
        val buffer = Buffer()
        // Warm up: let the JIT settle and the segment pool fill.
        repeat(WARMUP_ITERATIONS) {
            buffer.writeInt(it)
            val _ = buffer.readInt()
        }
        val before = allocatedBytes
        var acc = 0
        repeat(MEASURED_ITERATIONS) {
            buffer.writeInt(it)
            acc = acc xor buffer.readInt()
        }
        val allocated = allocatedBytes - before
        assertEquals(MEASURED_ITERATIONS.xorReduce(), acc)
        assertTrue(
            allocated < MEASURED_ITERATIONS / ALLOCATION_SLOP_DIVISOR,
            "Primitive read/write allocated $allocated bytes in $MEASURED_ITERATIONS iterations"
        )
    }

    @Test
    fun byteArrayReadWriteDoesNotAllocate() {
        val array = ByteArray(256) { it.toByte() }
        val buffer = Buffer()
        repeat(WARMUP_ITERATIONS) {
            buffer.write(array)
            val _ = buffer.readAtMostTo(array)
        }
        val before = allocatedBytes
        var totalRead = 0L
        repeat(MEASURED_ITERATIONS) {
            buffer.write(array)
            totalRead += buffer.readAtMostTo(array)
        }
        val allocated = allocatedBytes - before
        assertEquals(array.size.toLong() * MEASURED_ITERATIONS, totalRead)
        assertTrue(
            allocated < MEASURED_ITERATIONS / ALLOCATION_SLOP_DIVISOR,
            "ByteArray read/write allocated $allocated bytes in $MEASURED_ITERATIONS iterations"
        )
    }

    @Test
    fun bufferToBufferTransferDoesNotAllocate() {
        val source = Buffer()
        val sink = Buffer()
        source.write(ByteArray(Segment.SIZE) { it.toByte() })
        repeat(WARMUP_ITERATIONS) {
            sink.write(source, source.size)
            source.write(sink, sink.size)
        }
        val before = allocatedBytes
        repeat(MEASURED_ITERATIONS) {
            sink.write(source, source.size)
            source.write(sink, sink.size)
        }
        val allocated = allocatedBytes - before
        assertTrue(
            allocated < MEASURED_ITERATIONS / ALLOCATION_SLOP_DIVISOR,
            "Buffer-to-buffer transfer allocated $allocated bytes in $MEASURED_ITERATIONS iterations"
        )
    }

    private companion object {
        const val WARMUP_ITERATIONS = 100_000
        const val MEASURED_ITERATIONS = 1_000_000

        /**
         * Allowed slack: on average less than one byte per hundred operations.
         * Any per-operation object (16+ bytes) would exceed this budget by orders of magnitude.
         */
        const val ALLOCATION_SLOP_DIVISOR = 100
    }

    private fun Int.xorReduce(): Int {
        var acc = 0
        for (i in 0 until this) acc = acc xor i
        return acc
    }
}
