package dev.filmroom.engine

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class GpuProbeProtocolTest {
    private fun planJson(): JSONObject {
        val n = 512L; val k = 3L; val pixels = 256L * 256
        val data = (n * n + k * k) * 4 + n * 8
        val device = 3 * n * n * 8 + data + pixels * 8 + 65536
        val host = data + k * k * 8 + n * 8 + pixels * 24 + 1024 * 1024
        return JSONObject().put("token", 73L).put("manifestSha256", "a".repeat(64))
            .put("originalWidth", 4000).put("originalHeight", 3000).put("fftSide", n).put("radius", 1)
            .put("roiWidth", 256).put("roiHeight", 256).put("channel", 1)
            .put("deviceBytes", device).put("hostBytes", host)
            .put("driverAllowanceBytes", GpuProbeProtocol.ALLOWANCE).put("helperAllowanceBytes", GpuProbeProtocol.ALLOWANCE)
            .put("combinedPeakBytes", device + host + 2 * GpuProbeProtocol.ALLOWANCE)
            .put("snapshotBytes", (n * n + k * k + pixels) * 8 + 8192)
    }
    private fun envelope(plan: JSONObject = planJson()) = JSONObject().put("ok", true).put("schemaVersion", 1)
        .put("operation", "planGpuDiffusionProbe").put("plan", plan).toString()
    private fun acceptedPlan() = GpuProbeProtocol.parsePlan(envelope(), 73)
    private fun result(): JSONObject {
        val plan = planJson()
        return JSONObject().put("ok", true).put("schemaVersion", 1).put("operation", "runGpuDiffusionProbe")
            .put("diagnosticOnly", true).put("outputReplaced", false).put("fullRenderMeasured", false)
            .put("originalWidth", 4000).put("originalHeight", 3000).put("fftSide", 512).put("radius", 1)
            .put("roiWidth", 256).put("roiHeight", 256).put("channel", 1).put("plan", plan)
            .put("bitExact", false).put("preparationMs", 2.0).put("initializationMs", 3.0).put("comparisonMs", 0.5).put("totalMs", 12.0)
            .put("metrics", JSONObject().put("adapter", "/private/photo-name.jpg SECRET_DRIVER_TEXT")
                .put("backend", "Vulkan research f32 FFT (not f64)").put("adapterDeviceType", "IntegratedGpu")
                .put("hardwareGpu", true).put("exactF64Claim", false).put("shaderF64Advertised", true)
                .put("vendor", 20803).put("stageTotalMs", 6.5).put("devicePayloadBytes", plan.getLong("deviceBytes"))
                .put("uploadBytes", 10000).put("readbackBytes", 1000).put("dispatches", 25).put("tiles", 1))
            .put("error", JSONObject().put("nonfiniteSamples", 0).put("changedSamples", 300).put("maxAbs", 0.1)
                .put("rms", 0.01).put("referencePeak", 1.0).put("maxAbsOverPeak", 0.1))
    }
    @Test fun exactPlanAccountsForHostDeviceDriverAndHelperWithoutRaisingBudget() {
        val plan = acceptedPlan()
        assertEquals(plan.deviceBytes + plan.hostBytes + 128 * NativeMemoryPolicy.MIB, plan.combinedPeakBytes)
        assertEquals(plan.combinedPeakBytes, GpuProbeProtocol.availableBudget(plan, NativeMemorySnapshot(2L shl 30, 0, false)))
        assertNull(GpuProbeProtocol.availableBudget(plan, NativeMemorySnapshot(512 * NativeMemoryPolicy.MIB, 0, false)))
        assertNull(GpuProbeProtocol.availableBudget(plan, NativeMemorySnapshot(2L shl 30, 0, true)))
        assertNull(GpuProbeProtocol.availableBudget(plan, NativeMemorySnapshot(Long.MAX_VALUE, -1, false)))
    }
    @Test fun rejectsOverflowUnderestimatedMissingOrMismatchedPlans() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("deviceBytes", Long.MAX_VALUE) }, { it.put("hostBytes", 1L) },
            { it.put("helperAllowanceBytes", 0L) }, { it.put("combinedPeakBytes", GpuProbeProtocol.COMBINED_CAP + 1) },
            { it.put("token", 74) }, { it.put("token", "73") }, { it.put("manifestSha256", "b".repeat(65)) },
            { it.put("fftSide", 513) }, { it.put("snapshotBytes", 1) }, { it.put("arbitraryPath", "/private") })
        mutations.forEach { mutation ->
            val p = planJson(); mutation(p)
            assertTrue(runCatching { GpuProbeProtocol.parsePlan(envelope(p), 73) }.isFailure)
        }
        assertThrows(ArithmeticException::class.java) { GpuProbeProtocol.validatePlan(acceptedPlan().copy(deviceBytes = Long.MAX_VALUE)) }
    }
    @Test fun stripsAllDriverProseTokensDigestsAndPathsFromIpcPersistenceAndDisplay() {
        val summary = GpuProbeProtocol.parseNativeResult(result().toString(), acceptedPlan())
        val safe = summary.toSafeJson()
        listOf("adapter", "SECRET", "/private", "photo-name", "manifestSha", "token", "a".repeat(64)).forEach { assertFalse(safe.contains(it)) }
        assertEquals(summary, GpuProbeSummary.fromSafeJson(safe))
        assertEquals(GpuProbeStatus.COMPLETE, summary.status)
        assertTrue(summary.displayText().contains("不代表整张导出速度"))
        assertTrue(safe.toByteArray().size <= GpuProbeProtocol.MAX_IPC_BYTES)
    }
    @Test fun rejectsNonfiniteMismatchedUnsafeOrUnqualifiedResults() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("plan").put("token", 74) }, { it.put("outputReplaced", true) },
            { it.put("fullRenderMeasured", true) }, { it.put("diagnosticOnly", false) }, { it.put("bitExact", true) },
            { it.getJSONObject("metrics").put("backend", "driver arbitrary text") },
            { it.getJSONObject("metrics").put("hardwareGpu", false) },
            { it.getJSONObject("error").put("rms", "NaN") }, { it.getJSONObject("error").put("nonfiniteSamples", 1) },
            { it.getJSONObject("error").put("changedSamples", 65537) }, { it.put("totalMs", -1) })
        mutations.forEach { mutation ->
            val value = result(); mutation(value)
            assertTrue(runCatching { GpuProbeProtocol.parseNativeResult(value.toString(), acceptedPlan()) }.isFailure)
        }
        assertTrue(runCatching { GpuProbeProtocol.parseNativeResult(" ".repeat(16385), acceptedPlan()) }.isFailure)
    }
    @Test fun unknownNativeErrorBecomesFixedStatusAndStoredJsonRejectsExtraFields() {
        val native = JSONObject().put("ok", false).put("schemaVersion", 1).put("diagnosticOnly", true).put("outputReplaced", false)
            .put("code", "/private/driver?token=secret")
        val summary = GpuProbeProtocol.parseNativeResult(native.toString(), acceptedPlan())
        assertEquals(GpuProbeStatus.NATIVE_FAILED, summary.status)
        assertFalse(summary.toSafeJson().contains("private"))
        assertNull(GpuProbeSummary.fromSafeJson(JSONObject(summary.toSafeJson()).put("token", 73).toString()))
        assertNull(GpuProbeSummary.fromSafeJson(JSONObject(summary.toSafeJson()).put("status", "RAW_DRIVER_FAILURE").toString()))
        assertNull(GpuProbeSummary.fromSafeJson(" ".repeat(4097)))
    }
}
