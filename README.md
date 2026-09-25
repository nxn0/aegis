# Aegis

Aegis is an Android app that checks a completed phone-call recording for signs of a synthetic or AI-generated voice.

It is a post-call checker. Aegis does not record calls, listen through the microphone, replace the phone app, route calls, or inspect live audio during a call.

## For Users

### What Aegis does

After a call ends, Aegis looks for the newest audio recording already saved on the phone. It reads up to the first ten seconds of that recording and runs the local voice detector model on the device.

You then receive notifications showing:

1. Aegis is picking up the recorded voice.
2. Aegis is running the voice check.
3. The final result and score.

The current decision rule is:

- Below 60%: Aegis reports synthetic or AI voice detected.
- 60% or higher: Aegis reports human voice detected.

The score is a detector score. It is not a guarantee, a legal finding, or proof of who made a call. Results can be affected by recording quality, background noise, silence, compression, the phone's call-recording implementation, and the model's training data.

### What Aegis does not do

- It does not start monitoring when permissions are granted.
- It does not start monitoring when the phone boots.
- It does not record from the microphone.
- It does not intercept, route, or modify calls.
- It does not upload recordings or scores to a server.

### Install the release APK

1. Download the Aegis release APK.
2. Open the APK on the Android phone.
3. Allow installation from the source used to open the APK if Android asks.
4. Install Aegis.
5. Open Aegis and grant the requested permissions.

The application ID is `com.aegis.eidolon`.

### Start Aegis

Permissions alone do not start monitoring.

1. Open Aegis.
2. Grant the requested permissions.
3. Press **Start running in background**.
4. Keep the persistent **Aegis active** notification visible while you want Aegis to monitor call state.

### Stop Aegis

Open Aegis and press **Stop running in background**. Aegis then stops its foreground service and stops monitoring call state.

Force-stopping the app, uninstalling it, or some manufacturer battery-management settings can also stop the service. Aegis does not automatically restart after boot or after permissions are granted.

### Permissions

Aegis requests:

- **Phone state:** notices when a phone call changes state and ends.
- **Audio or media access:** finds and reads the existing call recording through Android's media database.
- **Notifications:** shows progress and result notifications on Android versions that require notification permission.

Aegis does not request microphone, call-log, contacts, overlay, or call-routing permissions.

### If no result appears

Check these items:

- Aegis is running and its **Aegis active** notification is visible.
- The phone's system dialer actually saved a recording.
- The recording appears in the phone's audio/media library.
- Aegis has audio/media and notification permissions.
- The phone has not restricted Aegis with battery-saving settings.
- The recording is available after the call ends. Some phones take several seconds to finish saving it.


## Technical Architecture

### Machine-learning model

Aegis uses a pretrained deepfake-audio classifier rather than a hand-written collection of audio rules. The model is packaged locally as `app/src/main/assets/model_q4f16.onnx`, so the recording and the inference result stay on the device.

