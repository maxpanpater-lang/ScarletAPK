package de.maxgraue.scarlettrecorder.audio

enum class LoopTrackState { EMPTY, RECORDING, PLAYING, OVERDUBBING }

class LoopTrack {
    @Volatile var state: LoopTrackState = LoopTrackState.EMPTY
        private set
    @Volatile var volume: Float = 1f
    @Volatile var muted: Boolean = false
    @Volatile var solo: Boolean = false
    var samples: ShortArray = ShortArray(0)
        private set
    var position: Int = 0
        private set

    fun beginRecording() {
        samples = ShortArray(0)
        position = 0
        state = LoopTrackState.RECORDING
    }

    fun append(input: ShortArray, count: Int) {
        if (state != LoopTrackState.RECORDING || count <= 0) return
        val next = samples.size + count
        samples = samples.copyOf(next)
        input.copyInto(samples, samples.size - count, 0, count)
    }

    fun finishRecording() {
        if (samples.isNotEmpty()) {
            position = 0
            state = LoopTrackState.PLAYING
        } else {
            state = LoopTrackState.EMPTY
        }
    }

    fun toggleOverdub() {
        if (samples.isNotEmpty()) {
            state = if (state == LoopTrackState.OVERDUBBING) LoopTrackState.PLAYING else LoopTrackState.OVERDUBBING
        }
    }

    fun clear() {
        samples = ShortArray(0)
        position = 0
        state = LoopTrackState.EMPTY
    }

    fun mixInto(output: ShortArray, input: ShortArray, count: Int, overdub: Boolean) {
        if (muted || samples.isEmpty() || state == LoopTrackState.RECORDING) return
        for (i in 0 until count) {
            val index = position
            val loopValue = samples[index].toInt()
            val inputValue = if (overdub && state == LoopTrackState.OVERDUBBING) input[i].toInt() / 2 else 0
            output[i] = (output[i].toInt() + ((loopValue + inputValue) * volume).toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            if (overdub && state == LoopTrackState.OVERDUBBING) samples[index] = output[i]
            position = if (index + 1 == samples.size) 0 else index + 1
        }
    }
}
