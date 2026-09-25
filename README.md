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
- It does not analyze the entire recording. Only the first ten seconds are used.

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

The persistent notification means the Android foreground service is active. It is intentional: Android uses it to show that Aegis is running in the background.

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

If decoding or inference fails, Aegis logs the failure and shows a **Voice analysis unavailable** notification instead of silently treating the call as human.

## Technical Architecture

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
- A threshold can classify a test set consistently only if the model has been validated on recordings representative of the target phone and call quality.
