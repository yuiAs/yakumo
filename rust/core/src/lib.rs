//! translatecore — native pipeline core exposed to the Android app via UniFFI.
//!
//! PoC stage: only proves the Rust -> Kotlin bridge end to end. The real
//! TranslationEngine abstraction (NLLB / LLM / Realtime) lands in later phases.

uniffi::setup_scaffolding!();

mod asr;
mod translate;
mod tts;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TranslateError {
    #[error("{0}")]
    Failed(String),
}

/// Step A smoke: load onnxruntime via `ort` and open an ONNX model on device.
#[uniffi::export]
pub fn translate_smoke(ort_dylib: String, model_path: String) -> Result<String, TranslateError> {
    translate::smoke(&ort_dylib, &model_path).map_err(TranslateError::Failed)
}

/// Translates `text` from `src_lang` to `tgt_lang` (NLLB FLORES codes, e.g.
/// "eng_Latn", "jpn_Jpan") using the NLLB ONNX models under `model_dir`.
#[uniffi::export]
pub fn translate_text(
    model_dir: String,
    text: String,
    src_lang: String,
    tgt_lang: String,
    ort_dylib: String,
) -> Result<String, TranslateError> {
    translate::translate(&model_dir, &text, &src_lang, &tgt_lang, &ort_dylib)
        .map_err(TranslateError::Failed)
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AsrError {
    #[error("{0}")]
    Failed(String),
}

/// Transcribes 16 kHz mono PCM (signed 16-bit little-endian) with the SenseVoice
/// model under `model_dir`. Language is auto-detected. Returns the transcript.
/// Bytes are used over the FFI to avoid boxing tens of thousands of floats.
#[uniffi::export]
pub fn asr_recognize(
    model_dir: String,
    pcm16le: Vec<u8>,
    sample_rate: i32,
) -> Result<String, AsrError> {
    let samples: Vec<f32> = pcm16le
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
        .collect();
    asr::recognize(&model_dir, &samples, sample_rate).map_err(AsrError::Failed)
}

/// Result of a TTS synthesis: the written WAV plus basic audio metadata.
#[derive(uniffi::Record)]
pub struct TtsResult {
    pub sample_rate: i32,
    pub num_samples: i32,
    pub wav_path: String,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TtsError {
    #[error("{0}")]
    Failed(String),
}

/// Synthesizes `text` with the Kokoro model under `model_dir`, writing a WAV to
/// `out_wav`. `speed` is the Kokoro generation rate (playback speed is applied
/// separately in the UI layer). Proves a real model runs end to end on device.
#[uniffi::export]
pub fn tts_synthesize(
    model_dir: String,
    text: String,
    sid: i32,
    speed: f32,
    out_wav: String,
) -> Result<TtsResult, TtsError> {
    let (sample_rate, num_samples) =
        tts::synthesize(&model_dir, &text, sid, speed, &out_wav).map_err(TtsError::Failed)?;
    Ok(TtsResult {
        sample_rate,
        num_samples,
        wav_path: out_wav,
    })
}

/// Version banner for the native core. Used by the PoC to confirm the app is
/// actually executing Rust rather than a Kotlin stub.
#[uniffi::export]
pub fn core_version() -> String {
    format!("translatecore v{}", env!("CARGO_PKG_VERSION"))
}

/// Echoes a greeting from the native side, proving arguments cross the FFI boundary.
#[uniffi::export]
pub fn greeting(name: String) -> String {
    format!("{name} — wired through Rust core")
}

// sherpa-onnx C API binding. On Android we link the prebuilt libsherpa-onnx-c-api.so
// (see build.rs); on the host we stub it so tests/binding-generation still link.
#[cfg(target_os = "android")]
mod sherpa {
    use std::ffi::CStr;
    use std::os::raw::c_char;

    extern "C" {
        fn SherpaOnnxGetVersionStr() -> *const c_char;
    }

    pub fn version() -> String {
        let ptr = unsafe { SherpaOnnxGetVersionStr() };
        if ptr.is_null() {
            return "unknown".to_owned();
        }
        unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned()
    }
}

#[cfg(not(target_os = "android"))]
mod sherpa {
    pub fn version() -> String {
        "host-stub".to_owned()
    }
}

/// Reports the linked sherpa-onnx version. Calling this proves the heavy native
/// stack (sherpa-onnx + onnxruntime) actually loads and executes on the device,
/// without needing any model yet.
#[uniffi::export]
pub fn sherpa_version() -> String {
    format!("sherpa-onnx {}", sherpa::version())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn greeting_includes_input() {
        assert!(greeting("Android".into()).starts_with("Android"));
    }

    #[test]
    fn version_is_reported() {
        assert!(core_version().contains("translatecore"));
    }
}
