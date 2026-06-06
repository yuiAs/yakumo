// Thin wrapper so `cargo run --bin uniffi-bindgen` drives binding generation
// using this crate's pinned uniffi version.
fn main() {
    uniffi::uniffi_bindgen_main()
}
