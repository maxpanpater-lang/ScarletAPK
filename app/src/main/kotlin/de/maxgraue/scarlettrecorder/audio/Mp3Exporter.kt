package de.maxgraue.scarlettrecorder.audio

import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.RandomAccessFile

/** Encodes PCM16 WAV using bundled Java LAME, independent of Android codecs. */
object Mp3Exporter {
    fun export(wav: File, mp3: File, bitrate: Int = 192_000): Result<File> = runCatching {
        require(!mp3.exists()) { "Zieldatei existiert bereits" }
        val temporary = File(mp3.path + ".partial")
        try {
            RandomAccessFile(wav, "r").use { input ->
                fun text(): String {
                    val bytes = ByteArray(4)
                    input.readFully(bytes)
                    return String(bytes, Charsets.US_ASCII)
                }
                fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                fun ushort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
                require(text() == "RIFF")
                uint()
                require(text() == "WAVE")
                var channels = 0
                var rate = 0
                var dataOffset = 0L
                var dataSize = 0L
                while (input.filePointer + 8 <= input.length()) {
                    val kind = text()
                    val size = uint()
                    val start = input.filePointer
                    require(size <= input.length() - start) { "Unvollständige WAV-Datei" }
                    when (kind) {
                        "fmt " -> {
                            require(size >= 16 && ushort() == 1) { "PCM-WAV benötigt" }
                            channels = ushort()
                            rate = uint().toInt()
                            uint()
                            ushort()
                            require(ushort() == 16) { "16-Bit-WAV benötigt" }
                        }
                        "data" -> { dataOffset = start; dataSize = size }
                    }
                    input.seek(start + size + size % 2)
                }
                require(channels in 1..2 && rate in 8000..48000 && dataOffset > 0)
                require(dataSize % (channels * 2) == 0L)
                val lame = Lame()
                val gain = GainAnalysis()
                val bits = BitStream()
                val presets = Presets()
                val quantizePvt = QuantizePVT()
                val quantize = Quantize()
                val vbr = VBRTag()
                val version = Version()
                val id3 = ID3Tag()
                val reservoir = Reservoir()
                val takehiro = Takehiro()
                val decoder = MPGLib()
                val decoderInterface = Interface()
                val common = Common()
                lame.setModules(gain, bits, presets, quantizePvt, quantize, vbr, version, id3, decoder)
                bits.setModules(gain, decoder, version, vbr)
                id3.setModules(bits, version)
                presets.setModules(lame)
                quantize.setModules(bits, reservoir, quantizePvt, takehiro)
                quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
                reservoir.setModules(bits)
                takehiro.setModules(quantizePvt)
                vbr.setModules(lame, bits, version)
                decoder.setModules(decoderInterface, common)
                decoderInterface.setModules(vbr, common)
                val flags = lame.lame_init()
                try {
                    flags.num_channels = channels
                    flags.in_samplerate = rate
                    flags.brate = bitrate / 1000
                    flags.quality = 3
                    flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.STEREO
                    flags.bWriteVbrTag = false
                    flags.write_id3tag_automatic = false
                    id3.id3tag_init(flags)
                    check(lame.lame_init_params(flags) >= 0) { "MP3-Encoder konnte nicht starten" }
                    input.seek(dataOffset)
                    var remaining = dataSize / (channels * 2)
                    val left = IntArray(1152)
                    val right = IntArray(1152)
                    val encoded = ByteArray(16384)
                    temporary.outputStream().buffered().use { output ->
                        while (remaining > 0) {
                            val frames = minOf(remaining, 1152L).toInt()
                            for (i in 0 until frames) {
                                left[i] = java.lang.Short.reverseBytes(input.readShort()).toInt() shl 16
                                right[i] = if (channels == 2) java.lang.Short.reverseBytes(input.readShort()).toInt() shl 16 else left[i]
                            }
                            val count = lame.lame_encode_buffer_int(flags, left, right, frames, encoded, 0, encoded.size)
                            check(count >= 0) { "MP3-Kodierung fehlgeschlagen ($count)" }
                            output.write(encoded, 0, count)
                            remaining -= frames
                        }
                        val count = lame.lame_encode_flush(flags, encoded, 0, encoded.size)
                        check(count >= 0)
                        output.write(encoded, 0, count)
                    }
                } finally { lame.lame_close(flags) }
            }
            check(temporary.renameTo(mp3)) { "MP3 konnte nicht gespeichert werden" }
            mp3
        } finally { temporary.delete() }
    }
}