The model was sourced from the Hugging Face repository [ai8shiro/deepfake-audio-wav2vec2-ONNX](https://huggingface.co/ai8shiro/deepfake-audio-wav2vec2-ONNX). Credit goes to the model authors and maintainers there. That repository describes its model as an ONNX conversion of [Vansh180/deepfake-audio-wav2vec2](https://huggingface.co/Vansh180/deepfake-audio-wav2vec2), which is based on `facebook/wav2vec2-base`. The upstream model card reports training for binary audio classification on a balanced subset of the ASVspoof 2021 PA dataset, with `real`/`fake` (also described as bonafide/spoof) labels.

#### What Wav2Vec2 is

Wav2Vec2 is a self-supervised speech representation architecture. It accepts raw audio samples instead of a manually engineered spectrogram feature vector and learns useful speech representations in several stages:

1. A stack of strided one-dimensional convolution layers turns the waveform into a shorter sequence of local acoustic features. This reduces the time resolution while retaining information such as timbre, phonetic structure, and speech texture.
2. A Transformer encoder processes that feature sequence with self-attention. Each position can use context from the surrounding audio, allowing the model to represent longer-range relationships rather than judging each sample independently.
3. For this task, a sequence-classification head pools the learned representation and produces two class outputs for real versus fake audio.

The model configuration describes a 7-layer convolutional feature extractor followed by a 12-layer Transformer with 12 attention heads and a 768-wide hidden representation. The expected input is normalized mono waveform audio sampled at 16 kHz. The classifier does not identify a speaker, transcribe words, or prove that a recording was generated by a particular tool. It looks for acoustic patterns associated with the real/fake examples represented in its training data.

#### ONNX and quantization

ONNX is a portable format for representing a neural-network computation graph. The model can therefore be executed by ONNX Runtime without shipping the original Python/PyTorch training stack inside the Android app. Aegis uses `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` and runs the model with the CPU execution provider.

The `model_q4f16.onnx` filename indicates the app's quantized Q4F16 asset: weights are represented at reduced precision while computation uses 16-bit floating-point intermediates where supported by the exported graph. Quantization makes an on-device model smaller and can reduce memory and compute cost, but it can also change scores slightly compared with the original full-precision checkpoint. The app treats the output as a detector score, not as a calibrated probability.

#### How Aegis classifies a recording

The classification path is:

1. `AudioDecoder` uses Android `MediaExtractor` and `MediaCodec` to decode the selected recording, mixes channels to mono, and resamples it to 16,000 samples per second.
2. Aegis limits analysis to the first ten seconds, which is at most 160,000 samples.
3. `InferenceEngine` divides those samples into 16,000-sample (one-second) windows with an 8,000-sample (half-second) hop. Windows whose RMS energy is below the voice-activity floor are skipped so silence is less likely to dominate the result.
4. Each remaining waveform window is passed directly to the ONNX model. The normal active model path uses waveform input; the six-number `FeatureExtractor` path is only a compatibility fallback for a model that explicitly declares a `[1, 6]` input.
5. The engine converts a one-value output into a probability-like score when necessary, or converts two output logits with softmax. It takes the median across analyzed windows, reducing the influence of one unusually noisy, silent, or anomalous segment.
6. Aegis applies its application threshold: scores below `0.60` (60%) are reported as synthetic and scores at or above `0.60` are reported as human. This is the app's current decision rule, not a threshold guaranteed by Wav2Vec2 or by the upstream model card.

This means the final notification is a binary application decision built from several stages: decoded audio, preprocessing, multiple neural-network evaluations, aggregation, and a threshold. It is not a forensic certificate. Call codecs, background noise, short speech, reverberation, clipping, language or speaker mismatch, and synthetic voices unlike the training examples can all affect the score. The upstream model's published benchmark numbers should not be read as a guarantee of the same performance on phone-call recordings.

The ONNX file is staged into the app cache only while an audit is running. A temporary `OrtSession` is configured with sequential execution, basic graph optimizations, two intra-operation CPU threads, and one inter-operation thread. When the audit ends, `InferenceEngine.close()` closes the session and deletes the staged model copy.

### Application identity

- Package and application ID: `com.aegis.eidolon`
- Kotlin namespace: `com.aegis.eidolon`
- Minimum Android version: API 29
- Target Android version: API 34
- UI: Jetpack Compose and Material 3
- Inference runtime: ONNX Runtime for Android
- Model: `app/src/main/assets/model_q4f16.onnx`

### Main components

#### `MainActivity`

File: `app/src/main/java/com/aegis/eidolon/MainActivity.kt`

`MainActivity` owns the small user interface and explicit service consent flow.

It:

- Requests phone-state, audio/media, and notification permissions.
- Shows whether required permissions are available.
- Checks whether `AuditorService` is actually running.
- Starts the foreground service only when the user presses **Start running in background**.
- Stops the service and clears the enabled preference when the user presses **Stop running in background**.
- Applies the peach and dusty-pink Material color scheme.

Granting permissions does not start the service. Opening the app does not start the service.

#### `AuditorService`

File: `app/src/main/java/com/aegis/eidolon/audit/AuditorService.kt`

`AuditorService` is the opt-in backend process. It is declared as an Android foreground service with the `dataSync` service type.

When started, it:

1. Creates the Aegis foreground notification channel.
2. Posts the persistent **Aegis active** notification.
3. Registers a phone-state listener.
4. Waits for a transition from an active call state to idle.
5. Schedules the post-call audit on an IO coroutine after a five-second recording-flush delay.

The five-second delay gives the system dialer time to finish writing the recording.

When stopped, it unregisters the phone-state listener and cancels its coroutine scope. The service uses `START_STICKY` while the user has enabled it, but Android and the user can still stop it. The UI reports the actual running service state rather than trusting only a saved preference.

#### `CallRecordingAuditor`

File: `app/src/main/java/com/aegis/eidolon/audit/CallRecordingAuditor.kt`

This class coordinates one completed-call audit.

It:

1. Asks `CallRecordingStore` for the newest audio item.
2. Skips the item if its URI was already analyzed.
3. Posts the pickup and inference progress notifications.
4. Uses `AudioDecoder` to decode up to ten seconds of audio.
5. Creates `InferenceEngine` only after decoded audio is available.
6. Runs the detector and posts the final result notification.
7. Stores the analyzed URI so the same recording is not repeatedly processed.

Decoder and model failures are logged with `Log.e` and reported through a **Voice analysis unavailable** notification.

#### `CallRecordingStore`

File: `app/src/main/java/com/aegis/eidolon/audit/CallRecordingStore.kt`

`CallRecordingStore` queries `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, filters out pending media, sorts by `DATE_ADDED DESC`, and returns the newest audio URI.

It does not search every folder manually and does not create recordings. The phone's dialer or recording app must save the recording into a media location visible to `MediaStore`.

#### `AudioDecoder`

File: `app/src/main/java/com/aegis/eidolon/ml/AudioDecoder.kt`

`AudioDecoder` converts the recording from its stored media format into model-ready mono PCM samples.

It:

- Uses `MediaExtractor` to find the audio track.
- Uses `MediaCodec` to decode formats supported by the Android device.
- Mixes multiple channels down to mono.
- Limits decoding to the requested first ten seconds.
- Resamples the audio to 16 kHz, the detector's expected sample rate.

The model is not loaded while this step is running.

#### `InferenceEngine`

File: `app/src/main/java/com/aegis/eidolon/ml/InferenceEngine.kt`

`InferenceEngine` owns one temporary ONNX Runtime session.

When constructed, it:

1. Copies `model_q4f16.onnx` from the APK assets into the app cache if needed.
2. Opens the ONNX Runtime session.
3. Detects the model input shape.

When classifying, it:

- Splits the decoded audio into 16,000-sample windows.
- Uses an 8,000-sample hop between windows.
- Ignores windows below the minimum RMS voice level.
- Runs inference on the remaining windows.
- Uses the median score to reduce the effect of silence and outlier windows.
- Applies the 60% threshold, where scores below 60% are synthetic and scores at or above 60% are human.

The caller closes `InferenceEngine` after each audit. Closing the engine closes the ONNX session and deletes the staged model file from the cache.

#### `FeatureExtractor`

File: `app/src/main/java/com/aegis/eidolon/ml/FeatureExtractor.kt`

This helper calculates six basic audio features when a model input requires a six-value feature vector:

- RMS energy
- Peak amplitude
- Mean absolute amplitude
- Crest factor
- Zero-crossing rate
- Log-scaled energy

The active Q4F16 Wav2Vec2-style model accepts waveform input, so the feature-vector path is retained for model compatibility but is not the normal active path for the current asset.

#### `VoiceRiskScore`

File: `app/src/main/java/com/aegis/eidolon/data/AudioModels.kt`

`VoiceRiskScore` carries:

- `probability`: the model-derived score from 0.0 to 1.0
- `isSynthetic`: the result after the 60% decision rule
- `windowSamples`: the number of decoded samples considered
- `modelVersion`: the model asset name used for the audit

## Complete Runtime Flow

```text
User presses Start
        |
        v
MainActivity starts AuditorService
        |
        v
AuditorService posts persistent notification
        |
        v
AuditorService listens for phone-state changes
        |
        v
Call becomes idle after an active call
        |
        v
Progress notification: Picking up voice
        |
        v
Wait five seconds for the recorder to finish
        |
        v
CallRecordingStore finds newest MediaStore audio item
        |
        v
Progress notification: Running inference
        |
        v
AudioDecoder reads first ten seconds and resamples to 16 kHz mono
        |
        v
InferenceEngine loads model, scores voice windows, then closes
        |
        v
Result notification: synthetic voice or human voice
```

## Privacy Boundaries

- Monitoring is opt-in through the Start button.
- Monitoring is stopped through the Stop button.
- There is no boot receiver and no automatic startup after permission approval.
- The app does not request microphone access.
- Audio is processed locally on the Android device.
- No network endpoint is used for recording analysis.
- Only the newest visible `MediaStore` audio item is considered.
- Only the first ten seconds are decoded for inference.
- The temporary model copy is removed when inference closes.

## Build From Source

The project uses the system Gradle installation. From the repository root:

```bash
gradle :app:assembleDebug
```

Install the debug APK on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch Aegis:

```bash
adb shell am start -n com.aegis.eidolon/.MainActivity
```

The release APK can be distributed directly to users. Users do not need Gradle, Android Studio, or ADB to install or use the release APK.

## Known Limitations

- Android phone manufacturers do not all save call recordings in the same location or format.
- A recording must be visible through `MediaStore` for Aegis to find it.
- The service must be running before the call state changes occur.
- Android battery-management settings can stop background services.
- The model score is not a calibrated probability and should not be treated as certainty.
