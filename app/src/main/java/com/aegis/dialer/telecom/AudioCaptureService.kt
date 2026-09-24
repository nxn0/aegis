package com.aegis.dialer.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aegis.dialer.data.PcmRingBuffer
import com.aegis.dialer.data.VoiceRiskScore
import com.aegis.dialer.ml.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val inferenceInFlight = AtomicBoolean(false)
    private val inferenceLock = Any()
    private var pendingWindow: FloatArray? = null
    private var captureJob: Job? = null
    private var recorder: AudioRecord? = null
    @Volatile
    private var engine: InferenceEngine? = null
    private val engineLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var consecutiveSyntheticWindows = 0
    private var samplesSinceInference = 0
    private val ringBuffer = PcmRingBuffer(INFERENCE_WINDOW_SAMPLES * 2)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching { startForeground(NOTIFICATION_ID, notification()) }
            .onFailure { error ->
                Log.e(TAG, "Unable to promote capture service to foreground", error)
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        if (captureJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_MASK,
            ENCODING
        )
        if (minBuffer <= 0) {
            stopSelf()
            return
        }
        recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                maxOf(minBuffer, CHUNK_SAMPLES * 2)
            )
        }.onFailure {
            Log.e(TAG, "Audio capture is unavailable on this device", it)
        }.getOrNull()
        val activeRecorder = recorder ?: return
        if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized; device policy may restrict call capture")
            stopCapture()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:call-analysis")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        inferenceExecutor.execute { getEngine() }
        captureJob = serviceScope.launch {
            val chunk = ShortArray(CHUNK_SAMPLES)
            try {
                activeRecorder.startRecording()
                while (isActive) {
                    val read = activeRecorder.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        ringBuffer.write(chunk, read)
                        samplesSinceInference += read
                        if (samplesSinceInference >= HOP_SAMPLES) {
                            samplesSinceInference -= HOP_SAMPLES
                            ringBuffer.snapshot(INFERENCE_WINDOW_SAMPLES)?.let { window ->
                                submitInference(window)
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read failed: $read")
                        break
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Audio capture stopped by the platform", error)
                stopCapture()
            }
        }
    }

    private fun submitInference(window: FloatArray) {
        synchronized(inferenceLock) {
            pendingWindow = window
            if (!inferenceInFlight.compareAndSet(false, true)) return
        }
        inferenceExecutor.execute {
            while (true) {
                val nextWindow = synchronized(inferenceLock) {
                    pendingWindow?.also { pendingWindow = null }
                        ?: run {
                            inferenceInFlight.set(false)
                            return@execute
                        }
                }
                runCatching { getEngine()?.classify(nextWindow) }
                    .onFailure { Log.e(TAG, "Local inference failed", it) }
                    .getOrNull()
                    ?.let(::publishScore)
            }
        }
    }

    private fun getEngine(): InferenceEngine? {
        engine?.let { return it }
        return synchronized(engineLock) {
            engine ?: runCatching { InferenceEngine(applicationContext) }
                .onFailure { Log.e(TAG, "Detector model initialization failed", it) }
                .getOrNull()
                ?.also { engine = it }
        }
    }

    private fun publishScore(score: VoiceRiskScore) {
        consecutiveSyntheticWindows = if (score.probability >= SYNTHETIC_THRESHOLD) {
            consecutiveSyntheticWindows + 1
        } else {
            0
        }
        scoreListener?.invoke(score.copy(isSynthetic = consecutiveSyntheticWindows >= REQUIRED_CONSECUTIVE_WINDOWS))
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        inferenceExecutor.shutdownNow()
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        samplesSinceInference = 0
        consecutiveSyntheticWindows = 0
        synchronized(inferenceLock) { pendingWindow = null }
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        engine?.close()
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aegis call protection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Aegis-Dialer active")
        .setContentText(
            "Local voice authenticity analysis is starting"
        )
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
        .setOngoing(true)
        .build()

    companion object {
        const val ACTION_START = "com.aegis.dialer.action.START_CAPTURE"
        const val ACTION_STOP = "com.aegis.dialer.action.STOP_CAPTURE"
        private const val CHANNEL_ID = "aegis_capture"
        private const val NOTIFICATION_ID = 7001
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600
        private const val INFERENCE_WINDOW_SAMPLES = 16_000
        private const val HOP_SAMPLES = INFERENCE_WINDOW_SAMPLES / 2
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val SYNTHETIC_THRESHOLD = 0.85f
        private const val REQUIRED_CONSECUTIVE_WINDOWS = 3
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "AudioCaptureService"
        @Volatile var scoreListener: ((VoiceRiskScore) -> Unit)? = null
    }
}
