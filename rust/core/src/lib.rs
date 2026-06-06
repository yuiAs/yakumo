//! translatecore — native pipeline core exposed to the Android app via UniFFI.
//!
//! PoC stage: only proves the Rust -> Kotlin bridge end to end. The real
//! TranslationEngine abstraction (NLLB / LLM / Realtime) lands in later phases.

uniffi::setup_scaffolding!();

mod tts;

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
