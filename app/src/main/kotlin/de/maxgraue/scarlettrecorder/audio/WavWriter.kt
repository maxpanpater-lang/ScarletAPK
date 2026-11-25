package de.maxgraue.scarlettrecorder.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** Writes little-endian PCM16 WAV files and patches the header when closed. */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var dataBytes = 0L
    init { writeHeader(0) }

    fun writePcm16(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size)
        for (i in 0 until count) { val s = samples[i].toInt(); out.write(s and 0xff); out.write((s shr 8) and 0xff) }
        dataBytes += count * 2L
    }

    override fun close() { out.flush(); out.close(); RandomAccessFilePatch.patch(file, sampleRate, channels, dataBytes) }

    private fun writeHeader(dataSize: Long) {
        fun text(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        fun le(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        text("RIFF"); le((36 + dataSize).toInt()); text("WAVEfmt "); le(16); le(1 or (channels shl 16)); le(sampleRate); le(sampleRate * channels * 2); le((channels * 2) or (16 shl 16)); text("data"); le(dataSize.toInt())
    }
}

private object RandomAccessFilePatch {
    fun patch(file: File, sampleRate: Int, channels: Int, dataSize: Long) {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            fun le(offset: Long, value: Int) { raf.seek(offset); raf.write(value and 255); raf.write(value shr 8 and 255); raf.write(value shr 16 and 255); raf.write(value shr 24 and 255) }
            le(4, (36 + dataSize).toInt()); le(24, sampleRate); le(28, sampleRate * channels * 2); le(40, dataSize.toInt())
        }
    }
}
