package com.aegis.eidolon.ml

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class FeatureExtractor(private val frameSize: Int = 16000) {
    fun extract(samples: FloatArray): FloatArray {
        require(samples.size >= frameSize) { "A complete inference window is required" }
        val window = samples.takeLast(frameSize)
        var energy = 0.0
        var zeroCrossings = 0
        var previous = window.firstOrNull() ?: 0f
        window.forEach { sample ->
            energy += sample * sample
            if ((sample >= 0f) != (previous >= 0f)) zeroCrossings++
            previous = sample
        }
        val rms = sqrt(energy / window.size).toFloat()
        val peak = window.maxOf { abs(it) }
        val meanAbs = window.sumOf { abs(it).toDouble() }.toFloat() / window.size
        val crest = if (rms > 0f) peak / rms else 0f
        val zcr = zeroCrossings.toFloat() / window.size
        return floatArrayOf(
            rms,
            peak,
            meanAbs,
            crest,
            zcr,
            ln(1f + energy.toFloat())
        )
    }
}
