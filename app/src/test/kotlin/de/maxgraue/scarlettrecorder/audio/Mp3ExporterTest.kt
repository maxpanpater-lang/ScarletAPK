package de.maxgraue.scarlettrecorder.audio

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class Mp3ExporterTest {
    @Test fun exportsMonoAndStereoWithoutAndroidCodec() {
        val directory = Files.createTempDirectory("scarlett-export").toFile()
        for (channels in 1..2) {
            val wav = File(directory, "input-$channels.wav")
            WavWriter(wav, 48000, channels).use { writer ->
                val samples = ShortArray(48000 * channels) { index ->
                    (kotlin.math.sin(2 * Math.PI * 440 * (index / channels) / 48000) * 16000).toInt().toShort()
                }
                writer.writePcm16(samples)
            }
            val mp3 = File(directory, "output-$channels.mp3")
            val result = Mp3Exporter.export(wav, mp3)
            assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
            assertTrue(mp3.length() > 1000)
            assertTrue(wav.exists())
            assertFalse(File(mp3.path + ".partial").exists())
        }
    }
}
