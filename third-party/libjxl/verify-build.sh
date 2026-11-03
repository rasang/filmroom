#!/usr/bin/env bash
# Checks static archive integration. Android execution is a separate device gate.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
TARGET=${1:-host}
BUILD_ROOT=${LIBJXL_BUILD_ROOT:-$ROOT/.build}
PREFIX="$BUILD_ROOT/$TARGET/prefix"
CHECK="$BUILD_ROOT/$TARGET/check"
mkdir -p "$CHECK"
case "$TARGET" in
  host) CXX=${CXX:-c++}; READELF=${READELF:-readelf}; EXTRA=(-pthread) ;;
  arm64-v8a|x86_64)
    NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
    BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
    TRIPLE=aarch64-linux-android
    [[ "$TARGET" != x86_64 ]] || TRIPLE=x86_64-linux-android
    CXX="$BIN/$TRIPLE${ANDROID_API:-26}-clang++"
    READELF="$BIN/llvm-readelf"
    EXTRA=(-static-libstdc++ -llog)
    ;;
  *) echo "Usage: $0 [host|arm64-v8a|x86_64]" >&2; exit 2 ;;
esac
cat > "$CHECK/smoke.cc" <<'CPP'
#include <jxl/cms.h>
#include <jxl/decode.h>
#include <jxl/thread_parallel_runner.h>
extern "C" __attribute__((visibility("default"))) int filmroom_jxl_build_smoke() {
  if (JxlDecoderVersion() != 12000) return 1;
  JxlDecoder* dec = JxlDecoderCreate(nullptr);
  if (!dec) return 2;
  void* runner = JxlThreadParallelRunnerCreate(nullptr, 0);
  if (!runner) { JxlDecoderDestroy(dec); return 3; }
  int result = 0;
  if (JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner) != JXL_DEC_SUCCESS) result = 4;
  JxlDecoderSetCms(dec, *JxlGetDefaultCms());
  if (JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO) != JXL_DEC_SUCCESS) result = 5;
  if (JxlDecoderProcessInput(dec) != JXL_DEC_NEED_MORE_INPUT) result = 6;
  JxlDecoderDestroy(dec);
  JxlThreadParallelRunnerDestroy(runner);
  return result;
}
int main() { return filmroom_jxl_build_smoke(); }
CPP
LIBS=(
  "$PREFIX/lib/libjxl_dec.a" "$PREFIX/lib/libjxl_threads.a"
  "$PREFIX/lib/libjxl_cms.a" "$PREFIX/lib/libhwy.a"
  "$PREFIX/lib/libbrotlidec.a" "$PREFIX/lib/libbrotlicommon.a"
)
COMMON=(-std=c++17 -O2 -fPIC -DJXL_STATIC_DEFINE -DJXL_THREADS_STATIC_DEFINE -DJXL_CMS_STATIC_DEFINE "-I$PREFIX/include")
"$CXX" "${COMMON[@]}" "$CHECK/smoke.cc" "${LIBS[@]}" "${EXTRA[@]}" -lm \
  -shared -Wl,--no-undefined -Wl,--exclude-libs=ALL -Wl,-z,max-page-size=16384 -o "$CHECK/libjxl_smoke.so"
"$READELF" -lW "$CHECK/libjxl_smoke.so" > "$CHECK/program-headers.txt"
"$READELF" -dW "$CHECK/libjxl_smoke.so" > "$CHECK/dynamic.txt"
python3 - "$CHECK/program-headers.txt" "$CHECK/dynamic.txt" <<'PY'
from pathlib import Path
import sys
loads = [line.split() for line in Path(sys.argv[1]).read_text().splitlines() if line.strip().startswith("LOAD ")]
assert loads and all(int(line[-1], 16) >= 16384 for line in loads), "ELF load segment alignment below 16 KiB"
dynamic = Path(sys.argv[2]).read_text()
assert "libjxl" not in dynamic and "libc++_shared" not in dynamic, "Unexpected dynamic codec/C++ dependency"
print("PASS: static JPEG XL linkage, no undefined symbols, >=16 KiB PT_LOAD alignment")
PY
if [[ "$TARGET" == host ]]; then
  "$CXX" "${COMMON[@]}" "$CHECK/smoke.cc" "${LIBS[@]}" "${EXTRA[@]}" -lm -o "$CHECK/smoke"
  "$CHECK/smoke"
  echo 'PASS: host decoder version, allocation, thread runner, CMS and input state smoke test'
else
  echo "PASS: $TARGET cross-link checks (not executed on an Android device)"
fi
