package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fixtures are produced by production Rust prepare/plan functions, never hand-calculated Kotlin copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class GpuNativePlanContractTest {
    private val fixtures=listOf("camera.json","scanner.json","optical.json","exposure.json","dir.json","grain.json","auto-exposure.json","highlight-boost.json","exposure-log.json","print-exposure.json","print-diffusion.json","scan-tail.json","finish-tail.json")
    private fun envelope(name:String):String = requireNotNull(javaClass.classLoader!!.getResourceAsStream("gpu-contracts/$name")){"Missing executed native plan fixture: $name"}.bufferedReader().use{it.readText()}
    @Test fun everyExecutedNativePlanPassesActualAndroidValidator(){
        val operations=mutableSetOf<GpuRenderOperation>()
        for(name in fixtures){
            val raw=envelope(name);val plan=JSONObject(raw).getJSONObject("plan")
            val operation=GpuRenderOperation.fromWire(plan.get("operation"))
            assertTrue("Duplicate stage fixture",operations.add(operation))
            val parsed=GpuRenderProtocol.parseStagePlan(raw,plan.getLong("token"),operation)
            assertEquals(plan.getLong("combinedPeakBytes"),parsed.combinedPeakBytes)
            assertEquals(plan.getLong("diskBytes"),parsed.diskBytes)
            assertEquals(plan.getString("manifestSha256"),parsed.digest)
        }
        assertEquals(GpuRenderOperation.entries.toSet(),operations)
    }
    @Test fun executedSequentialMtPlanKeepsDistinctOriginalRandomStream(){
        val raw=envelope("grain-mt.json");val native=JSONObject(raw).getJSONObject("plan")
        val plan=GpuFilmGrainProtocol.parsePlan(raw,native.getLong("token"))
        assertEquals(GpuGrainRngMode.SEQUENTIAL_MT,plan.rngMode)
        assertEquals(minOf(256,plan.width*plan.height),plan.chunkPixels)
        val fake=JSONObject(raw);fake.getJSONObject("plan").put("rngMode","coordinateSplitMix64")
        assertTrue(runCatching{GpuFilmGrainProtocol.parsePlan(fake.toString(),plan.token)}.isFailure)
    }
    @Test fun undersizedOrMismatchedNativePlanFailsClosed(){
        for(name in fixtures){
            val original=JSONObject(envelope(name));val plan=original.getJSONObject("plan")
            val operation=GpuRenderOperation.fromWire(plan.get("operation"));val token=plan.getLong("token")
            val smaller=JSONObject(original.toString());smaller.getJSONObject("plan").put("diskBytes",1)
            assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(smaller.toString(),token,operation)}.isFailure)
            val memory=JSONObject(original.toString());memory.getJSONObject("plan").put("hostBytes",1)
            assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(memory.toString(),token,operation)}.isFailure)
            assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(original.toString(),token+1,operation)}.isFailure)
        }
    }
}
