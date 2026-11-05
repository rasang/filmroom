package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuScienceSessionProtocolTest {
    private val budget = GpuScienceSessionBudget(1000, 100, 8)
    private fun stats() = JSONObject().put("epoch", 7).put("prepaidBytes", 1000).put("fixedBytes", 100)
        .put("chargedBytes", 400).put("pipelineSlots", 2).put("poolBytes", 200)
        .put("pipelineHits", 3).put("bufferHits", 4).put("clearSubmissions", 2)
    private fun parse(v: JSONObject, prior: GpuScienceSessionStats? = null) = GpuScienceSessionProtocol.stats(v, 7, budget, prior)
    @Test fun statsRequireExactKeysAndTrueIntegers() {
        assertEquals(2L, parse(stats()).clearSubmissions)
        assertTrue(runCatching { parse(stats().put("adapter", "raw text")) }.isFailure)
        assertTrue(runCatching { parse(stats().apply { remove("clearSubmissions") }) }.isFailure)
        assertTrue(runCatching { parse(stats().put("poolBytes", "200")) }.isFailure)
        assertTrue(runCatching { parse(stats().put("pipelineHits", 3.5)) }.isFailure)
    }
    @Test fun statsAreBoundedByPrepaymentAndMonotonic() {
        val previous = parse(stats())
        for ((key, value) in listOf("epoch" to 8L, "prepaidBytes" to 1001L, "fixedBytes" to 99L,
            "chargedBytes" to 1001L, "pipelineSlots" to 9L, "poolBytes" to 301L)) {
            assertTrue(key, runCatching { parse(stats().put(key, value)) }.isFailure)
        }
        for ((key, value) in listOf("chargedBytes" to 399L, "pipelineSlots" to 1L, "poolBytes" to 199L,
            "pipelineHits" to 2L, "bufferHits" to 3L, "clearSubmissions" to 1L)) {
            assertTrue(key, runCatching { parse(stats().put(key, value), previous) }.isFailure)
        }
        assertEquals(5L, parse(stats().put("bufferHits", 5), previous).bufferHits)
    }
    @Test fun acceptedAndPollPreserveTicketAndObjectSummary() {
        val ticket = GpuScienceStageTicket(7, 11, 13, 17, "a".repeat(64))
        val accepted = JSONObject(GpuScienceSessionProtocol.accepted(ticket))
        assertEquals(2, accepted.getInt("schemaVersion")); assertTrue(accepted.getBoolean("accepted"))
        assertEquals(11L, accepted.getLong("frameGeneration")); assertEquals(13L, accepted.getLong("sequence"))
        assertEquals(ticket.manifestSha256, accepted.getString("manifestSha256"))
        val poll = JSONObject(GpuScienceSessionProtocol.poll(ticket, "success_sealed", 30, 30, "OK", "{\"gpuUsed\":true,\"uploadBytes\":12}"))
        assertEquals(2, poll.getInt("schemaVersion")); assertEquals(17L, poll.getLong("token"))
        assertEquals(12L, poll.getJSONObject("summary").getLong("uploadBytes"))
        assertFalse(poll.has("stageStopped")); assertFalse(poll.has("helperDead"))
        assertTrue(runCatching { GpuScienceSessionProtocol.poll(ticket, "success_stopped", 30, 30, "OK", null) }.isFailure)
        assertTrue(runCatching { GpuScienceSessionProtocol.poll(ticket, "pending", 31, 30, "OK", null) }.isFailure)
    }
    @Test fun boundedJsonAndConfigurationRejectInvalidAdmissions() {
        assertTrue(runCatching { GpuScienceSessionProtocol.objectOf(" ".repeat(16385)) }.isFailure)
        assertTrue(runCatching { GpuScienceSessionProtocol.configuration(7, GpuScienceSessionBudget(1, 2, 8)) }.isFailure)
        val v = JSONObject(GpuScienceSessionProtocol.configuration(7, budget))
        GpuScienceSessionProtocol.exact(v, setOf("schemaVersion", "epoch", "prepaidBytes", "fixedBytes", "pipelineLimit"))
        assertEquals(1000L, v.getLong("prepaidBytes"))
    }
}
