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
    val error = MutableStateFlow("")
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var input: AudioRecord? = null
    private var loop = ShortArray(0)
    private var loopLength = 0
    @Volatile private var playbackIndex = 0

    fun start(device: AudioDeviceInfo, output: AudioDeviceInfo): Boolean {
        if (running.get()) return false
        val size = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (size <= 0) return false
        val recorder = AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        if (recorder.state != AudioRecord.STATE_INITIALIZED || !recorder.setPreferredDevice(device)) { recorder.release(); return false }
        val track = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(size * 2).build()
        if (!track.setPreferredDevice(output)) { recorder.release(); track.release(); return false }
        input = recorder; running.set(true); recorder.startRecording(); track.play(); state.value = LooperState.RECORDING
        thread = Thread({
            val buffer = ShortArray(size)
            // Keep capture primitive-only. ArrayList<Short> boxes every sample and
            // creates avoidable GC pressure on the real-time thread.
            var captured = ShortArray(sampleRate * 2 * 8)
            var capturedCount = 0
            val output = ShortArray(size)
            var loopPrepared = false
            try {
                while (running.get()) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    if (state.value == LooperState.RECORDING) {
                        if (capturedCount + n > captured.size) {
                            captured = captured.copyOf((captured.size * 1.5f).toInt().coerceAtLeast(capturedCount + n))
                        }
                        buffer.copyInto(captured, capturedCount, 0, n)
                        capturedCount += n
                    }
                    if (state.value != LooperState.RECORDING && !loopPrepared && capturedCount > 0) {
                        loop = captured.copyOf(capturedCount)
                        loopLength = loop.size
                        loopSeconds.value = loopLength.toFloat() / (sampleRate * 2)
                        playbackIndex = 0
                        loopPrepared = true
                    }
                    if ((state.value == LooperState.PLAYING || state.value == LooperState.OVERDUBBING) && loopLength > 0) {
                        for (i in 0 until n) {
                            val index = playbackIndex
                            val mixed = if (state.value == LooperState.OVERDUBBING) loop[index].toInt() + buffer[i].toInt() / 2 else loop[index].toInt()
                            output[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            if (state.value == LooperState.OVERDUBBING) loop[index] = output[i]
                            playbackIndex = if (index + 1 == loopLength) 0 else index + 1
                        }
                        track.write(output, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                }
            } catch (failure: Exception) { error.value = failure.message ?: "Looper error" }
            finally { recorder.stop(); recorder.release(); track.stop(); track.release(); state.value = LooperState.IDLE; running.set(false) }
            if (capturedCount > 0 && !loopPrepared) {
                loop = captured.copyOf(capturedCount)
                loopLength = loop.size
                playbackIndex = 0
                loopSeconds.value = loopLength.toFloat() / (sampleRate * 2)
            }
        }, "scarlett-looper")
        thread!!.start(); return true
    }
    fun finishLoop() { if (state.value == LooperState.RECORDING) state.value = LooperState.PLAYING }
    fun toggleOverdub() { if (loopLength > 0) state.value = if (state.value == LooperState.OVERDUBBING) LooperState.PLAYING else LooperState.OVERDUBBING }
    fun clear() { loop = ShortArray(0); loopLength = 0; playbackIndex = 0; loopSeconds.value = 0f; state.value = LooperState.IDLE }
    fun stop() { running.set(false); input?.stop(); thread?.join(1500); thread = null; input = null; state.value = LooperState.IDLE }
}
