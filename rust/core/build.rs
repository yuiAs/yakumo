use std::path::PathBuf;

// Link against the prebuilt sherpa-onnx C API shipped in the app's jniLibs.
// Only emitted for Android targets; host builds (tests, uniffi-bindgen) skip this.
fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    if !target.contains("android") {
        return;
    }

    let abi = match std::env::var("CARGO_CFG_TARGET_ARCH").as_deref() {
        Ok("aarch64") => "arm64-v8a",
        Ok("x86_64") => "x86_64",
        Ok("arm") => "armeabi-v7a",
        Ok("x86") => "x86",
        other => panic!("unsupported android arch: {other:?}"),
    };

    let jni = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap())
        .join("../../android/app/src/main/jniLibs")
        .join(abi);

    println!("cargo:rustc-link-search=native={}", jni.display());
    println!("cargo:rustc-link-lib=dylib=sherpa-onnx-c-api");
}
