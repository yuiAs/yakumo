//! Translation runtime smoke (Step A): prove `ort` loads the existing
//! libonnxruntime.so and can open an NLLB ONNX model on device.
//! The real encode/decode/generate pipeline (Step B) builds on this.

#[cfg(target_os = "android")]
pub fn smoke(ort_dylib: &str, model_path: &str) -> Result<String, String> {
    use ort::session::Session;

    // Point ort at the already-present onnxruntime shared library (no link/build).
    ort::init_from(ort_dylib)
        .commit()
        .map_err(|e| format!("ort init: {e}"))?;

    let session = Session::builder()
        .map_err(|e| format!("session builder: {e}"))?
        .commit_from_file(model_path)
        .map_err(|e| format!("load model: {e}"))?;

    let ins: Vec<String> = session.inputs.iter().map(|i| i.name.to_string()).collect();
    let outs: Vec<String> = session.outputs.iter().map(|o| o.name.to_string()).collect();
    Ok(format!("inputs={ins:?} outputs={outs:?}"))
}

#[cfg(not(target_os = "android"))]
pub fn smoke(_ort_dylib: &str, _model_path: &str) -> Result<String, String> {
    Err("translation runtime is only wired on Android".to_owned())
}
