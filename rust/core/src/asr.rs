//! Offline ASR via the sherpa-onnx C API (SenseVoice). Android-only; host stub.
//!
//! The recognizer is expensive to build but reusable, so it is kept resident in
//! a process-global keyed by `model_dir`; only the lightweight per-utterance
//! stream is created on each call.

#[cfg(target_os = "android")]
pub fn load(model_dir: &str) -> Result<(), String> {
    imp::load(model_dir)
}

/// Returns `(transcript, detected_lang_tag)`; the tag is SenseVoice's raw
/// `<|xx|>` language marker (empty if unavailable).
#[cfg(target_os = "android")]
pub fn recognize(
    model_dir: &str,
    samples: &[f32],
    sample_rate: i32,
) -> Result<(String, String), String> {
    imp::recognize(model_dir, samples, sample_rate)
}

#[cfg(not(target_os = "android"))]
pub fn load(_model_dir: &str) -> Result<(), String> {
    Err("ASR is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn recognize(
    _model_dir: &str,
    _samples: &[f32],
    _sample_rate: i32,
) -> Result<(String, String), String> {
    Err("ASR is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(target_os = "android")]
#[allow(dead_code)] // many config fields are part of the ABI but unused for SenseVoice
mod imp {
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_void};
    use std::path::Path;
    use std::sync::{Mutex, OnceLock};

    // --- C struct mirrors (sherpa-onnx c-api.h v1.13.2). Order/types must match exactly. ---

    #[repr(C)]
    struct TransducerModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        joiner: *const c_char,
    }
    #[repr(C)]
    struct ParaformerModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct NemoEncDecCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct WhisperModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        language: *const c_char,
        task: *const c_char,
        tail_paddings: i32,
        enable_token_timestamps: i32,
        enable_segment_timestamps: i32,
    }
    #[repr(C)]
    struct TdnnModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct SenseVoiceModelConfig {
        model: *const c_char,
        language: *const c_char,
        use_itn: i32,
    }
    #[repr(C)]
    struct MoonshineModelConfig {
        preprocessor: *const c_char,
        encoder: *const c_char,
        uncached_decoder: *const c_char,
        cached_decoder: *const c_char,
        merged_decoder: *const c_char,
    }
    #[repr(C)]
    struct FireRedAsrModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
    }
    #[repr(C)]
    struct DolphinModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct ZipformerCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct CanaryModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        src_lang: *const c_char,
        tgt_lang: *const c_char,
        use_pnc: i32,
    }
    #[repr(C)]
    struct WenetCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct OmnilingualAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct MedAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct FunASRNanoModelConfig {
        encoder_adaptor: *const c_char,
        llm: *const c_char,
        embedding: *const c_char,
        tokenizer: *const c_char,
        system_prompt: *const c_char,
        user_prompt: *const c_char,
        max_new_tokens: i32,
        temperature: f32,
        top_p: f32,
        seed: i32,
        language: *const c_char,
        itn: i32,
        hotwords: *const c_char,
    }
    #[repr(C)]
    struct FireRedAsrCtcModelConfig {
        model: *const c_char,
    }
    #[repr(C)]
    struct Qwen3AsrModelConfig {
        conv_frontend: *const c_char,
        encoder: *const c_char,
        decoder: *const c_char,
        tokenizer: *const c_char,
        max_total_len: i32,
        max_new_tokens: i32,
        temperature: f32,
        top_p: f32,
        seed: i32,
        hotwords: *const c_char,
    }
    #[repr(C)]
    struct CohereTranscribeModelConfig {
        encoder: *const c_char,
        decoder: *const c_char,
        language: *const c_char,
        use_punct: i32,
        use_itn: i32,
    }

    #[repr(C)]
    struct OfflineModelConfig {
        transducer: TransducerModelConfig,
        paraformer: ParaformerModelConfig,
        nemo_ctc: NemoEncDecCtcModelConfig,
        whisper: WhisperModelConfig,
        tdnn: TdnnModelConfig,
        tokens: *const c_char,
        num_threads: i32,
        debug: i32,
        provider: *const c_char,
        model_type: *const c_char,
        modeling_unit: *const c_char,
        bpe_vocab: *const c_char,
        telespeech_ctc: *const c_char,
        sense_voice: SenseVoiceModelConfig,
        moonshine: MoonshineModelConfig,
        fire_red_asr: FireRedAsrModelConfig,
        dolphin: DolphinModelConfig,
        zipformer_ctc: ZipformerCtcModelConfig,
        canary: CanaryModelConfig,
        wenet_ctc: WenetCtcModelConfig,
        omnilingual: OmnilingualAsrCtcModelConfig,
        medasr: MedAsrCtcModelConfig,
        funasr_nano: FunASRNanoModelConfig,
        fire_red_asr_ctc: FireRedAsrCtcModelConfig,
        qwen3_asr: Qwen3AsrModelConfig,
        cohere_transcribe: CohereTranscribeModelConfig,
    }

    #[repr(C)]
    struct FeatureConfig {
        sample_rate: i32,
        feature_dim: i32,
    }
    #[repr(C)]
    struct OfflineLmConfig {
        model: *const c_char,
        scale: f32,
    }
    #[repr(C)]
    struct HomophoneReplacerConfig {
        dict_dir: *const c_char,
        lexicon: *const c_char,
        rule_fsts: *const c_char,
    }
    #[repr(C)]
    struct OfflineRecognizerConfig {
        feat_config: FeatureConfig,
        model_config: OfflineModelConfig,
        lm_config: OfflineLmConfig,
        decoding_method: *const c_char,
        max_active_paths: i32,
        hotwords_file: *const c_char,
        hotwords_score: f32,
        rule_fsts: *const c_char,
        rule_fars: *const c_char,
        blank_penalty: f32,
        hr: HomophoneReplacerConfig,
    }

    // Full mirror of SherpaOnnxOfflineRecognizerResult (c-api.h v1.13.2). We read
    // `text` and `lang` (SenseVoice fills `lang` with a tag like "<|en|>"); every
    // preceding field must be declared so `lang`'s offset is correct.
    #[repr(C)]
    struct RecognizerResult {
        text: *const c_char,
        timestamps: *const f32,
        count: i32,
        tokens: *const c_char,
        tokens_arr: *const *const c_char,
        json: *const c_char,
        lang: *const c_char,
        emotion: *const c_char,
        event: *const c_char,
        durations: *const f32,
        ys_log_probs: *const f32,
        segment_timestamps: *const f32,
        segment_durations: *const f32,
        segment_texts: *const c_char,
        segment_texts_arr: *const *const c_char,
        segment_count: i32,
    }

    extern "C" {
        fn SherpaOnnxCreateOfflineRecognizer(
            config: *const OfflineRecognizerConfig,
        ) -> *const c_void;
        fn SherpaOnnxDestroyOfflineRecognizer(recognizer: *const c_void);
        fn SherpaOnnxCreateOfflineStream(recognizer: *const c_void) -> *const c_void;
        fn SherpaOnnxDestroyOfflineStream(stream: *const c_void);
        fn SherpaOnnxAcceptWaveformOffline(
            stream: *const c_void,
            sample_rate: i32,
            samples: *const f32,
            n: i32,
        );
        fn SherpaOnnxDecodeOfflineStream(recognizer: *const c_void, stream: *const c_void);
        fn SherpaOnnxGetOfflineStreamResult(stream: *const c_void) -> *const RecognizerResult;
        fn SherpaOnnxDestroyOfflineRecognizerResult(r: *const RecognizerResult);
    }

    /// Resident SenseVoice recognizer. The sherpa handle is a raw pointer, so it
    /// is not auto-`Send`; access is serialized through `ENGINE`'s `Mutex`, which
    /// makes single-owner cross-thread use sound.
    struct AsrEngine {
        model_dir: String,
        recognizer: *const c_void,
    }

    unsafe impl Send for AsrEngine {}

    impl Drop for AsrEngine {
        fn drop(&mut self) {
            unsafe { SherpaOnnxDestroyOfflineRecognizer(self.recognizer) };
        }
    }

    static ENGINE: OnceLock<Mutex<Option<AsrEngine>>> = OnceLock::new();

    fn engine_cell() -> &'static Mutex<Option<AsrEngine>> {
        ENGINE.get_or_init(|| Mutex::new(None))
    }

    fn create_recognizer(model_dir: &str) -> Result<*const c_void, String> {
        let dir = Path::new(model_dir);
        let model_path = dir.join("model.int8.onnx");
        let tokens_path = dir.join("tokens.txt");
        for p in [&model_path, &tokens_path] {
            if std::fs::File::open(p).is_err() {
                return Err(format!("cannot open {}", p.display()));
            }
        }

        let model = CString::new(model_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let tokens = CString::new(tokens_path.to_string_lossy().into_owned()).map_err(|e| e.to_string())?;
        let language = CString::new("auto").unwrap(); // language ID
        let provider = CString::new("cpu").unwrap();
        let decoding = CString::new("greedy_search").unwrap();

        let mut cfg: OfflineRecognizerConfig = unsafe { std::mem::zeroed() };
        cfg.feat_config.sample_rate = 16000;
        cfg.feat_config.feature_dim = 80;
        cfg.model_config.tokens = tokens.as_ptr();
        cfg.model_config.num_threads = 2;
        cfg.model_config.debug = 1;
        cfg.model_config.provider = provider.as_ptr();
        cfg.model_config.sense_voice.model = model.as_ptr();
        cfg.model_config.sense_voice.language = language.as_ptr();
        cfg.model_config.sense_voice.use_itn = 1;
        cfg.decoding_method = decoding.as_ptr();

        // sherpa copies the config strings internally, so the CStrings above can
        // be dropped once this returns.
        let recognizer = unsafe { SherpaOnnxCreateOfflineRecognizer(&cfg) };
        if recognizer.is_null() {
            return Err("SherpaOnnxCreateOfflineRecognizer returned null".to_owned());
        }
        Ok(recognizer)
    }

    fn ensure_loaded<'a>(
        guard: &'a mut Option<AsrEngine>,
        model_dir: &str,
    ) -> Result<&'a AsrEngine, String> {
        let stale = guard.as_ref().map(|e| e.model_dir.as_str()) != Some(model_dir);
        if stale {
            let recognizer = create_recognizer(model_dir)?;
            *guard = Some(AsrEngine {
                model_dir: model_dir.to_owned(),
                recognizer,
            });
        }
        Ok(guard.as_ref().expect("engine just loaded"))
    }

    pub fn load(model_dir: &str) -> Result<(), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        ensure_loaded(&mut guard, model_dir)?;
        Ok(())
    }

    unsafe fn cstr(p: *const c_char) -> String {
        if p.is_null() {
            String::new()
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }

    pub fn recognize(
        model_dir: &str,
        samples: &[f32],
        sample_rate: i32,
    ) -> Result<(String, String), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let recognizer = ensure_loaded(&mut guard, model_dir)?.recognizer;

        // The recognizer is reused; only the per-utterance stream is transient.
        let stream = unsafe { SherpaOnnxCreateOfflineStream(recognizer) };
        if stream.is_null() {
            return Err("CreateOfflineStream returned null".to_owned());
        }
        unsafe {
            SherpaOnnxAcceptWaveformOffline(
                stream,
                sample_rate,
                samples.as_ptr(),
                samples.len() as i32,
            );
            SherpaOnnxDecodeOfflineStream(recognizer, stream);
        }
        let res = unsafe { SherpaOnnxGetOfflineStreamResult(stream) };
        let (text, lang) = if res.is_null() {
            (String::new(), String::new())
        } else {
            let t = unsafe { cstr((*res).text) };
            let l = unsafe { cstr((*res).lang) };
            unsafe { SherpaOnnxDestroyOfflineRecognizerResult(res) };
            (t, l)
        };
        unsafe { SherpaOnnxDestroyOfflineStream(stream) };
        Ok((text, lang))
    }
}
