# Aegis-Dialer

Aegis-Dialer is a local-first Android dialer and synthetic-voice risk detector. It combines Android Telecom integration, an on-device ONNX Runtime inference pipeline, a native-style call experience, and a local contacts call book.

The project follows one privacy rule: audio and inference data stay on the device. The application does not upload call audio, model inputs, model outputs, contacts, call metadata, or telemetry to a cloud service.

## What It Does

- Dial pad for entering and placing phone calls.
- Searchable contacts call book backed by Android Contacts Provider.
- Draggable A-Z contact navigation rail.
- Default dialer role registration through Android `RoleManager`.
- Android `InCallService` integration for Telecom call state.
- Active-call screen with answer, reject, hang-up, mute, speaker, and hold controls.
- Incoming and active-call notifications with call actions.
- Local PCM capture service started when a Telecom call becomes active.
- Sliding-window ONNX Runtime inference bundled in the APK.
- Rolling detection rule requiring three consecutive high-risk windows.
- Optional warning overlay for high-confidence synthetic-voice detection.

This is an installable Android prototype. It is not a replacement for the carrier, modem, or Android Telecom stack, and it cannot bypass Android or OEM restrictions on call-audio capture.

## Architecture

```text
User places or receives a call
              |
              v
Android Telecom Framework
              |
              +--> AegisInCallService
              |        |
              |        +--> CallActivity
              |        +--> Call notification actions
              |        +--> AudioCaptureService on STATE_ACTIVE
              |
              v
AudioRecord at 16 kHz mono PCM
              |
              v
Ring buffer: 1.5 s window, 0.75 s hop
              |
              v
ONNX Runtime Mobile session
              |
              v
Two-class logits -> spoof probability
              |
              v
Three consecutive scores >= 0.85
              |
              v
Warning overlay and local notification
```

### Modules

| Module | Location | Responsibility |
| --- | --- | --- |
| Main dialer UI | `app/src/main/java/com/aegis/dialer/MainActivity.kt` | Minimal landing screen, keypad, call book, role controls, permissions, and outgoing calls. |
| Telecom integration | `telecom/TelecomConnectionService.kt` | Default dialer role request, `InCallService`, call lifecycle, and call notifications. |
| Call session | `telecom/CallSession.kt` | Shares the active Telecom call between services, activity, and notification actions. |
| Call activity | `telecom/CallActivity.kt` | Incoming and active-call controls. |
| Notification actions | `telecom/CallActionReceiver.kt` | Answer, reject, hang-up, and open-call actions. |
| Audio capture | `telecom/AudioCaptureService.kt` | Foreground service, `AudioRecord`, ring buffer, wakelock, and inference scheduling. |
| Feature extraction | `ml/FeatureExtractor.kt` | Six-value legacy feature vector for compatible models. |
| Inference | `ml/InferenceEngine.kt` | Model staging, ONNX Runtime configuration, tensor creation, and probability conversion. |
| Alert overlay | `ui/OverlayAlertManager.kt` | Optional high-risk warning overlay. |
| Shared data | `data/AudioModels.kt` | PCM ring buffer and `VoiceRiskScore`. |

## User Experience

### Landing screen

The default screen is intentionally minimal. It shows the title and four bottom actions:

- **Keypad** opens the keypad. Pressing it again closes the keypad and returns to the blank landing state.
- **Call book** opens contacts. Pressing it again closes the call book and returns to the blank landing state.
- **Set as default** launches Android's default-dialer role request.
- **Default apps** opens Android's default-app settings.

### Keypad

The keypad supports digits `0-9`, `*`, and `#`. **Call** launches an `ACTION_CALL` intent after checking `CALL_PHONE`. **Backspace** removes the final character.

Selecting a contact fills the keypad and switches back to keypad mode. Incoming `tel:` intents also populate the number and open keypad mode.

### Call book

The call book reads phone rows from `ContactsContract.CommonDataKinds.Phone`. It supports name search, number search, full vertical scrolling, an A-Z drag rail, and tapping a contact to select its number. The current implementation loads up to 30 matching rows per query.

### Active-call experience

When `AegisInCallService` receives a Telecom call, it attaches the call to `CallSession` and opens `CallActivity`. The screen provides answer/reject while ringing, end call while connected, mute/unmute, speaker/earpiece selection, and hold/resume.

The service publishes an ongoing high-importance call notification. Incoming calls expose **Answer** and **Reject**. Active calls expose **End call**. Tapping the notification opens the call activity.

## Audio and Inference Pipeline

### Capture

