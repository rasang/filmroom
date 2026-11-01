#!/usr/bin/env bash
# Install this script at <source checkout>/native/build-native.sh.
# Builds the GPL CPU engine plus bounded optional Adreno Vulkan density compute.
set -euo pipefail
if [[ "${1:-}" == "--help" ]]; then
  printf '%s\n' 'Usage: bash native/build-native.sh [all|arm64-v8a|x86_64|--check]' \
    'Requires rustc/cargo 1.88.0 in PATH and Android NDK 27.2.12479018.' \
    'Set ANDROID_NDK_HOME or NDK_HOME. CARGO_HOME and RUSTUP_HOME are respected.' \
    'Optional: FILM_NATIVE_DIR, CARGO_TARGET_DIR, FILM_NATIVE_OUTPUT_DIR.'
  exit 0
fi
native_dir=${FILM_NATIVE_DIR:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)}
ndk=${ANDROID_NDK_HOME:-${NDK_HOME:-}}
if [[ -z "$ndk" ]]; then printf '%s\n' 'Set ANDROID_NDK_HOME or NDK_HOME to the official NDK 27.2.12479018 directory.' >&2; exit 2; fi
[[ -f "$native_dir/Cargo.toml" && -f "$native_dir/Cargo.lock" ]] || { printf '%s\n' 'Native Cargo workspace/lockfile not found; place script in native/ or set FILM_NATIVE_DIR.' >&2; exit 2; }
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$ndk/source.properties" || { printf '%s\n' 'This build recipe pins NDK 27.2.12479018 (r27c).' >&2; exit 2; }
[[ "$(rustc --version)" == 'rustc 1.88.0 '* ]] || { printf '%s\n' 'Select rustc 1.88.0 before running the pinned build recipe.' >&2; exit 2; }
[[ "$(cargo --version)" == 'cargo 1.88.0 '* ]] || { printf '%s\n' 'Select cargo 1.88.0 before running the pinned build recipe.' >&2; exit 2; }
case "$(uname -s)" in
  Linux) host=linux-x86_64 ;;
  Darwin) host=darwin-x86_64 ;;
  *) printf '%s\n' 'This shell recipe supports the Linux and macOS NDK hosts.' >&2; exit 2 ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
for target in aarch64-linux-android x86_64-linux-android; do
  [[ -x "$bin/${target}26-clang" ]] || { printf 'Missing NDK linker for %s\n' "$target" >&2; exit 2; }
  rustup target list --installed | grep -qx "$target" || { printf 'Install the selected toolchain target first: rustup target add %s\n' "$target" >&2; exit 2; }
done
if [[ "${1:-}" == '--check' ]]; then printf '%s\n' 'Pinned native build prerequisites found; no compilation performed.'; exit 0; fi
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$native_dir/target-android"}
output=${FILM_NATIVE_OUTPUT_DIR:-"$native_dir/artifacts/jniLibs"}
case "${1:-all}" in all) abis=(arm64-v8a x86_64) ;; arm64-v8a|x86_64) abis=("$1") ;; *) printf '%s\n' 'Unknown ABI. Use all, arm64-v8a or x86_64.' >&2; exit 2 ;; esac
cd "$native_dir"
for abi in "${abis[@]}"; do
  if [[ "$abi" == arm64-v8a ]]; then
    target=aarch64-linux-android
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$bin/aarch64-linux-android26-clang"
  else
    target=x86_64-linux-android
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$bin/x86_64-linux-android26-clang"
  fi
  # V5 stage-separated physical engine uses these exact features/link flags; --locked enforces the supplied lockfile.
  # No global RUSTFLAGS were set in the verified V5 build.
  cargo rustc --locked -p film-engine --features precision-f64,adreno-gpu --lib --target "$target" --release -- \
    -C link-arg=-Wl,-z,max-page-size=16384 \
    -C link-arg=-Wl,-z,common-page-size=16384
  mkdir -p "$output/$abi"
  cp "$CARGO_TARGET_DIR/$target/release/libfilm_engine.so" "$output/$abi/libfilm_engine.so"
  "$bin/llvm-readelf" -l -d --dyn-syms "$output/$abi/libfilm_engine.so" > "$output/$abi/elf-validation.txt"
  printf 'Built %s\n' "$output/$abi/libfilm_engine.so"
done
