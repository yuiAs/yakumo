# やくも (Yakumo) — EN↔JA Voice Translator

An on-device English↔Japanese voice translator for Android. By default, speech
recognition and machine translation run **fully offline** after a one-time model
download, and speech is spoken back through the OS text-to-speech engine. An
opt-in online mode can route a turn through OpenAI Realtime instead. The inference
core is written in Rust and exposed to a Jetpack Compose UI through UniFFI.

## Features

- Offline ASR → translation → speech pipeline, no network at inference time
- Automatic language detection (SenseVoice multilingual model)
- VAD-based turn segmentation with tunable thresholds
- Opt-in low-latency **streaming** English ASR (Nemotron)
- Spoken output via the OS TTS engine, with variable playback rate
- Optional **online mode** (OpenAI Realtime speech-to-speech) — see below

## Building

### Prerequisites

Native libraries are compiled/fetched during the Gradle build, so the toolchain
is required (the `.so` files are not committed):

- **JDK 17+** (the reference setup uses Temurin 21)
- **Android SDK**: platform `android-36`, build-tools, and **NDK 27.2.12479018**
- **Rust** (stable) with the Android targets:
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  ```
- **cargo-ndk**:
  ```sh
  cargo install cargo-ndk
  ```

### Build & install

```sh
cd android
./gradlew assembleDebug
```

The build wires two extra tasks ahead of the usual jniLibs merge:

1. **`fetchSherpaPrebuilt`** — downloads the sherpa-onnx prebuilt `.so` (cached in
   the Gradle user home) and extracts them into `jniLibs/<abi>/`.
2. **`cargoBuildRustCore`** — cross-compiles `rust/core` with cargo-ndk into
   `jniLibs/<abi>/libtranslatecore.so` (must run after the fetch, because
   `build.rs` links against `libsherpa-onnx-c-api.so`).

Both declare inputs/outputs, so they are skipped when nothing changed. Supported
ABIs are **arm64-v8a** (devices) and **x86_64** (emulator).

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. Install it
with `adb install` (or your Android CLI of choice), launch the app, and use
**Settings → Download all models** before the first translation. The experimental
streaming ASR model (`asr_stream`) is large and English-only, so it is excluded
from this bulk download and fetched separately from its own button under
**Settings → Experimental**.

### Regenerating UniFFI bindings

The generated Kotlin bindings (`uniffi/translatecore/translatecore.kt`) are
committed. Regenerate them only when the Rust public API changes:

```powershell
pwsh rust/build-android.ps1
```

## Architecture at a glance

```
Kotlin / Compose (app/)        UI, AudioRecord capture, OS TTS, model provisioning, settings
        │  UniFFI + JNA
        ▼
Rust core (rust/core/)         pipeline orchestration, ASR/MT via FFI
        │  C API / dlopen
        ▼
Native libs (jniLibs/)         sherpa-onnx + onnxruntime, libtranslatecore.so
```

- **Capture** stays in Kotlin (`AudioRecord`); **playback** uses the OS
  `TextToSpeech` engine. Rust handles pure conversion and inference, which keeps
  it testable.
- **Translation** runs NLLB ONNX via the `ort` crate, which `dlopen`s the
  `libonnxruntime.so` already bundled with sherpa-onnx.

## Models

Models are **not** bundled in the APK. They are downloaded on first use from the
manifest at `android/app/src/main/assets/models.json` into the app's internal
storage (required because the NDK's raw `open()` is denied on external storage on
some OEMs).

| ID | Model | Role | Source | Approx. size |
|---|---|---|---|---|
| `vad` | Silero VAD | Voice activity detection / turn segmentation | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~2 MB |
| `asr` | sherpa-onnx SenseVoice int8 (zh-en-ja-ko-yue) | ASR + language ID | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~230 MB |
| `nllb` | NLLB-200-distilled-600M (ONNX, quantized, merged decoder) | Translation | [Xenova on HF](https://huggingface.co/Xenova/nllb-200-distilled-600M) | ~865 MB (encoder + decoder + tokenizer) |
| `asr_stream` | sherpa-onnx Nemotron streaming EN 0.6B int8 | Experimental low-latency EN ASR | [k2-fsa releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | ~464 MB |

SenseVoice (`asr`) is the default recognizer: it handles both EN and JA and
reports a language tag used for automatic direction detection. The streaming
Nemotron model (`asr_stream`) is an opt-in alternative that trades that coverage
for live, low-latency partials — it is **English-only** and emits no language
tag, so it suits the EN→JA flow only and is enabled per choice under
**Settings → Experimental**.

Spoken output uses the device's own OS TTS engine, so no synthesis model is
downloaded here. NLLB is multilingual; the in-app language pair is EN↔JA, so only
those directions are exercised.

## Online mode (optional)

Offline is the default and needs no account. If you want lower latency, Settings
exposes an opt-in **Online (OpenAI)** mode: you supply your own OpenAI API key
(encrypted on-device via Tink AEAD under an Android Keystore master key), and a
turn can be routed
through **OpenAI Realtime** (`gpt-realtime-translate`) for streaming
speech-to-speech. Audio is then sent to OpenAI and billed to your key. The toggle
sits next to the mic, the offline pipeline stays the default, and **Test
connection** in Settings mints an ephemeral token to confirm the key and network.

## License

TBD.
