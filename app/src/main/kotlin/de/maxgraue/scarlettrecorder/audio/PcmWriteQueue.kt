package de.maxgraue.scarlettrecorder.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Bounded producer/consumer queue so storage I/O never blocks the audio reader. */
class PcmWriteQueue(private val writer: WavWriter, capacity: Int = 8) : AutoCloseable {
    private val queue = ArrayBlockingQueue<ShortArray>(capacity)
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
        // A full queue means storage is too slow. Drop the newest block instead
        // of blocking the USB audio thread and causing monitoring glitches.
        queue.offer(copy)
    }

    override fun close() {
        closed = true
        worker.join(3000)
        if (worker.isAlive) writer.close()
    }
}
