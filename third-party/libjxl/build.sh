#!/usr/bin/env bash
# Offline, checksum-verified build of unmodified upstream libjxl decoder sources.
# stdout: the absolute prefix containing include/ and lib/. Diagnostics: stderr.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
TARGET=${1:-host}
case "$TARGET" in
  host|arm64-v8a|x86_64) ;;
  *) echo "Usage: $0 [host|arm64-v8a|x86_64]" >&2; exit 2 ;;
esac

CMAKE=${CMAKE:-cmake}
NINJA=${NINJA:-ninja}
JOBS=${LIBJXL_JOBS:-2}
if ! [[ "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
  echo 'LIBJXL_JOBS must be a positive integer.' >&2; exit 2
fi
command -v "$CMAKE" >/dev/null || { echo 'CMake 3.16+ is required; see README.md.' >&2; exit 2; }
NINJA=$(command -v "$NINJA") || { echo 'Ninja is required; see README.md.' >&2; exit 2; }

cd "$ROOT"
sha256sum --check SHA256SUMS >&2
SOURCE_KEY=$(sha256sum SHA256SUMS | cut -d' ' -f1)
if [[ ! -f .source/.filmroom-source-key ]] || [[ $(cat .source/.filmroom-source-key) != "$SOURCE_KEY" ]]; then
  if [[ -e .source ]]; then
    echo 'Existing extracted sources have no matching manifest. Remove only third-party/libjxl/.source and retry.' >&2
    exit 2
  fi
  STAGE=$(mktemp -d "$ROOT/.source-tmp-XXXXXXXX")
  trap 'rm -rf -- "$STAGE"' EXIT
  tar xzf archives/libjxl-v0.12.0.tar.gz --strip-components=1 -C "$STAGE"
  for ITEM in \
      'brotli 028fb5a23661f123017c060daa546b55cf4bde29' \
      'highway 457c891775a7397bdb0376bb1031e6e027af1c48' \
      'skcms 96d9171c94b937a1b5f0293de7309ac16311b722'; do
    read -r NAME REVISION <<< "$ITEM"
    mkdir -p "$STAGE/third_party/$NAME"
    tar xzf "archives/$NAME-$REVISION.tar.gz" --strip-components=1 -C "$STAGE/third_party/$NAME"
  done
  printf '%s\n' "$SOURCE_KEY" > "$STAGE/.filmroom-source-key"
  mv "$STAGE" .source
  trap - EXIT
fi

BUILD_ROOT=${LIBJXL_BUILD_ROOT:-$ROOT/.build}
mkdir -p "$BUILD_ROOT"
BUILD_ROOT=$(cd "$BUILD_ROOT" && pwd)
BUILD="$BUILD_ROOT/$TARGET"
PREFIX="$BUILD/prefix"
ARGS=(
  -G Ninja "-DCMAKE_MAKE_PROGRAM=$NINJA"
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  '-DCMAKE_C_FLAGS_RELEASE=-O2 -DNDEBUG -ffunction-sections -fdata-sections'
  '-DCMAKE_CXX_FLAGS_RELEASE=-O2 -DNDEBUG -ffunction-sections -fdata-sections'
  -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DJPEGXL_VERSION=0.12.0
  -DJPEGXL_ENABLE_TOOLS=OFF -DJPEGXL_ENABLE_DEVTOOLS=OFF
  -DJPEGXL_ENABLE_DOXYGEN=OFF -DJPEGXL_ENABLE_MANPAGES=OFF
  -DJPEGXL_ENABLE_BENCHMARK=OFF -DJPEGXL_ENABLE_EXAMPLES=OFF
  -DJPEGXL_ENABLE_FUZZERS=OFF -DJPEGXL_ENABLE_JNI=OFF
  -DJPEGXL_ENABLE_SJPEG=OFF -DJPEGXL_ENABLE_OPENEXR=OFF
  -DJPEGXL_ENABLE_VIEWERS=OFF -DJPEGXL_ENABLE_PLUGINS=OFF
  -DJPEGXL_ENABLE_TCMALLOC=OFF -DJPEGXL_ENABLE_TRANSCODE_JPEG=OFF
  -DJPEGXL_ENABLE_BOXES=ON -DJPEGXL_ENABLE_SKCMS=ON
)
if [[ "$TARGET" != host ]]; then
  NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
  if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo 'Set ANDROID_NDK_HOME to Android NDK r27c or newer.' >&2; exit 2
  fi
  ARGS+=(
    "-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake"
    "-DANDROID_ABI=$TARGET" "-DANDROID_PLATFORM=android-${ANDROID_API:-26}"
    -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384'
    '-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384'
  )
fi
"$CMAKE" -S "$ROOT/.source" -B "$BUILD" "${ARGS[@]}" >&2
# Explicit target list excludes the encoder and all unrelated image/tool plugins.
"$CMAKE" --build "$BUILD" --target jxl_dec jxl_threads jxl_cms brotlidec brotlicommon hwy -j "$JOBS" >&2
mkdir -p "$PREFIX/include/jxl" "$PREFIX/lib" "$PREFIX/licenses"
cp "$BUILD/lib/include/jxl/"*.h "$PREFIX/include/jxl/"
cp "$BUILD/lib/libjxl_dec.a" "$BUILD/lib/libjxl_threads.a" "$BUILD/lib/libjxl_cms.a" "$PREFIX/lib/"
cp "$BUILD/third_party/highway/libhwy.a" "$BUILD/third_party/brotli/libbrotlidec.a" "$BUILD/third_party/brotli/libbrotlicommon.a" "$PREFIX/lib/"
cp "$ROOT/licenses/"* "$PREFIX/licenses/"
printf '%s\n' 'libjxl 0.12.0' "target=$TARGET" "manifest_sha256=$SOURCE_KEY" > "$PREFIX/build-info.txt"
printf '%s\n' "$PREFIX"
