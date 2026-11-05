package dev.filmroom.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The optional file exchange runs actual Android protocol code between native producer and consumer.
 * It is not satisfied by a Kotlin-generated native result or by forwarding raw JSON unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GpuStreamingCrossLanguageContractTest {
    private val fixtureOperations = mapOf(
        "optical-stream-v1" to GpuRenderOperation.FILM_OPTICS_STREAM_V1,
        "grain-stream-v1" to GpuRenderOperation.FILM_GRAIN_STREAM_V1,
        "dir-stream-v1" to GpuRenderOperation.FILM_DIR_STREAM_V1,
        "exposure-log-dir-stream-v1" to GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1,
        "scan-tail-stream-v1" to GpuRenderOperation.SCAN_TAIL_STREAM_V1,
        "finish-tail-stream-v1" to GpuRenderOperation.FINISH_TAIL_STREAM_V1,
        "highlight-stream-v1" to GpuRenderOperation.HIGHLIGHT_STREAM_V1)

    private fun nativeToSafe(raw: String, plan: GpuStreamingPlan): String {
        val native = GpuRenderProtocol.parseStageNativeResult(raw, plan)
        require(native.first == GpuRenderCode.OK && native.second != null)
        val encoded = GpuRenderProtocol.encodeResult(native.first, native.second)
        val decoded = GpuRenderProtocol.parseStageResult(encoded, plan)
        require(decoded.first == GpuRenderCode.OK && decoded.second != null)
        return GpuRenderProtocol.encodeResult(decoded.first, decoded.second).also {
            val result = JSONObject(it)
            assertEquals(setOf("schemaVersion", "code", "summary"), result.keys().asSequence().toSet())
            // Raw success is consumed at the native boundary, not silently reinterpreted as a stage seal.
            assertFalse(result.getJSONObject("summary").has("ok"))
            assertFalse(result.getJSONObject("summary").getBoolean("nativeStageSealed"))
        }
    }

    @Test fun rawSuccessFlagIsMandatoryButNeverPartOfTheSanitizedPayload() {
        for (op in GpuStreamingProtocol.operations) {
            val plan = GpuStreamingFixtures.parsed(op)
            val original = GpuStreamingFixtures.result(plan)
            val safe = nativeToSafe(original.toString(), plan)
            for (value in listOf<Any>(false, "true", 1, JSONObject.NULL)) {
                assertTrue(runCatching { nativeToSafe(JSONObject(original.toString()).put("ok", value).toString(), plan) }.isFailure)
            }
            val missing = JSONObject(original.toString()).apply { remove("ok") }
            assertTrue(runCatching { nativeToSafe(missing.toString(), plan) }.isFailure)
            for (value in listOf<Any>(true, false, JSONObject.NULL)) {
                val injected = JSONObject(safe).apply { getJSONObject("summary").put("ok", value) }
                assertTrue(runCatching { GpuRenderProtocol.parseStageResult(injected.toString(), plan) }.isFailure)
            }
        }
    }

    @Test fun nonSuccessOuterResultCannotCarryOrPublishAValidPayload() {
        for (op in GpuStreamingProtocol.operations) {
            val plan = GpuStreamingFixtures.parsed(op)
            val summary = JSONObject(nativeToSafe(GpuStreamingFixtures.result(plan).toString(), plan)).getJSONObject("summary")
            for (code in GpuRenderCode.entries.filter { it != GpuRenderCode.OK }) {
                val failure = JSONObject().put("schemaVersion", 1).put("code", code.name)
                val parsed = GpuRenderProtocol.parseStageResult(failure.toString(), plan)
                assertEquals(code, parsed.first); assertNull(parsed.second)
                assertTrue(runCatching { GpuRenderProtocol.parseStageResult(failure.put("summary", summary).toString(), plan) }.isFailure)
            }
        }
    }

    @Test fun actualNativeFixturesPassThroughAndroidSanitizerBeforeRustConsumption() = transformNativeFixtures()

    @Test fun actualNativeHighlightFixtureIsRequiredForTheSeventhOperationGate() = transformNativeFixtures("highlight-stream-v1")

    private fun transformNativeFixtures(requiredFixture: String? = null) {
        val inputSetting = System.getenv("FILMROOM_STREAM_RAW_SUMMARY_FIXTURE_DIR")
        val outputSetting = System.getenv("FILMROOM_STREAM_SAFE_SUMMARY_FIXTURE_DIR")
        assumeTrue("Cross-language execution requires native-produced raw fixtures and an explicit output directory",
            !inputSetting.isNullOrBlank() && !outputSetting.isNullOrBlank())
        val input = File(requireNotNull(inputSetting)).canonicalFile
        val output = File(requireNotNull(outputSetting)).canonicalFile
        require(input.isDirectory && input != output)
        require(output.isDirectory || output.mkdirs())
        val files = requireNotNull(input.listFiles()).filter { it.name.endsWith("-raw.json") }.sortedBy { it.name }
        require(files.isNotEmpty()) { "No native-produced raw summaries were supplied" }
        if (requiredFixture != null) require(files.any { it.name == "$requiredFixture-raw.json" }) {
            "The highlight gate requires an actual native-produced $requiredFixture-raw.json"
        }
        val transformed = files.map { file ->
            val base = file.name.removeSuffix("-raw.json")
            require(base in fixtureOperations && file.isFile && !Files.isSymbolicLink(file.toPath()))
            require(file.length() in 1..(2L * GpuRenderProtocol.MAX_JSON))
            val fixture = JSONObject(file.readText(Charsets.UTF_8))
            require(fixture.keys().asSequence().toSet() == setOf("plan", "nativeResult"))
            val envelope = fixture.getJSONObject("plan")
            val nativePlan = envelope.getJSONObject("plan")
            val operation = GpuRenderOperation.fromWire(nativePlan.get("operation"))
            require(fixtureOperations[base] == operation) { "Native fixture basename and operation do not match" }
            val token = GpuScienceSessionProtocol.integer(nativePlan, "token", 1)
            val plan = GpuRenderProtocol.parseStagePlan(envelope.toString(), token, operation) as GpuStreamingPlan
            val result = nativeToSafe(fixture.getJSONObject("nativeResult").toString(), plan)
            val transformed = JSONObject().put("plan", envelope).put("result", JSONObject(result))
            "$base-safe.json" to transformed.toString()
        }
        // No partial result is published until every supplied native case passed the real parser roundtrip.
        for ((name, contents) in transformed) {
            val temporary = Files.createTempFile(output.toPath(), ".stream-safe-", ".partial")
            try {
                Files.write(temporary, contents.toByteArray(Charsets.UTF_8))
                Files.move(temporary, File(output, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
