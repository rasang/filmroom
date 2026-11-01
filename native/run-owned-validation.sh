#!/usr/bin/env bash
# Full CPU + legacy and owned JNI gate, isolated host outputs only.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-/workspace/shared/filmroom-roi-native-api-target}
export RAYON_NUM_THREADS=${RAYON_NUM_THREADS:-4}
export RUST_TEST_THREADS=1
export CARGO_BUILD_JOBS=1
results="$root/validation/all-host-jni-final";mkdir -p "$results/classes"
cd "$root"
cargo test --offline --locked --release --lib -p film-engine -p spektrafilm-core -p spektrafilm-math -p spektrafilm-model --features precision-f64 >"$results/cpu-tests.log" 2>&1
cargo build --offline --locked -p film-engine --features precision-f64 --lib >"$results/build.log" 2>&1
java --module jdk.compiler/com.sun.tools.javac.Main -d "$results/classes" tests/jni/dev/filmroom/engine/*.java
sandbox=$(mktemp -d "$results/sandbox.XXXXXX")
trap 'rm -rf -- "$sandbox"' EXIT
mkdir -p "$sandbox/files/engine-data" "$sandbox/cache/detail-preview/sources"
cp -R data/. "$sandbox/files/engine-data/"
cp DATA_MANIFEST.json "$sandbox/files/engine-data/manifest.json"
lib="$CARGO_TARGET_DIR/debug/libfilm_engine.so"
java -cp "$results/classes" dev.filmroom.engine.NativeFilmBridge "$lib" "$sandbox/files/engine-data" "$results/smoke.f32" >"$results/jni-smoke.json"
java -cp "$results/classes" dev.filmroom.engine.ConcurrencyProbe "$lib" "$sandbox/files/engine-data" >"$results/jni-concurrency.json"
for probe in Export Path Privacy Memory QueueCheckpoint; do
 name=$(printf '%s' "$probe"|tr '[:upper:]' '[:lower:]')
 java -cp "$results/classes" "dev.filmroom.engine.${probe}Probe" "$lib" "$sandbox" >"$results/jni-$name.json"
done
java -cp "$results/classes" dev.filmroom.engine.DetailProbe "$lib" "$sandbox" >"$results/jni-detail.json" 2>"$results/jni-detail.stderr"
echo 'Full CPU, legacy JNI and owned detail JNI gates passed'
