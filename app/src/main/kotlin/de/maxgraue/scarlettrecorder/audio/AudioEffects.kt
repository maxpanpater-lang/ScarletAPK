package de.maxgraue.scarlettrecorder.audio
import kotlin.math.abs
import kotlin.math.max
data class EffectSettings(var eq: Float = 0f, var compressor: Float = 0.35f, var gate: Float = 0.02f)
class AudioEffects {
    val settings = EffectSettings()
    fun process(samples: ShortArray, count: Int) {
        var envelope = 0f
        for (i in 0 until count) {
            val input = samples[i] / 32768f
            envelope = max(envelope * 0.995f, abs(input))
            if (envelope < settings.gate) { samples[i] = 0; continue }
            var output = input * (1f + settings.eq)
            val threshold = 0.55f
            if (abs(output) > threshold) output = kotlin.math.sign(output) * (threshold + (abs(output) - threshold) * (1f - settings.compressor))
            samples[i] = (output.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }
}
