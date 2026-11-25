package de.maxgraue.scarlettrecorder.audio

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Bounded producer/consumer queue so storage I/O never blocks the audio reader. */
class PcmWriteQueue(private val writer: WavWriter, capacity: Int = 8) : AutoCloseable {
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var closed = false
    private val worker = Thread({
        while (!closed || queue.isNotEmpty()) {
            val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            writer.writePcm16(chunk)
        }
        writer.close()
    }, "scarlett-wav-writer")

    init { worker.start() }

    fun offer(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val copy = samples.copyOf(count)
        // Never drop or block the audio reader. The writer drains this queue independently.
        queue.add(copy)
    }

    override fun close() {
        closed = true
        worker.join(3000)
        if (worker.isAlive) writer.close()
    }
}
