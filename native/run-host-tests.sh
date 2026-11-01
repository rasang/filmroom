#!/usr/bin/env bash
# CPU reference/safety tests, then actual host JVM JNI calls. Synthetic data only.
# Run as a regular user: the permission-failure privacy test relies on mode000.
set -euo pipefail
native_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$native_dir/target-host-tests"}
results=${FILM_TEST_RESULTS:-"$native_dir/test-results"}
mkdir -p "$results"
case "$(uname -s)" in Linux) suffix=so ;; Darwin) suffix=dylib ;; *) printf '%s\n' 'Host JNI recipe requires a Linux/macOS JDK and Rust host toolchain.' >&2; exit 2 ;; esac
cd "$native_dir"
cargo test --locked -p film-engine -p spektrafilm-core -p spektrafilm-math -p spektrafilm-model --features precision-f64 > "$results/cpu-tests.log" 2>&1
cargo build --locked -p film-engine --features precision-f64 --lib > "$results/host-library-build.log" 2>&1
classes="$results/java-classes";mkdir -p "$classes"
java --module jdk.compiler/com.sun.tools.javac.Main -d "$classes" "$native_dir"/tests/jni/dev/filmroom/engine/*.java
sandbox=$(mktemp -d "$results/jni-sandbox.XXXXXX")
trap 'rm -rf -- "$sandbox"' EXIT
mkdir -p "$sandbox/files/engine-data" "$sandbox/cache"
cp -R "$native_dir/data/." "$sandbox/files/engine-data/"
# The confinement test needs an existing bundled manifest, not photo metadata.
cp "$native_dir/DATA_MANIFEST.json" "$sandbox/files/engine-data/manifest.json"
lib="$CARGO_TARGET_DIR/debug/libfilm_engine.$suffix"
java -cp "$classes" dev.filmroom.engine.NativeFilmBridge "$lib" "$sandbox/files/engine-data" "$results/smoke.f32" > "$results/jni-smoke.json"
java -cp "$classes" dev.filmroom.engine.ConcurrencyProbe "$lib" "$sandbox/files/engine-data" > "$results/jni-concurrency.json"
for probe in Export Path Privacy Memory QueueCheckpoint; do
  name=$(printf '%s' "$probe"|tr '[:upper:]' '[:lower:]')
  java -cp "$classes" "dev.filmroom.engine.${probe}Probe" "$lib" "$sandbox" > "$results/jni-$name.json"
done
printf 'CPU and host JNI tests passed; results: %s\n' "$results"
# These tests execute a HOST shared library, not either Android shared library.
