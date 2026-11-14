#!/usr/bin/env bash
# Narrow host test of the real CodecDiagnosticDetail.kt. Does NOT cover Android,
# NativeCodecBridge, OperationTrace, SafeInfo, or full diagnostic report serialization.
# Requires JDK 17+, Kotlin compiler/stdlib jars (tested: Gradle 8.11.1 lib, Kotlin 2.0.20)
# and org.json (tested 20240303). No dependencies downloaded by this script.
# Usage: KOTLIN_LIB=/path/to/gradle/lib JSON_JAR=/path/to/json.jar bash tools/diagnostics/test-codec-detail-host.sh
set -euo pipefail
: "${KOTLIN_LIB:?directory containing Kotlin compiler and stdlib jars required}"
: "${JSON_JAR:?path to org.json jar required}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
stdlib="$(find "$KOTLIN_LIB" -maxdepth 1 -name 'kotlin-stdlib-[0-9]*.jar' -print -quit)"
test -n "$stdlib"
cat > "$tmp/Main.kt" <<'KOTLIN'
package dev.filmroom.diagnostics
import org.json.JSONObject
fun main() {
    fun valid() = JSONObject().put("diagnosticSchema",1).put("reason","D65_MATRIX_MISSING").put("source","DNG").put("line",375).put("column",20).put("tag",50721)
    val detail=checkNotNull(CodecDiagnosticDetail.fromNative(valid()))
    check(detail==CodecDiagnosticDetail.fromFields(detail.fields().mapValues {it.value.toString()}))
    for(k in listOf("reason","source")) for(v in listOf<Any>("content://private", "", true, 1, JSONObject.NULL))
        check(CodecDiagnosticDetail.fromNative(valid().put(k,v))==null)
    for(k in listOf("line","column")) for(v in listOf<Any>(0,-1,100001,1.0,"1",true,JSONObject.NULL))
        check(CodecDiagnosticDetail.fromNative(valid().put(k,v))==null)
    for(v in listOf<Any>(34853,1,"50721",50721.0,true,JSONObject().put("gps",22.3)))
        check(!checkNotNull(CodecDiagnosticDetail.fromNative(valid().put("tag",v))).fields().containsKey("codecTag"))
    val fields=detail.fields().mapValues {it.value.toString()}
    for(k in listOf("codecLine","codecColumn")) for(v in listOf("1.0","1e2","+1"," 1","-1","100001","secret"))
        check(CodecDiagnosticDetail.fromFields(fields+(k to v))==null)
    check(CodecDiagnosticDetail.fromNative(valid().put("message","secret").put("gps",22.3))!!.fields().size==6)
    for(v in listOf<Any>(0,2,"1",1.0,true,JSONObject.NULL))
        check(CodecDiagnosticDetail.fromNative(valid().put("diagnosticSchema",v))==null)
    val other=checkNotNull(CodecDiagnosticDetail.fromNative(valid().put("source","OTHER")))
    check(other.fields()["codecLine"]==375L && other.fields()["codecColumn"]==20L)
    check(CodecDiagnosticDetail.fromNative(JSONObject().put("code","INVALID_COLOR_METADATA").put("stage","probe"))==null)
    println("PASS CodecDiagnosticDetail: strict native types, identities, coordinate bounds, tag whitelist, persisted revalidation, roundtrip and secret exclusion")
}

KOTLIN
java -cp "$KOTLIN_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect     -classpath "$stdlib:$JSON_JAR" -d "$tmp/out"     "$root/app/src/main/java/dev/filmroom/diagnostics/CodecDiagnosticDetail.kt" "$tmp/Main.kt"
java -cp "$tmp/out:$stdlib:$JSON_JAR" dev.filmroom.diagnostics.MainKt
