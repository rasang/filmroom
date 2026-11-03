#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source /workspace/shared/filmroom-export-speed-research/env.sh
export CARGO_TARGET_DIR="$PWD/target" RAYON_NUM_THREADS=2
export FILM_JXL_PREFIX=$("$PWD/third-party/libjxl/build.sh" host)
cd codecs
cargo test --locked --lib --no-run -j1 > ../results/build-test.log 2>&1
cargo test --locked --lib camera_raw -j1 > ../results/unit.log 2>&1
cargo build --locked --bin codec_probe --bin raw_variant_fault -j1 > ../results/build-bin.log 2>&1
cd ..
python run_first_gate.py > results/first-gate.log 2>&1
