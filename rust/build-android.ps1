# Builds the Rust core for Android and regenerates the UniFFI Kotlin bindings.
# Run from anywhere; paths are resolved relative to this script.
#
#   pwsh rust/build-android.ps1            # arm64 + x86_64 (default)
#   pwsh rust/build-android.ps1 -Release   # optimized build
param(
    [string[]]$Abis = @('arm64-v8a', 'x86_64'),
    [switch]$Release
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot          # repo root
$crate = Join-Path $PSScriptRoot 'core'
$jniLibs = Join-Path $root 'android\app\src\main\jniLibs'
$ktOut = Join-Path $root 'android\app\src\main\java'

# cargo-ndk locates the NDK via these; set them so the script is self-contained.
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\27.2.12479018'

$targetArgs = $Abis | ForEach-Object { '-t', $_ }
# [string[]] is load-bearing: an `if` expression returns through the pipeline,
# which unwraps a one-element array to a bare string. Splatting that string
# passes it one character at a time, so cargo sees a lone '-'.
[string[]]$profileArgs = if ($Release) { @('--release') } else { @() }

Push-Location $crate
try {
    Write-Host '== Building .so via cargo-ndk ==' -ForegroundColor Cyan
    cargo ndk @targetArgs -o $jniLibs build @profileArgs

    Write-Host '== Generating Kotlin bindings ==' -ForegroundColor Cyan
    $lib = Join-Path $jniLibs 'arm64-v8a\libtranslatecore.so'
    cargo run --quiet --bin uniffi-bindgen generate --library $lib --language kotlin --out-dir $ktOut
}
finally {
    Pop-Location
}
Write-Host 'Done.' -ForegroundColor Green
