#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")"&&pwd)
java=${JAVA_HOME:?Set JAVA_HOME to an installed JDK 17 directory}
mkdir -p "$root/target/jni-classes"
"$java/bin/javac" -d "$root/target/jni-classes" "$root/tests/jni/dev/filmroom/codec/NativeCodecBridge.java" "$root/tests/jni/CodecSmoke.java"
"$java/bin/java" -cp "$root/target/jni-classes" CodecSmoke "$root/target/release/libfilm_codecs.so" "$root"
