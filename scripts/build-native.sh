#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}"
case "$(uname -s)" in
  Linux) host=linux-x86_64 ;;
  Darwin) host=darwin-x86_64 ;;
  *) echo 'Use build-native.ps1 on Windows' >&2; exit 1 ;;
esac
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/$host/bin/x86_64-linux-android26-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
for target in aarch64-linux-android x86_64-linux-android; do
  cargo build --locked --release --manifest-path "$repo/native/android/Cargo.toml" --target "$target"
  if [[ "$target" == aarch64-linux-android ]]; then abi=arm64-v8a; else abi=x86_64; fi
  mkdir -p "$repo/android/app/src/main/jniLibs/$abi"
  cp "$repo/native/android/target/$target/release/libyap_android.so" "$repo/android/app/src/main/jniLibs/$abi/"
done
