//! NLLB-200 translation via `ort` (onnxruntime) + `tokenizers`.
//!
//! Uses the no-past encoder/decoder ONNX (re-feeds the full decoder sequence
//! each step) to keep the greedy loop simple and correct, trading some speed.
//! ort uses `load-dynamic`, so this compiles everywhere; it only needs a real
//! libonnxruntime.so at runtime (provided on Android, see callers).
//!
//! Loading the ~865 MB of NLLB ONNX is the dominant cost, so the encoder,
//! decoder and tokenizer are kept resident in a process-global `NllbEngine`
//! keyed by `model_dir`. The first call loads; subsequent calls reuse it.

use ort::session::Session;
use ort::value::Tensor;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use tokenizers::Tokenizer;

pub fn smoke(ort_dylib: &str, model_path: &str) -> Result<String, String> {
    init_ort(ort_dylib);
    let session = Session::builder()
        .map_err(|e| format!("builder: {e}"))?
        .commit_from_file(model_path)
        .map_err(|e| format!("load: {e}"))?;
    let ins: Vec<String> = session.inputs.iter().map(|i| i.name.to_string()).collect();
    let outs: Vec<String> = session.outputs.iter().map(|o| o.name.to_string()).collect();
    Ok(format!("inputs={ins:?} outputs={outs:?}"))
}

// ort's environment can only be committed once per process; ignore re-init.
fn init_ort(ort_dylib: &str) {
    let _ = ort::init_from(ort_dylib).commit();
}

fn i64_tensor(shape: Vec<i64>, data: Vec<i64>) -> Result<Tensor<i64>, String> {
    Tensor::from_array((shape, data)).map_err(|e| e.to_string())
}

fn argmax(row: &[f32]) -> usize {
    let mut best = 0usize;
    let mut best_v = f32::NEG_INFINITY;
    for (i, &v) in row.iter().enumerate() {
        if v > best_v {
            best_v = v;
            best = i;
        }
    }
    best
}

fn ids_max_len(src_len: i64) -> usize {
    (src_len as usize) * 2 + 16
}

/// Resident NLLB models. `Session::run` needs `&mut self`, so callers hold this
/// behind a `Mutex` (see `ENGINE`); that also makes the raw model state safe to
/// touch from whichever thread UniFFI dispatches on.
struct NllbEngine {
    model_dir: String,
    tokenizer: Tokenizer,
    encoder: Session,
    decoder: Session,
}

impl NllbEngine {
    fn load(model_dir: &str) -> Result<Self, String> {
        let dir = Path::new(model_dir);
        let tokenizer = Tokenizer::from_file(dir.join("tokenizer.json"))
            .map_err(|e| format!("tokenizer: {e}"))?;
        let encoder = Session::builder()
            .map_err(|e| e.to_string())?
            .commit_from_file(dir.join("encoder_model_quantized.onnx"))
            .map_err(|e| format!("load encoder: {e}"))?;
        let decoder = Session::builder()
            .map_err(|e| e.to_string())?
            .commit_from_file(dir.join("decoder_model_quantized.onnx"))
            .map_err(|e| format!("load decoder: {e}"))?;
        Ok(Self {
            model_dir: model_dir.to_owned(),
            tokenizer,
            encoder,
            decoder,
        })
    }