`AudioCaptureService` is a microphone foreground service. It checks `RECORD_AUDIO`, creates `AudioRecord` with `VOICE_COMMUNICATION`, requests 16,000 Hz mono 16-bit PCM, acquires a partial wakelock, and reads 1,600-sample chunks on a background coroutine.

The application does not claim that `VOICE_COMMUNICATION` always exposes both sides of a carrier call. Android versions, vendors, carriers, Bluetooth routes, and privacy policies can restrict the available audio route.

### Windowing

The ring buffer holds two inference windows. Each window contains 16,000 samples. A new snapshot is scheduled every 8,000 samples, giving 50% overlap.

```text
Sample rate:                 16,000 Hz
Channels:                    1
PCM encoding:                16-bit
Capture chunk:               1,600 samples
Inference window:            16,000 samples
Inference hop:                8,000 samples
Risk threshold:                  0.85
Required consecutive hits:        3
```

Inference runs on a dedicated single-thread executor. If an earlier inference is still running, the next window is dropped instead of creating an unbounded queue that could starve audio capture.

### Active model contract

The active model is `model_q4f16.onnx`:

```text
Input:  input_values, float32, [batch_size, sequence_length]
Output: logits,       float32, [batch_size, 2]
```

The app sends raw normalized PCM samples. The two output values are converted with a numerically stable two-class softmax. Class 1 is treated as the synthetic/spoof probability.

`InferenceEngine` also supports a legacy `[1, 6]` feature-vector input for compatible models, but the current Q4 model uses raw waveform input.

### ONNX Runtime optimization

The session is configured for sequential execution, full graph optimization, two intra-op threads, one inter-op thread, CPU arena allocation, and memory-pattern optimization. The model is copied from APK assets into the app's private cache before session creation so the app does not allocate the entire model as a Kotlin `ByteArray`.

## Detection and Alerting

Each inference produces a `VoiceRiskScore` with probability, classification state, window size, and model name.

```text
if probability >= 0.85:
    consecutiveWindows += 1
else:
    consecutiveWindows = 0

alert when consecutiveWindows >= 3
```

The overlay warning is:

```text
WARNING: AI Voice Cloning / Deepfake Detected on Call
Confidence <percentage>%
```

The overlay requires the user to grant `SYSTEM_ALERT_WINDOW`. Without that special access, local inference can still run but the overlay is skipped.

## Permissions

| Permission | Purpose |
| --- | --- |
| `RECORD_AUDIO` | Reads the permitted microphone/telecom audio route. |
| `READ_PHONE_STATE` | Reads phone state needed by call workflows. |
| `READ_CONTACTS` | Loads the contacts call book. |
| `CALL_PHONE` | Places outgoing calls from the keypad. |
| `READ_CALL_LOG` | Reserved for call-history integration and dialer compatibility. |
| `WRITE_CALL_LOG` | Reserved for dialer compatibility and future call-history ownership. |
| `MANAGE_OWN_CALLS` | Declares Telecom ownership capability. |
| `POST_NOTIFICATIONS` | Allows call and capture notifications on Android 13+. |
| `SYSTEM_ALERT_WINDOW` | Allows the optional warning overlay. This is a special settings permission. |
| `FOREGROUND_SERVICE` | Allows foreground services. |
| `FOREGROUND_SERVICE_MICROPHONE` | Identifies the microphone foreground service type. |

The app requests dangerous runtime permissions together on first launch. Android may display separate dialogs depending on the device permission controller. Overlay access must be enabled manually in system settings.

## Default Dialer Role

Phone permissions alone do not grant full call-management access. For the complete Telecom workflow, Aegis must be selected as the device's default phone app.

The manifest contains both data and no-data variants of `ACTION_DIAL` and `ACTION_CALL`. The no-data variants are important on some OEM devices because their default-phone picker checks those resolver entries when building the eligible list.

Recommended setup:

1. Open Aegis-Dialer.
2. Grant call, contacts, microphone, and notification permissions.
3. Tap **Set as default**.
4. If the OEM dialog closes or does not list Aegis, open **Default apps**.
5. Select **Phone app** or **Dialer app**.
6. Choose **Aegis-Dialer**.
7. Enable **Display over other apps** in Aegis settings if overlay alerts are required.

OEM behavior differs. Some Vivo builds dismiss the `RoleManager` request screen even when the app is eligible. The manual Default Apps path is the fallback.

## Build Requirements

