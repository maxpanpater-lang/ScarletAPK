package de.maxgraue.scarlettrecorder.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.media.AudioAttributes
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class AudioRecorder(private val sampleRate: Int = 48_000, private val channels: Int = 2) {
    val levels = MutableStateFlow(Pair(0f, 0f))
    val error = MutableStateFlow("")
    val elapsedSeconds = MutableStateFlow(0L)
    @Volatile var monitorEnabled = false
    @Volatile var metronomeEnabled = false
    @Volatile var bpm = 120f
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    fun start(file: File? = null, device: AudioDeviceInfo? = null, mode: String = "Stereo", output: AudioDeviceInfo? = null): Boolean {
        if (running.get() || thread?.isAlive == true) return false
        val mask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        if (device == null || !r.setPreferredDevice(device)) {
            r.release()
            return false
        }
        val wav = file?.let { WavWriter(it, sampleRate, if (mode == "Stereo") 2 else 1) }
        val second = if (file != null && mode == "Getrennt") WavWriter(File(file.parentFile, file.nameWithoutExtension + "-input2.wav"), sampleRate, 1) else null
        error.value = ""
        elapsedSeconds.value = 0
        record = r; running.set(true); r.startRecording()
        thread = Thread({
            val buffer = ShortArray(min)
            val left = ShortArray(min / 2)
            val right = ShortArray(min / 2)
            var frames = 0L
            var monitor: AudioTrack? = null
            var metronomeSample = 0L
            val wavQueue = wav?.let { PcmWriteQueue(it) }
            val secondQueue = second?.let { PcmWriteQueue(it) }
            try {
                if (output != null) {
                    monitor = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(maxOf(min * 2, AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                    check(monitor.setPreferredDevice(output)) { "USB monitoring output unavailable" }
                    monitor.play()
                }
                while (running.get()) {
                    val n = r.read(buffer, 0, buffer.size)
                    check(n > 0 && n % 2 == 0) { "Audio input interrupted ($n)" }
                    // Android can briefly report null while USB routing is refreshed.
                    // Only a confirmed different device is treated as disconnect.
                    val routedInput = r.routedDevice
                    if (routedInput != null && routedInput.id != device.id) {
                        throw IllegalStateException("USB audio connection interrupted")
                    }
                    var peakLeft = 0
                    var peakRight = 0
                    for (i in 0 until n / 2) {
                        left[i] = buffer[i * 2]
                        right[i] = buffer[i * 2 + 1]
                        peakLeft = maxOf(peakLeft, abs(left[i].toInt()))
                        peakRight = maxOf(peakRight, abs(right[i].toInt()))
                    }
                    levels.value = Pair(peakLeft / 32768f, peakRight / 32768f)
                    when (mode) {
                        "Stereo" -> wavQueue?.offer(buffer, n)
                        "Mono 2" -> wavQueue?.offer(right, n / 2)
                        else -> wavQueue?.offer(left, n / 2)
                    }
                    secondQueue?.offer(right, n / 2)
                    if (monitorEnabled && monitor != null) {
                        check(monitor.routedDevice == null || monitor.routedDevice?.id == output?.id) { "Monitoring-Ausgang getrennt" }
                        if (mode == "Mono 1" || mode == "Mono 2") {
                            val mono = if (mode == "Mono 1") left else right
                            for (i in 0 until n / 2) {
                                buffer[i * 2] = mono[i]
                                buffer[i * 2 + 1] = mono[i]
                            }
                        }
                        if (metronomeEnabled) {
                            val beatLength = (sampleRate * 60f / bpm.coerceIn(40f, 240f)).toLong().coerceAtLeast(1)
                            for (i in 0 until n) {
                                val beatPosition = metronomeSample % beatLength
                                if (beatPosition < sampleRate / 180) {
                                    val beat = (metronomeSample / beatLength) % 4
                                    val envelope = 1f - beatPosition.toFloat() / (sampleRate / 180f)
                                    val level = if (beat == 0L) 10500f else 6500f
                                    val click = (envelope * level).toInt()
                                    buffer[i] = (buffer[i].toInt() + click).coerceAtMost(32767).toShort()
                                }
                                metronomeSample++
                            }
                        }
                        monitor.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                    frames += n / 2
                    elapsedSeconds.value = frames / sampleRate
                }
            } catch (failure: Exception) {
                error.value = failure.message ?: "Recording error"
            } finally {
                running.set(false)
                runCatching { r.stop() }
                r.release()
                runCatching { monitor?.stop() }
                monitor?.release()
                runCatching { wavQueue?.close() }.onFailure { error.value = "WAV could not be finalized" }
                runCatching { secondQueue?.close() }
                levels.value = Pair(0f, 0f)
            }
        }, "scarlett-recorder")
        thread!!.start(); return true
    }
    fun stop() {
        running.set(false)
        runCatching { record?.stop() }
        thread?.join(3000)
        if (thread?.isAlive != true) {
            thread = null
            record = null
        }
    }
}
