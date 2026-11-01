#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-/workspace/shared/filmroom-roi-native-api-target}
export RAYON_NUM_THREADS=${RAYON_NUM_THREADS:-4}
results="$root/validation/owned-jni";mkdir -p "$results/classes"
cd "$root"
cargo build --offline --locked -p film-engine --features precision-f64 --lib >"$results/build.log" 2>&1
java --module jdk.compiler/com.sun.tools.javac.Main -d "$results/classes" tests/jni/dev/filmroom/engine/*.java
sandbox=$(mktemp -d "$results/sandbox.XXXXXX")
trap 'rm -rf -- "$sandbox"' EXIT
mkdir -p "$sandbox/files/engine-data" "$sandbox/cache/detail-preview/sources"
cp -R data/. "$sandbox/files/engine-data/"
cp DATA_MANIFEST.json "$sandbox/files/engine-data/manifest.json"
java -cp "$results/classes" dev.filmroom.engine.DetailProbe "$CARGO_TARGET_DIR/debug/libfilm_engine.so" "$sandbox" >"$results/detail.json" 2>"$results/detail.stderr"
echo 'Owned detail host JNI gates passed'
