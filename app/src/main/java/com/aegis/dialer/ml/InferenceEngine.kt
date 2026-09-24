package com.aegis.dialer.ml

import android.content.Context
import com.aegis.dialer.data.VoiceRiskScore
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.InputStream
import java.io.File

class InferenceEngine(
    context: Context,
    private val modelAsset: String = MODEL_ASSET,
    private val syntheticThreshold: Float = 0.85f
) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val featureExtractor = FeatureExtractor()
    private val selectedModelAsset = modelAsset
    private val stagedModel = stageModel(context, selectedModelAsset)
    private val inputShape: LongArray

    init {
        val options = OrtSession.SessionOptions()
        try {
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(true)
            options.setCPUArenaAllocator(true)
            session = environment.createSession(stagedModel.absolutePath, options)
        } finally {
            options.close()
        }
        inputName = session.inputNames.firstOrNull()
            ?: error("The ONNX model has no input tensor")
        inputShape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
            ?: error("The ONNX model input must be a tensor")
    }

    fun classify(window: FloatArray): VoiceRiskScore {
        val inputValues = if (expectsFeatureVector()) {
            arrayOf(featureExtractor.extract(window))
        } else {
            arrayOf(window)
        }
        OnnxTensor.createTensor(environment, inputValues).use { input ->
            session.run(mapOf(inputName to input)).use { outputs ->
                val probability = extractProbability(outputs[0].value)
                return VoiceRiskScore(
                    probability = probability,
                    isSynthetic = probability >= syntheticThreshold,
                    windowSamples = window.size,
                    modelVersion = selectedModelAsset
                )
            }
        }
    }

    fun classify(input: InputStream): VoiceRiskScore {
        val bytes = input.use { it.readBytes() }
        val samples = FloatArray(FEATURE_WINDOW_SAMPLES)
        val sampleCount = minOf(bytes.size / 2, samples.size)
        repeat(sampleCount) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort() / Short.MAX_VALUE.toFloat()
        }
        return classify(samples)
    }

    private fun expectsFeatureVector(): Boolean = inputShape.contentEquals(longArrayOf(1L, 6L))

    private fun extractProbability(value: Any): Float {
        val values = flattenOutput(value)
        require(values.size <= 2) {
            "The ONNX output contains ${values.size} values; expected one probability or two-class logits"
        }
        return when (values.size) {
            1 -> values[0].let { if (it in 0f..1f) it else sigmoid(it) }
            2 -> softmaxClassOne(values[0], values[1])
            else -> error("The ONNX model returned no output values")
        }.coerceIn(0f, 1f)
    }

    private fun flattenOutput(value: Any): FloatArray = when (value) {
        is Array<*> -> value.flatMap { flattenOutput(it ?: error("Null ONNX output value")).asList() }.toFloatArray()
        is FloatArray -> value
        is DoubleArray -> value.map(Double::toFloat).toFloatArray()
        is Number -> floatArrayOf(value.toFloat())
        else -> error("Unsupported ONNX output type: ${value::class.java.name}")
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + kotlin.math.exp(-value))

    private fun softmaxClassOne(first: Float, second: Float): Float {
        val maximum = maxOf(first, second)
        val firstExp = kotlin.math.exp(first - maximum)
        val secondExp = kotlin.math.exp(second - maximum)
        return secondExp / (firstExp + secondExp)
    }

    override fun close() {
        session.close()
        stagedModel.delete()
    }

    private fun stageModel(context: Context, assetName: String): File {
        val modelFile = File(context.cacheDir, assetName)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            context.assets.open(assetName).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
        }
        require(modelFile.length() > 0L) { "The detector model asset is empty" }
        return modelFile
    }

    companion object {
        const val MODEL_ASSET = "model_q4f16.onnx"
        private const val FEATURE_WINDOW_SAMPLES = 16_000
        private const val INTRA_OP_THREADS = 2
        private const val COPY_BUFFER_SIZE = 1024 * 1024
    }
}
