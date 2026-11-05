package dev.filmroom.diagnostics

import dev.filmroom.engine.GpuRenderOperation
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class NativeGpuLifecycleParserTest {
    private fun raw(): String = """{"schemaVersion":1,"token":45,"stageCode":8,"sequence":12,"phaseCode":3,"stateCode":1,"elapsedMs":17,"totalElapsedMs":100,"terminalCode":0,"diagnosticCode":0,"activityCode":1,"stepCode":4,"failureCode":0,"events":[{"sequence":12,"phaseCode":3,"stateCode":1,"elapsedMs":17,"totalElapsedMs":100,"activityCode":1,"stepCode":4}]}"""

    @Test fun frozenNativeFixtureAndSafeFailureLegendMatchExactly() {
        val loader = javaClass.classLoader!!
        val raw = loader.getResourceAsStream("gpu-native-phase-v1.json")!!.bufferedReader().use { it.readText() }
        val sample = NativeGpuLifecycleParser.parse(raw)
        assertNotNull(sample)
        val legend = JSONObject(loader.getResourceAsStream("gpu-native-failure-codes-v1.json")!!.bufferedReader().use { it.readText() }).getJSONArray("failureCodes")
        assertEquals(NativeGpuFailureLegend.MAX_CODE + 1, legend.length())
        for (index in 0 until legend.length()) {
            val entry = legend.getJSONObject(index)
            assertEquals(index, entry.getInt("code"))
            assertEquals(entry.getString("name"), NativeGpuFailureLegend.name(index))
            assertTrue(NativeGpuFailureLegend.name(index).matches(Regex("[A-Z][A-Z0-9_]*")))
        }
    }

    @Test fun helperRecorderNeedsNoDiagnosticStoreAndNeverSynchronouslyWritesOnMain() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = Diagnostics::class.java.getDeclaredField("store").apply { isAccessible = true }
        assertNull(store.get(Diagnostics))
        assertFalse(GpuLifecycleRecorder.recordBeforeBlocking(context, 99, GpuRenderOperation.FILM_GRAIN, GpuLifecyclePhase.NATIVE_ENTER))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(executor.submit<Boolean> {
                GpuLifecycleRecorder.recordBeforeBlocking(context, 99, GpuRenderOperation.FILM_GRAIN, GpuLifecyclePhase.NATIVE_ENTER)
            }.get(3, java.util.concurrent.TimeUnit.SECONDS))
            val snapshot = executor.submit<RenderLifecycleSnapshot> {
                GpuLifecycleRecorder.snapshot(5, System.currentTimeMillis())
            }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(snapshot.events.any { it.token == 99L && it.phase == GpuLifecyclePhase.NATIVE_ENTER })
            assertNull(store.get(Diagnostics))
        } finally { executor.shutdownNow() }
    }

    @Test fun strictNumericSnapshotPreservesScopeStepAndNativeTerminal() {
        val sample = checkNotNull(NativeGpuLifecycleParser.parse(raw()))
        assertEquals(45L, sample.token); assertEquals(GpuRenderOperation.FILM_GRAIN, sample.operation)
        assertEquals(NativeGpuPhase.PIPELINE, sample.native.phase)
        assertEquals(NativeGpuStep.COMPUTE_PIPELINE, sample.native.step)
        assertEquals(NativeGpuActivity.SYNTHETIC_ADMISSION, sample.native.activity)
        assertEquals(NativeGpuTerminal.ACTIVE, sample.native.terminal)
        val terminal = NativeGpuLifecycleParser.parse(raw().replace("\"terminalCode\":0", "\"terminalCode\":4").replace("\"failureCode\":0", "\"failureCode\":123"))!!
        assertEquals(NativeGpuTerminal.PANIC, terminal.native.terminal)
        assertEquals(123, terminal.native.failureCode)
    }

    @Test fun compoundStageHasDistinctStableNativeIdAndJournalIdentity() {
        val sample = requireNotNull(NativeGpuLifecycleParser.parse(raw().replace("\"stageCode\":8", "\"stageCode\":14")))
        assertEquals(GpuRenderOperation.EXPOSURE_LOG_DIR, sample.operation)
        val event = RenderLifecycleEvent(1_800_000_000_000L, 500, "a".repeat(32), 123, 1, 45,
            RenderLifecycleScope.GPU_STAGE, sample.operation, GpuLifecyclePhase.NATIVE_PHASE, native = sample.native)
        assertEquals(event, RenderLifecycleEvent.parse(event.line()))
        assertTrue(event.json().contains("EXPOSURE_LOG_DIR"))
    }

    @Test fun rejectsPrivateUnknownDuplicateCoercedOversizedAndOutOfRangeInputs() {
        val invalid = listOf(
            raw().replace("\"stageCode\":8", "\"stageCode\":\"private/path\""),
            raw().replace("\"token\":45", "\"token\":45,\"token\":46"),
            raw().replace("\"token\":45", "\"token\":\"45\""),
            raw().replace("\"token\":45", "\"token\":45.0"),
            raw().replace("\"token\":45", "\"token\":-1"),
            raw().replace("\"stageCode\":8", "\"stageCode\":22"),
            raw().replace("\"phaseCode\":3", "\"phaseCode\":10"),
            raw().replace("\"stepCode\":4", "\"stepCode\":6"),
            raw().replace("\"activityCode\":1", "\"activityCode\":2"),
            raw().replace("\"terminalCode\":0", "\"terminalCode\":5"),
            raw().replace("\"diagnosticCode\":0", "\"diagnosticCode\":2"),
            raw().replace("\"failureCode\":0", "\"failureCode\":256"),
            raw().replace("\"elapsedMs\":17", "\"elapsedMs\":999999999999999999"),
            raw().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"message\":\"secret photo GPS\""),
            raw() + "secret", " ".repeat(NativeGpuLifecycleParser.MAX_BYTES + 1),
        )
        invalid.forEach { assertNull(NativeGpuLifecycleParser.parse(it)) }
    }

    @Test fun eventCountAndEventFieldsAreBoundedEvenThoughOnlyLatestSampleIsExported() {
        val event = JSONObject(raw()).getJSONArray("events").getJSONObject(0)
        val maximum = JSONObject(raw()).put("events", org.json.JSONArray().apply { repeat(32) { put(event) } }).toString()
        assertNotNull(NativeGpuLifecycleParser.parse(maximum))
        val tooMany = JSONObject(raw()).put("events", org.json.JSONArray().apply { repeat(33) { put(event) } }).toString()
        assertNull(NativeGpuLifecycleParser.parse(tooMany))
        assertNull(NativeGpuLifecycleParser.parse(raw().replace("\"events\":[{", "\"events\":[{\"filename\":\"private.jpg\",")))
        assertNotNull(NativeGpuLifecycleParser.parse(raw().replace(",\"stepCode\":4", "").replace(",\"failureCode\":0", "")))
    }

    @Test fun sharedReportContainsReparsedLifecycleAndNeverRawSourceFiles() {
        val root = Files.createTempDirectory("lifecycle-share-").toFile()
        val shares = Files.createTempDirectory("lifecycle-shares-").toFile()
        try {
            val sample = NativeGpuLifecycleParser.parse(raw())!!
            RenderLifecycleJournal(root).use { journal ->
                assertTrue(journal.append(RenderLifecycleEvent(1_800_000_000_000, 1000, "a".repeat(32), 1, 1, sample.token,
                    RenderLifecycleScope.GPU_STAGE, sample.operation, GpuLifecyclePhase.NATIVE_PHASE, native = sample.native, parentToken = 99)))
            }
            java.io.File(root, "unrelated-photo-secret.jpg").writeText("private-image-bytes")
            val lifecycle = RenderLifecycleJournal.snapshot(root, 5, 1_800_000_000_000)
            val snapshot = DiagnosticSnapshot(5, 1_800_000_000_000, 1000, emptyList(), emptyList(), 0, false, true, renderLifecycle = lifecycle)
            val report = DiagnosticArchive.write(shares, snapshot, DiagnosticMetadata()).file.readText()
            assertTrue(report.contains("RENDER LIFECYCLE V1")); assertTrue(report.contains("SYNTHETIC_ADMISSION")); assertTrue(report.contains("COMPUTE_PIPELINE"))
            assertFalse(report.contains("unrelated-photo-secret")); assertFalse(report.contains("private-image-bytes"))
            val json = report.substringAfter("=== BEGIN DIAGNOSTICS JSON V1 ===\n").substringBefore("\n=== END DIAGNOSTICS JSON V1 ===")
            val event = JSONObject(json).getJSONObject("renderLifecycle").getJSONArray("events").getJSONObject(0)
            assertEquals(99L, event.getLong("parentToken")); assertEquals("FILM_GRAIN", event.getString("operation"))
            assertEquals("COMPUTE_PIPELINE", event.getJSONObject("native").getString("step"))
        } finally { root.deleteRecursively(); shares.deleteRecursively() }
    }
    @Test fun streamingStagesHaveDistinctStableIdsWithoutReusingResidentIdentity() {
        val operations = listOf(GpuRenderOperation.FILM_OPTICS_STREAM_V1, GpuRenderOperation.FILM_GRAIN_STREAM_V1,
            GpuRenderOperation.FILM_DIR_STREAM_V1, GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1,
            GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1, GpuRenderOperation.HIGHLIGHT_STREAM_V1)
        for ((index, operation) in operations.withIndex()) {
            val sample = requireNotNull(NativeGpuLifecycleParser.parse(raw().replace("\"stageCode\":8", "\"stageCode\":${15 + index}")))
            assertEquals(operation, sample.operation)
            val event = RenderLifecycleEvent(1_800_000_000_000L, 500, "a".repeat(32), 123, 1, 45,
                RenderLifecycleScope.GPU_STAGE, operation, GpuLifecyclePhase.NATIVE_PHASE, native = sample.native)
            assertEquals(event, RenderLifecycleEvent.parse(event.line()))
        }
    }

    @Test fun highlightFailureIdsAppendWithoutRenumberingAndRejectUnknownValues() {
        assertEquals(244, NativeGpuFailureLegend.MAX_CODE)
        assertEquals("STALE_STAGE_PLAN", NativeGpuFailureLegend.name(229))
        for (code in 230..244) {
            val message = raw().replace("\"stageCode\":8", "\"stageCode\":21")
                .replace("\"failureCode\":0", "\"failureCode\":$code")
            val sample = requireNotNull(NativeGpuLifecycleParser.parse(message))
            assertEquals(GpuRenderOperation.HIGHLIGHT_STREAM_V1, sample.operation)
            assertEquals(code, sample.native.failureCode)
        }
        assertNull(NativeGpuLifecycleParser.parse(raw().replace("\"failureCode\":0", "\"failureCode\":245")))
    }

}
