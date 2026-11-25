param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Cargo = 'cargo',
    [string]$NdkVersion = '28.2.13676358',
    [string]$Toolchain = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$ndkBin = Join-Path $AndroidSdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin"
if (-not (Test-Path -LiteralPath $ndkBin)) { throw "Install Android NDK $NdkVersion before building native messaging" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $ndkBin 'aarch64-linux-android26-clang.cmd'
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = Join-Path $ndkBin 'x86_64-linux-android26-clang.cmd'
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
$manifest = Join-Path $repo 'native/android/Cargo.toml'
foreach ($target in @('aarch64-linux-android', 'x86_64-linux-android')) {
    $arguments = @()
    if ($Toolchain) { $arguments += "+$Toolchain" }
    $arguments += @('build', '--locked', '--release', '--manifest-path', $manifest, '--target', $target)
    & $Cargo @arguments
    if ($LASTEXITCODE -ne 0) { throw "Native build failed: $target" }
    $abi = if ($target -eq 'aarch64-linux-android') { 'arm64-v8a' } else { 'x86_64' }
    $destination = Join-Path $repo "android/app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo "native/android/target/$target/release/libyap_android.so") -Destination $destination
}
