package com.aegis.dialer.data

import kotlin.math.min

class PcmRingBuffer(private val capacity: Int) {
    private val samples = FloatArray(capacity)
    private var writeIndex = 0
    private var size = 0

    @Synchronized
    fun write(input: ShortArray, length: Int) {
        val count = min(length, input.size)
        repeat(count) { index ->
            samples[writeIndex] = input[index] / Short.MAX_VALUE.toFloat()
            writeIndex = (writeIndex + 1) % capacity
        }
        size = min(capacity, size + count)
    }

    @Synchronized
    fun snapshot(length: Int): FloatArray? {
        if (size < length) return null
        val result = FloatArray(length)
        val start = (writeIndex - length + capacity) % capacity
        repeat(length) { index ->
            result[index] = samples[(start + index) % capacity]
        }
        return result
    }
}

data class VoiceRiskScore(
    val probability: Float,
    val isSynthetic: Boolean,
    val windowSamples: Int,
    val modelVersion: String
)
