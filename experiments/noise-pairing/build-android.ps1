# Cross-compiles this experiment for the two ABIs the app ships (arm64-v8a, x86_64).
# Builds only; nothing can run on this machine for those targets. Same linker and page-size
# settings as scripts/build-native.ps1.
param(
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$NdkVersion = '28.2.13676358'
)
$ErrorActionPreference = 'Continue'  # cargo writes progress to stderr; failures are caught via exit codes
$ndkBin = Join-Path $AndroidSdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (-not (Test-Path -LiteralPath $ndkBin)) { throw "Install Android NDK $NdkVersion" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $ndkBin 'aarch64-linux-android26-clang.cmd'
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $ndkBin 'x86_64-linux-android26-clang.cmd'
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
# For any C code built by the `cc` crate (SQLCipher, OpenSSL).
$env:CC_aarch64_linux_android = Join-Path $ndkBin 'aarch64-linux-android26-clang.cmd'
$env:CC_x86_64_linux_android = Join-Path $ndkBin 'x86_64-linux-android26-clang.cmd'
$env:AR_aarch64_linux_android = Join-Path $ndkBin 'llvm-ar.exe'
$env:AR_x86_64_linux_android = Join-Path $ndkBin 'llvm-ar.exe'
foreach ($target in @('aarch64-linux-android', 'x86_64-linux-android')) {
    # --tests builds the test binaries too; they link but cannot run here.
    cargo build --locked --release --target $target
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $target" }
    cargo test --locked --release --target $target --no-run
    if ($LASTEXITCODE -ne 0) { throw "Test build failed: $target" }
    Write-Host "OK $target"
}
