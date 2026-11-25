package de.maxgraue.scarlettrecorder.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

enum class LooperState { IDLE, RECORDING, PLAYING, OVERDUBBING }

class LooperEngine(private val sampleRate: Int = 48_000) {
    val state = MutableStateFlow(LooperState.IDLE)
    val loopSeconds = MutableStateFlow(0f)
    val loopProgress = MutableStateFlow(0f)
    val error = MutableStateFlow("")
    val mixer = MultiTrackLooper(4)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var input: AudioRecord? = null
    @Volatile private var activeSlot = 0
    @Volatile var metronomeEnabled = false
    @Volatile var bpm = 120f

    fun start(device: AudioDeviceInfo, output: AudioDeviceInfo, slot: Int = activeSlot): Boolean {
        if (slot !in mixer.tracks.indices) return false
        activeSlot = slot
        mixer.tracks[slot].beginRecording()
        state.value = LooperState.RECORDING
        if (running.get()) return true

        val size = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return false
        val recorder = AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        if (recorder.state != AudioRecord.STATE_INITIALIZED || !recorder.setPreferredDevice(device)) {
            recorder.release()
            return false
        }
        val track = AudioTrack.Builder()
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(size * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (!track.setPreferredDevice(output)) {
            recorder.release()
            track.release()
            return false
        }
        input = recorder
        running.set(true)
        recorder.startRecording()
        track.play()
        thread = Thread({
            val buffer = ShortArray(size)
            val outputBuffer = ShortArray(size)
            var metronomeSample = 0L
            try {
                while (running.get()) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    val selected = mixer.tracks[activeSlot]
                    if (selected.state == LoopTrackState.RECORDING) selected.append(buffer, n)
                    java.util.Arrays.fill(outputBuffer, 0, n, 0.toShort())
                    val overdub = if (state.value == LooperState.OVERDUBBING) activeSlot else null
                    val mixed = mixer.mix(buffer, n, overdub)
                    mixed.copyInto(outputBuffer, 0, 0, n)
                    if (metronomeEnabled && state.value != LooperState.RECORDING) {
                        val beatLength = (sampleRate * 60f / bpm.coerceIn(40f, 220f)).toLong().coerceAtLeast(1)
                        for (i in 0 until n) {
                            val beatPosition = metronomeSample % beatLength
                            if (beatPosition < sampleRate / 180) {
                                val beatNumber = (metronomeSample / beatLength) % 4
                                val envelope = 1f - beatPosition.toFloat() / (sampleRate / 180f)
                                val clickLevel = if (beatNumber == 0L) 12000f else 7500f
                                val click = (envelope * clickLevel).toInt()
                                outputBuffer[i] = (outputBuffer[i].toInt() + click).coerceAtMost(32767).toShort()
                            }
                            metronomeSample++
                        }
                    }
                    track.write(outputBuffer, 0, n, AudioTrack.WRITE_BLOCKING)
                    val current = mixer.tracks[activeSlot]
                    loopSeconds.value = current.samples.size.toFloat() / (sampleRate * 2)
                    loopProgress.value = if (current.samples.isNotEmpty()) current.position.toFloat() / current.samples.size else 0f
                }
            } catch (failure: Exception) {
                error.value = failure.message ?: "Looper error"
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                runCatching { track.stop() }
                track.release()
                running.set(false)
                state.value = LooperState.IDLE
            }
        }, "scarlett-multilooper")
        thread!!.start()
        return true
    }

    fun finishLoop(slot: Int = activeSlot) {
        if (slot !in mixer.tracks.indices) return
        activeSlot = slot
        mixer.tracks[slot].finishRecording()
        state.value = if (mixer.tracks[slot].samples.isNotEmpty()) LooperState.PLAYING else LooperState.IDLE
    }

    fun toggleOverdub(slot: Int = activeSlot) {
        if (slot !in mixer.tracks.indices) return
        activeSlot = slot
        mixer.tracks[slot].toggleOverdub()
        state.value = if (mixer.tracks[slot].state == LoopTrackState.OVERDUBBING) LooperState.OVERDUBBING else LooperState.PLAYING
    }

    fun clear(slot: Int = activeSlot) {
        if (slot !in mixer.tracks.indices) return
        mixer.tracks[slot].clear()
        if (slot == activeSlot) {
            loopSeconds.value = 0f
            loopProgress.value = 0f
            state.value = if (running.get()) LooperState.PLAYING else LooperState.IDLE
        }
    }

    fun stop() {
        running.set(false)
        runCatching { input?.stop() }
        thread?.join(1500)
        thread = null
        input = null
        state.value = LooperState.IDLE
    }
}