- Android Studio Ladybug or newer.
- Android SDK 34.
- JDK 17.
- Kotlin 1.9.24.
- Android Gradle Plugin 8.5.2.
- Gradle 9.7.1 or a compatible installed Gradle.
- A physical device or emulator with Telecom support.
- A supported telephony/audio route for real call testing.

This project currently has no Gradle wrapper. Use the installed `gradle` command unless you add a wrapper locally.

## Build, Lint, and Install

From the repository root:

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
gradle :app:compileDebugKotlin
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install and launch it on a connected device:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aegis.dialer/.MainActivity
```

Useful smoke checks:

```bash
adb shell cmd package query-activities --brief -a android.intent.action.DIAL
adb shell cmd package query-activities --brief -a android.intent.action.CALL
adb shell cmd role get-role-holders android.app.role.DIALER
adb logcat -c
adb logcat -v time | grep -E 'Aegis|AudioCapture|AndroidRuntime|FATAL'
```

The keypad and contacts UI can be tested without placing a call. An end-to-end Telecom test requires a second authorized phone, a test SIM, voicemail, or another controlled endpoint.

## Model Assets

| Asset | Role |
| --- | --- |
| `model_q4f16.onnx` | Active APK model. Float waveform input and two logits. |
| `model_bnb4(1).onnx` | Alternate two-logit candidate. Not selected. |
| `aegis_voice_detector.int8.onnx` | Generated dynamic INT8 candidate. Not selected. |
| `aegis_voice_detector.onnx` | Original large FP32 source model. Preserved for reference. |
| `model.onnx` | Non-classifier candidate with a different output contract. Not used. |

The Gradle asset ignore pattern excludes the larger non-active candidates from the APK. Only `model_q4f16.onnx` is intended to ship in the current build.

## Quantization Workflow

Python 3.13 is recommended:

```bash
/usr/bin/python3.13 -m venv .venv313
.venv313/bin/python -m pip install --upgrade pip
.venv313/bin/python -m pip install onnx onnxruntime
.venv313/bin/python tools/quantize_model.py
```

The script reads `aegis_voice_detector.onnx`, performs dynamic per-channel INT8 quantization, restricts conversion to compatible `MatMul` and `Gemm` operators, writes `aegis_voice_detector.int8.onnx`, and validates the output without overwriting the source.

Size reduction does not guarantee mobile latency or accuracy. Benchmark on the target phone and compare against a held-out validation set before changing `MODEL_ASSET`.

## Performance Notes

The active Q4 model successfully returned two logits through ONNX Runtime. Linux CPU testing measured approximately 667 ms warm inference per 16,000-sample window. This is above the original 100 ms target.

The Android path avoids blocking audio capture by using a dedicated inference executor and dropping overlapping jobs. That prevents capture-thread starvation, but it does not make the model itself faster. Meeting a strict sub-100 ms budget requires a smaller architecture, such as a compact 1D CNN or distilled audio classifier, and validation on the target phone.

## Privacy and Security

- No network permission is declared.
- Audio is processed in memory and passed only to the local ONNX Runtime session.
- The model is bundled locally in APK assets.
- The staged model is copied into the app's private cache directory.
- The audio service is not exported.
- Call actions are handled by a non-exported receiver.
- Overlay alerts are opt-in through Android special app access.
- Android Telecom and audio-routing restrictions are respected.

## Known Limitations

1. **Call-audio availability is device-dependent.** `AudioRecord` may receive microphone or voice-communication audio rather than both sides of a carrier call.
2. **The default dialer role is required for the full Telecom workflow.** Without it, outgoing calls can be delegated to the system phone app, but Aegis may not receive active-call callbacks.
3. **The current model is too slow for the original target.** Quantization reduces storage, but the transformer-style model remains expensive.
4. **The detector is a risk signal, not a forensic verdict.** A model score is not proof that speech is synthetic.
5. **The call book loads up to 30 matching rows per query.** Paging and better indexing are future improvements.
6. **Call controls depend on Telecom state.** The UI cannot control a call Android has not delivered to Aegis's `InCallService`.
7. **Overlay warnings require special access.** Without it, the overlay cannot draw above another call UI.
8. **Real call testing needs a controlled endpoint.** Use a second authorized phone, test number, or voicemail, and do not test against another person's phone without permission.

## Project Status

The project currently has a functional dialer UI, contacts search and selection, Telecom role and call lifecycle integration, active-call controls and notifications, local model loading, audio capture, rolling threshold logic, debug APK packaging, lint, and device smoke-test workflows.

The next engineering priorities are model distillation, measured on-device latency, broader Telecom/OEM testing, accuracy evaluation, and production-grade contacts indexing.
