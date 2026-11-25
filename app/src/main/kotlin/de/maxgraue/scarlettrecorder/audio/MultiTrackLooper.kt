package de.maxgraue.scarlettrecorder.audio

class MultiTrackLooper(private val trackCount: Int = 4) {
    val tracks: List<LoopTrack> = List(trackCount) { LoopTrack() }
    var masterVolume: Float = 1f
    @Volatile var transportPosition: Int = 0
    @Volatile var masterLength: Int = 0

    fun mix(input: ShortArray, count: Int, overdubTrack: Int? = null): ShortArray {
        val output = ShortArray(count)
        val hasSolo = tracks.any { it.solo && it.samples.isNotEmpty() }
        tracks.forEachIndexed { index, track ->
            if (track.samples.isNotEmpty()) track.alignTo(transportPosition)
            if (!hasSolo || track.solo) {
                val overdub = overdubTrack == index
                track.mixInto(output, input, count, overdub)
            }
        }
        if (masterVolume != 1f) {
            for (i in output.indices) output[i] = (output[i] * masterVolume)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        if (masterLength > 0) transportPosition = (transportPosition + count) % masterLength
        return output
    }

    fun alignTrack(index: Int) { tracks.getOrNull(index)?.alignTo(transportPosition) }

    fun clearAll() { tracks.forEach { it.clear() }; transportPosition = 0; masterLength = 0 }
}
