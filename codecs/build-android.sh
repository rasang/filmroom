#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$root"
export CARGO_HOME=${CARGO_HOME:-"$HOME/.cargo"}
export RUSTUP_HOME=${RUSTUP_HOME:-"$HOME/.rustup"}
export PATH="$CARGO_HOME/bin:$PATH"
ndk=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the installed Android NDK 27.2 directory}
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$root/target"}
for spec in 'aarch64-linux-android arm64-v8a' 'x86_64-linux-android x86_64'; do
 read -r target abi <<< "$spec"
 export FILM_JXL_PREFIX=$("$root/../third-party/libjxl/build.sh" "$abi")
 export HE_CXX="$bin/${target}26-clang++" HE_AR="$bin/llvm-ar"
 export "CARGO_TARGET_$(echo "$target"|tr '[:lower:]-' '[:upper:]_')_LINKER=$bin/${target}26-clang"
 python3 "$root/tools/ensure-vendor-fresh.py" --target "$target" --profile release --target-dir "$CARGO_TARGET_DIR"
 cargo rustc --manifest-path "$root/Cargo.toml" --offline --locked --release --lib --target "$target" -j2 -- -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384
 mkdir -p "$root/artifacts/jniLibs/$abi"
 cp "$CARGO_TARGET_DIR/$target/release/libfilm_codecs.so" "$root/artifacts/jniLibs/$abi/"
 "$bin/llvm-readelf" -l -d --dyn-syms "$root/artifacts/jniLibs/$abi/libfilm_codecs.so" > "$root/artifacts/jniLibs/$abi/elf-validation.txt"
done
