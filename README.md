# Aegis Forensic Auditor

Aegis is a background Android post-call forensic auditor. It does not replace the system dialer, route calls, intercept call audio, or capture from the microphone.

## How it works

1. `CallEndedReceiver` listens for `TelephonyManager.ACTION_PHONE_STATE_CHANGED` and records phone-state transitions.
2. On a non-idle to idle transition, it launches IO work and waits five seconds for the native recorder to flush.
3. `CallRecordingStore` queries shared `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`, ordered by `DATE_ADDED DESC`, and returns the newest recording URI and path.
4. `InferenceEngine` stages `model_q4f16.onnx` only for the audit, analyzes the recording stream, and closes the ONNX session immediately.
5. A high-priority notification reports the synthetic-voice confidence result.

## Permissions

The app requests `READ_PHONE_STATE`, `READ_MEDIA_AUDIO` on Android 13 and newer (or `READ_EXTERNAL_STORAGE` on older devices), and `POST_NOTIFICATIONS` on Android 13 and newer. It does not request microphone, call-routing, Telecom, call-log, overlay, or foreground-service permissions.

## Build

```bash
gradle :app:assembleDebug
```

The quantized model is stored at `app/src/main/assets/model_q4f16.onnx` and is not kept resident between audits.