    fn run(&mut self, text: &str, src_lang: &str, tgt_lang: &str) -> Result<String, String> {
        // Disjoint field borrows: `tok` reads tokenizer while encoder/decoder
        // are mutated; the borrow checker allows this within one method body.
        let tok = &self.tokenizer;
        let eos = tok.token_to_id("</s>").ok_or("no </s> token")? as i64;
        let src_id = tok
            .token_to_id(src_lang)
            .ok_or_else(|| format!("bad src lang {src_lang}"))? as i64;
        let tgt_id = tok
            .token_to_id(tgt_lang)
            .ok_or_else(|| format!("bad tgt lang {tgt_lang}"))? as i64;

        // NLLB source format: [src_lang] tokens... </s>
        let enc = tok.encode(text, false).map_err(|e| format!("encode: {e}"))?;
        let mut ids: Vec<i64> = Vec::with_capacity(enc.get_ids().len() + 2);
        ids.push(src_id);
        ids.extend(enc.get_ids().iter().map(|&x| x as i64));
        ids.push(eos);
        let src_len = ids.len() as i64;
        let mask: Vec<i64> = vec![1; ids.len()];

        // --- Encoder ---
        let enc_out = self
            .encoder
            .run(ort::inputs![
                "input_ids" => i64_tensor(vec![1, src_len], ids)?,
                "attention_mask" => i64_tensor(vec![1, src_len], mask.clone())?,
            ])
            .map_err(|e| format!("encoder run: {e}"))?;
        let (h_shape, h_data) = enc_out["last_hidden_state"]
            .try_extract_tensor::<f32>()
            .map_err(|e| format!("extract hidden: {e}"))?;
        let h_shape: Vec<i64> = h_shape.to_vec();
        let h_data: Vec<f32> = h_data.to_vec();

        // --- Decoder (greedy, no KV cache) ---
        // Seed: </s> then forced target-language token, generate from there.
        let mut dec_ids: Vec<i64> = vec![eos, tgt_id];
        let max_len = ids_max_len(src_len);
        for _ in 0..max_len {
            let dlen = dec_ids.len() as i64;
            let out = self
                .decoder
                .run(ort::inputs![
                    "input_ids" => i64_tensor(vec![1, dlen], dec_ids.clone())?,
                    "encoder_attention_mask" => i64_tensor(vec![1, src_len], mask.clone())?,
                    "encoder_hidden_states" => Tensor::from_array((h_shape.clone(), h_data.clone())).map_err(|e| e.to_string())?,
                ])
                .map_err(|e| format!("decoder run: {e}"))?;
            let (l_shape, l_data) = out["logits"]
                .try_extract_tensor::<f32>()
                .map_err(|e| format!("extract logits: {e}"))?;
            // logits: [1, dlen, vocab] -> take the last position
            let vocab = l_shape[2] as usize;
            let start = (dec_ids.len() - 1) * vocab;
            let next = argmax(&l_data[start..start + vocab]) as i64;
            if next == eos {
                break;
            }
            dec_ids.push(next);
        }

        // Drop the seed tokens, decode the rest.
        let gen: Vec<u32> = dec_ids[2..].iter().map(|&x| x as u32).collect();
        self.tokenizer
            .decode(&gen, true)
            .map_err(|e| format!("decode: {e}"))
    }
}

static ENGINE: OnceLock<Mutex<Option<NllbEngine>>> = OnceLock::new();

fn engine_cell() -> &'static Mutex<Option<NllbEngine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

/// Loads (or reuses) the resident engine for `model_dir` without translating.
/// Lets the UI warm the models up once and report a "loaded" state.
pub fn load(model_dir: &str, ort_dylib: &str) -> Result<(), String> {
    init_ort(ort_dylib);
    let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
    ensure_loaded(&mut guard, model_dir)?;
    Ok(())
}

pub fn translate(
    model_dir: &str,
    text: &str,
    src_lang: &str,
    tgt_lang: &str,
    ort_dylib: &str,
) -> Result<String, String> {
    init_ort(ort_dylib);
    let mut guard = engine_cell().lock().map_err(|e| e.to_string())?;
    let engine = ensure_loaded(&mut guard, model_dir)?;
    engine.run(text, src_lang, tgt_lang)
}

/// Ensures the held engine matches `model_dir`, loading it on the first call or
/// when the directory changes. Returns a mutable handle to the resident engine.
fn ensure_loaded<'a>(
    guard: &'a mut Option<NllbEngine>,
    model_dir: &str,
) -> Result<&'a mut NllbEngine, String> {
    let stale = guard.as_ref().map(|e| e.model_dir.as_str()) != Some(model_dir);
    if stale {
        *guard = Some(NllbEngine::load(model_dir)?);
    }
    Ok(guard.as_mut().expect("engine just loaded"))
}
