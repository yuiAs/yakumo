//! Offline TTS via the sherpa-onnx C API (Kokoro). Android-only; the host build
//! gets a stub so tests and binding generation still link.
//!
//! The Kokoro handle is expensive to build but reusable, so it is kept resident
//! in a process-global keyed by `model_dir`; each call only generates audio.

#[cfg(target_os = "android")]
pub fn load(model_dir: &str, lang: &str) -> Result<(), String> {
    imp::load(model_dir, lang)
}

#[cfg(target_os = "android")]
pub fn synthesize(
    model_dir: &str,
    text: &str,
    sid: i32,
    speed: f32,
    out_wav: &str,
    lang: &str,
) -> Result<(i32, i32), String> {
    imp::synthesize(model_dir, text, sid, speed, out_wav, lang)
}

#[cfg(not(target_os = "android"))]
pub fn load(_model_dir: &str, _lang: &str) -> Result<(), String> {
    Err("TTS is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(not(target_os = "android"))]
pub fn synthesize(
    _model_dir: &str,
    _text: &str,
    _sid: i32,
    _speed: f32,
    _out_wav: &str,
    _lang: &str,
) -> Result<(i32, i32), String> {
    Err("TTS is only available on Android (native sherpa-onnx not linked on host)".to_owned())
}

#[cfg(target_os = "android")]
mod imp {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_void};
    use std::path::Path;
    use std::sync::{Mutex, OnceLock};

    // --- C struct mirrors. Field order/types must match sherpa-onnx c-api.h exactly. ---
    // Engine sub-configs are embedded by value in TtsModelConfig, so every one must be
    // declared to keep `kokoro` at the correct offset and the total size correct.

    #[repr(C)]
    struct VitsModelConfig {
        model: *const c_char,
        lexicon: *const c_char,
        tokens: *const c_char,
        data_dir: *const c_char,
        noise_scale: f32,
        noise_scale_w: f32,
        length_scale: f32,
        dict_dir: *const c_char,
    }

    #[repr(C)]
    struct MatchaModelConfig {
        acoustic_model: *const c_char,
        vocoder: *const c_char,
        lexicon: *const c_char,
        tokens: *const c_char,
        data_dir: *const c_char,
        noise_scale: f32,
        length_scale: f32,
        dict_dir: *const c_char,
    }

    #[repr(C)]
    struct KokoroModelConfig {
        model: *const c_char,
        voices: *const c_char,
        tokens: *const c_char,
        data_dir: *const c_char,
        length_scale: f32,
        dict_dir: *const c_char,
        lexicon: *const c_char,
        lang: *const c_char,
    }

    #[repr(C)]
    struct KittenModelConfig {
        model: *const c_char,
        voices: *const c_char,
        tokens: *const c_char,
        data_dir: *const c_char,
        length_scale: f32,
    }

    #[repr(C)]
    struct ZipvoiceModelConfig {
        tokens: *const c_char,
        encoder: *const c_char,
        decoder: *const c_char,
        vocoder: *const c_char,
        data_dir: *const c_char,
        lexicon: *const c_char,
        feat_scale: f32,
        t_shift: f32,
        target_rms: f32,
        guidance_scale: f32,
    }

    #[repr(C)]
    struct PocketModelConfig {
        lm_flow: *const c_char,
        lm_main: *const c_char,
        encoder: *const c_char,
        decoder: *const c_char,
        text_conditioner: *const c_char,
        vocab_json: *const c_char,
        token_scores_json: *const c_char,
        voice_embedding_cache_capacity: i32,
    }

    #[repr(C)]
    struct SupertonicModelConfig {
        duration_predictor: *const c_char,
        text_encoder: *const c_char,
        vector_estimator: *const c_char,
        vocoder: *const c_char,
        tts_json: *const c_char,
        unicode_indexer: *const c_char,
        voice_style: *const c_char,
    }

    #[repr(C)]
    struct TtsModelConfig {
        vits: VitsModelConfig,
        num_threads: i32,
        debug: i32,
        provider: *const c_char,
        matcha: MatchaModelConfig,
        kokoro: KokoroModelConfig,
        kitten: KittenModelConfig,
        zipvoice: ZipvoiceModelConfig,
        pocket: PocketModelConfig,
        supertonic: SupertonicModelConfig,
    }

    #[repr(C)]
    struct TtsConfig {
        model: TtsModelConfig,
        rule_fsts: *const c_char,
        max_num_sentences: i32,
        rule_fars: *const c_char,
        silence_scale: f32,
    }

    #[repr(C)]
    struct GeneratedAudio {
        samples: *const f32,
        n: i32,
        sample_rate: i32,
    }

    extern "C" {
        fn SherpaOnnxCreateOfflineTts(config: *const TtsConfig) -> *const c_void;
        fn SherpaOnnxDestroyOfflineTts(tts: *const c_void);
        fn SherpaOnnxOfflineTtsGenerate(
            tts: *const c_void,
            text: *const c_char,
            sid: i32,
            speed: f32,
        ) -> *const GeneratedAudio;
        fn SherpaOnnxDestroyOfflineTtsGeneratedAudio(p: *const GeneratedAudio);
        fn SherpaOnnxWriteWave(
            samples: *const f32,
            n: i32,
            sample_rate: i32,
            filename: *const c_char,
        ) -> i32;
    }

    /// Resident Kokoro TTS handle. The sherpa handle is a raw pointer, so it is
    /// not auto-`Send`; access is serialized through `ENGINE`'s `Mutex`.
    struct TtsEngine {
        model_dir: String,
        lang: String,
        tts: *const c_void,
    }

    unsafe impl Send for TtsEngine {}

    impl Drop for TtsEngine {
        fn drop(&mut self) {
            unsafe { SherpaOnnxDestroyOfflineTts(self.tts) };
        }
    }

    static ENGINE: OnceLock<Mutex<Option<TtsEngine>>> = OnceLock::new();

    fn engine_cell() -> &'static Mutex<Option<TtsEngine>> {
        ENGINE.get_or_init(|| Mutex::new(None))
    }

    fn create_tts(model_dir: &str, lang: &str) -> Result<*const c_void, String> {
        let dir = Path::new(model_dir);

        // Kokoro variants differ only in the model filename: v1.1-zh ships
        // `model.int8.onnx`, v1.0-multilang ships `model.onnx`. Pick whichever
        // is present so both layouts work.
        let model_name = if dir.join("model.int8.onnx").is_file() {
            "model.int8.onnx"
        } else {
            "model.onnx"
        };

        // Preflight: confirm the native side can actually read the key files.
        // Distinguishes path/permission problems from model/config problems.
        for name in [model_name, "voices.bin", "tokens.txt"] {
            let p = dir.join(name);
            match std::fs::File::open(&p) {
                Ok(_) => {}
                Err(e) => return Err(format!("cannot open {}: {e}", p.display())),
            }
        }
        if !dir.join("espeak-ng-data").is_dir() {
            return Err(format!("missing dir {}", dir.join("espeak-ng-data").display()));
        }

        let path = |name: &str| -> Result<CString, String> {
            CString::new(dir.join(name).to_string_lossy().into_owned()).map_err(|e| e.to_string())
        };

        // Keep all CStrings alive until after CreateOfflineTts copies them internally.
        let model = path(model_name)?;
        let voices = path("voices.bin")?;
        let tokens = path("tokens.txt")?;
        let data_dir = path("espeak-ng-data")?;
        let provider = CString::new("cpu").unwrap();

        // Japanese must be phonemized via espeak-ng (kokoro.lang="ja"); the zh
        // lexicon + jieba dict would otherwise read kanji as Mandarin. Other
        // langs (empty = en/zh) keep the lexicon + dict and leave lang unset.
        let is_ja = lang == "ja";
        let lang_c = CString::new(lang).map_err(|e| e.to_string())?;
        let dict_dir = if is_ja { None } else { Some(path("dict")?) };
        let lexicon = if is_ja {
            None
        } else {
            Some(
                CString::new(format!(
                    "{},{}",
                    dir.join("lexicon-us-en.txt").to_string_lossy(),
                    dir.join("lexicon-zh.txt").to_string_lossy()
                ))
                .map_err(|e| e.to_string())?,
            )
        };

        // Zero-init the whole config (null pointers / 0 floats) then fill Kokoro.
        let mut cfg: TtsConfig = unsafe { std::mem::zeroed() };
        cfg.model.num_threads = 2;
        cfg.model.debug = 1; // forces sherpa-onnx to log config + load errors
        cfg.model.provider = provider.as_ptr();
        cfg.model.kokoro.model = model.as_ptr();
        cfg.model.kokoro.voices = voices.as_ptr();
        cfg.model.kokoro.tokens = tokens.as_ptr();
        cfg.model.kokoro.data_dir = data_dir.as_ptr();
        if let Some(d) = &dict_dir {
            cfg.model.kokoro.dict_dir = d.as_ptr();
        }
        if let Some(l) = &lexicon {
            cfg.model.kokoro.lexicon = l.as_ptr();
        }
        if !lang.is_empty() {
            cfg.model.kokoro.lang = lang_c.as_ptr();
        }
        cfg.model.kokoro.length_scale = 1.0;
        cfg.max_num_sentences = 1;

        let tts = unsafe { SherpaOnnxCreateOfflineTts(&cfg) };
        if tts.is_null() {
            return Err("SherpaOnnxCreateOfflineTts returned null".to_owned());
        }
        Ok(tts)
    }

    fn ensure_loaded<'a>(
        guard: &'a mut Option<TtsEngine>,
        model_dir: &str,
        lang: &str,
    ) -> Result<&'a TtsEngine, String> {
        // Rebuild when the model dir OR lang changes: the espeak language is
        // baked into the sherpa handle at creation time.
        let stale = guard
            .as_ref()
            .map(|e| (e.model_dir.as_str(), e.lang.as_str()))
            != Some((model_dir, lang));
        if stale {
            let tts = create_tts(model_dir, lang)?;
            *guard = Some(TtsEngine {
                model_dir: model_dir.to_owned(),
                lang: lang.to_owned(),
                tts,
            });
        }
        Ok(guard.as_ref().expect("engine just loaded"))
    }

    pub fn load(model_dir: &str, lang: &str) -> Result<(), String> {
        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        ensure_loaded(&mut guard, model_dir, lang)?;
        Ok(())
    }

    pub fn synthesize(
        model_dir: &str,
        text: &str,
        sid: i32,
        speed: f32,
        out_wav: &str,
        lang: &str,
    ) -> Result<(i32, i32), String> {
        let text_c = CString::new(text).map_err(|e| e.to_string())?;
        let out_c = CString::new(out_wav).map_err(|e| e.to_string())?;

        let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
        let tts = ensure_loaded(&mut guard, model_dir, lang)?.tts;

        let audio = unsafe { SherpaOnnxOfflineTtsGenerate(tts, text_c.as_ptr(), sid, speed) };
        if audio.is_null() {
            return Err("SherpaOnnxOfflineTtsGenerate returned null".to_owned());
        }

        let (n, sample_rate) = unsafe { ((*audio).n, (*audio).sample_rate) };
        let written = unsafe { SherpaOnnxWriteWave((*audio).samples, n, sample_rate, out_c.as_ptr()) };

        unsafe { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) };

        if written != 1 {
            return Err(format!("SherpaOnnxWriteWave failed (rc={written})"));
        }
        Ok((sample_rate, n))
    }
}
