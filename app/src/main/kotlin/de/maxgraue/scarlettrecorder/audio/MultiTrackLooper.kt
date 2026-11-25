package de.maxgraue.scarlettrecorder.audio

class MultiTrackLooper(private val trackCount: Int = 4) {
    val tracks: List<LoopTrack> = List(trackCount) { LoopTrack() }
    var masterVolume: Float = 1f

    fun mix(input: ShortArray, count: Int, overdubTrack: Int? = null): ShortArray {
        val output = ShortArray(count)
        val hasSolo = tracks.any { it.solo && it.samples.isNotEmpty() }
        tracks.forEachIndexed { index, track ->
            if (!hasSolo || track.solo) {
                val overdub = overdubTrack == index
                track.mixInto(output, input, count, overdub)
            }
        }
        if (masterVolume != 1f) {
            for (i in output.indices) output[i] = (output[i] * masterVolume)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    fun clearAll() = tracks.forEach { it.clear() }
}
