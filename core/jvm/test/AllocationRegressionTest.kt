package kotlinx.io

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the common read/write paths against extra allocations: once warmed up,
 * writing to and reading from a buffer's existing segments must not allocate
 * beyond a small amount of JVM background noise (observed to be well under
 * 200 bytes per 100k operations on an unmodified library).
 */
class AllocationRegressionTest {
    private fun threadAllocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean()
        check(bean is com.sun.management.ThreadMXBean) { "ThreadMXBean does not expose allocation data" }
        bean.setThreadAllocatedMemoryEnabled(true)
        return bean.currentThreadAllocatedBytes
    }

    @Test
    fun commonReadWriteDoesNotAllocate() {
        val buffer = Buffer()
        buffer.write(ByteArray(Segment.SIZE * 2) { it.toByte() })
        // Warm up: reach the steady state in which recycled segments come back from the pool.
        repeat(10_000) {
            buffer.writeInt(it)
            val _ = buffer.readInt()
        }
        val before = threadAllocatedBytes()
        repeat(100_000) {
            buffer.writeInt(it)
            val _ = buffer.readInt()
        }
        val allocated = threadAllocatedBytes() - before
        // Any per-operation allocation would show up as megabytes here; the threshold
        // only tolerates JVM background noise.
        assertTrue(allocated <= 1024, "Common read/write allocated $allocated bytes")
    }
}
