package com.aegis.eidolon.ml

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

class AudioDecoder(private val context: Context) {
    fun decode(uri: Uri, maxSamples: Int): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The recording has no audio track")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("The audio track has no MIME type")
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sourceSampleLimit = (maxSamples.toLong() * sampleRate / TARGET_SAMPLE_RATE).toInt()
        val codec = MediaCodec.createDecoderByType(mime)
        val decoded = ArrayList<Float>()
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            decodePcm(extractor, codec, decoded, channelCount, sourceSampleLimit)
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return resample(decoded.toFloatArray(), sampleRate, TARGET_SAMPLE_RATE)
    }

    private fun decodePcm(
        extractor: MediaExtractor,
        codec: MediaCodec,
        decoded: MutableList<Float>,
        channelCount: Int,
        sourceSampleLimit: Int
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        while (!outputEnded && decoded.size < sourceSampleLimit) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        appendPcm(outputBuffer, decoded, channelCount, sourceSampleLimit)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        }
    }

    private fun appendPcm(
        buffer: java.nio.ByteBuffer,
        output: MutableList<Float>,
        channelCount: Int,
        sampleLimit: Int
    ) {
        val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (pcm.remaining() >= channelCount && output.size < sampleLimit) {
            var sample = 0f
            repeat(channelCount) { sample += pcm.get() / Short.MAX_VALUE.toFloat() }
            output += sample / channelCount
        }
    }

    private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate || input.isEmpty()) return input
        val output = FloatArray((input.size.toLong() * targetRate / sourceRate).toInt())
        output.indices.forEach { index ->
            val sourcePosition = index.toDouble() * sourceRate / targetRate
            val left = sourcePosition.toInt().coerceAtMost(input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            output[index] = (input[left] + (input[right] - input[left]) * (sourcePosition - left)).toFloat()
        }
        return output
    }

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
    }
}