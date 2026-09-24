---
language:
- en
- hi
- ta
- te
- ml
tags:
- deepfake-audio-detection
- audio-classification
- wav2vec2
- onnx
- voice-anti-spoofing
license: mit
---

# Dhwani: Multilingual Deepfake Audio Detection Model 🎙️

Dhwani is a robust, multilingual machine learning model designed to detect AI-generated deepfake audio, synthetic voice cloning, and audio spoofing. It is optimized for production deployment via ONNX Runtime.

## 🧠 Model Details
- **Architecture:** Hybrid Neural Network
  - **Front-End:** Facebook's [Wav2Vec2 XLS-R (300M)](https://huggingface.co/facebook/wav2vec2-xls-r-300m) (Self-Supervised Feature Extractor)
  - **Back-End:** AASIST (Audio Anti-Spoofing using Integrated Spectro-Temporal Graph Attention)
- **Format:** ONNX (`best_model.onnx`)
- **Supported Languages:** English, Hindi, Tamil, Telugu, Malayalam
- **Audio Input:** 16kHz, Mono, Float32 (max 3-second window / 48,000 samples)

## 🎯 Intended Use
- **Primary Use Case:** Detecting synthetic/AI-generated audio and voice cloning.
- **Secondary Use Case:** Analyzing audio integrity and filtering out spoofed inputs in voice-authentication systems.
- **Out of Scope:** This model is not intended for speech-to-text transcription or language translation.

## 📊 Training Data
The model was trained on a highly diverse, custom multilingual dataset comprising both genuine human speech and generative AI spoofs:
- **Real Audio:** Extracted from the **Mozilla Common Voice (v24.0)** dataset (Indic languages) and `garystafford/deepfake-audio-detection` (English).
- **Fake Audio:** Synthetic Text-to-Speech (TTS) generated using `vdivyasharma/IndicSynth` (Indic languages) and `garystafford/deepfake-audio-detection` (English).
- **Augmentations Applied:** 
  - Random Gaussian Noise Injection (SNR 10-20dB)
  - Speed & Pitch Perturbations (0.9x and 1.1x resampling to simulate telephony/transmission artifacts)

## 🚀 How to Use (ONNX)
Because this model is exported in ONNX format, it is extremely fast and lightweight. You do not need PyTorch installed to run inference.

```python
import onnxruntime as ort
import numpy as np
import librosa

# 1. Load the ONNX model
session = ort.InferenceSession("best_model.onnx")

# 2. Preprocess the audio (Ensure 16kHz Mono)
audio_path = "sample.wav"
y, sr = librosa.load(audio_path, sr=16000, mono=True)

# 3. Pad or truncate to exactly 48,000 samples (3 seconds)
max_len = 48000
if len(y) > max_len:
    y = y[:max_len]
else:
    y = np.pad(y, (0, max_len - len(y)), mode='constant')

# 4. Normalize the audio
y = (y - np.mean(y)) / np.sqrt(np.var(y) + 1e-5)
y = y.astype(np.float32).reshape(1, max_len)

# 5. Run Inference
input_name = session.get_inputs()[0].name
logits = session.run(None, {input_name: y})[0]

# 6. Interpret Results (Binary Classification)
probs = np.exp(logits) / np.sum(np.exp(logits), axis=1, keepdims=True)
fake_probability = probs[0][1]

print(f"Deepfake Probability: {fake_probability * 100:.2f}%")
```

## ⚠️ Limitations & Bias
- **Language Bias:** While trained on 5 distinct languages, the model may perform slightly worse on heavily accented variations of languages not heavily represented in the Mozilla Common Voice dataset.
- **Unseen TTS Engines:** Generative AI evolves rapidly. The model is highly accurate against the TTS engines used in its training distribution but may exhibit lower confidence against entirely novel state-of-the-art zero-shot voice cloning architectures.
- **Audio Quality:** Heavy background noise exceeding the SNR 10-20dB threshold used during training may lead to false positives.

## 📝 Citation & Authors
Originally developed as "Dhwani" during the HCL Guvi Hackathon. 
