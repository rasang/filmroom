package dev.filmroom.engine

import org.json.JSONObject

internal data class GpuScienceSessionStats(
    val epoch: Long, val prepaidBytes: Long, val fixedBytes: Long,
    val chargedBytes: Long, val pipelineSlots: Long, val poolBytes: Long,
    val pipelineHits: Long, val bufferHits: Long, val clearSubmissions: Long,
)

internal object GpuScienceSessionProtocol {
    private val statsKeys = setOf("epoch", "prepaidBytes", "fixedBytes", "chargedBytes", "pipelineSlots", "poolBytes", "pipelineHits", "bufferHits", "clearSubmissions")
    fun objectOf(raw: String): JSONObject {
        require(raw.length <= 16384 && raw.toByteArray(Charsets.UTF_8).size <= 16384)
        return JSONObject(raw)
    }
    fun exact(value: JSONObject, keys: Set<String>) {
        require(value.keys().asSequence().toSet() == keys)
    }
    fun integer(value: JSONObject, key: String, minimum: Long = 0L, maximum: Long = Long.MAX_VALUE): Long {
        val raw = value.get(key)
        require(raw is Int || raw is Long)
        val n = (raw as Number).toLong()
        require(n in minimum..maximum)
        return n
    }
    fun version(value: JSONObject, epoch: Long) {
        require(integer(value, "schemaVersion") == 1L)
        require(integer(value, "epoch", 1L) == epoch)
    }
    fun control(epoch: Long): JSONObject = JSONObject().put("schemaVersion", 1).put("epoch", epoch)
    fun configuration(epoch: Long, budget: GpuScienceSessionBudget): String {
        budget.validate()
        return control(epoch).put("prepaidBytes", budget.prepaidBytes).put("fixedBytes", budget.fixedBytes)
            .put("pipelineLimit", budget.pipelineLimit).toString()
    }
    fun stats(value: JSONObject, epoch: Long, budget: GpuScienceSessionBudget, previous: GpuScienceSessionStats?): GpuScienceSessionStats {
        exact(value, statsKeys)
        val result = GpuScienceSessionStats(integer(value, "epoch", 1L), integer(value, "prepaidBytes", 1L),
            integer(value, "fixedBytes", 1L), integer(value, "chargedBytes"), integer(value, "pipelineSlots"),
            integer(value, "poolBytes"), integer(value, "pipelineHits"), integer(value, "bufferHits"), integer(value, "clearSubmissions"))
        require(result.epoch == epoch && result.prepaidBytes == budget.prepaidBytes && result.fixedBytes == budget.fixedBytes)
        require(result.chargedBytes in budget.fixedBytes..budget.prepaidBytes)
        require(result.poolBytes <= result.chargedBytes - budget.fixedBytes)
        require(result.pipelineSlots <= budget.pipelineLimit.toLong())
        if (previous != null) {
            require(result.chargedBytes >= previous.chargedBytes && result.poolBytes >= previous.poolBytes)
            require(result.pipelineSlots >= previous.pipelineSlots && result.pipelineHits >= previous.pipelineHits)
            require(result.bufferHits >= previous.bufferHits && result.clearSubmissions >= previous.clearSubmissions)
        }
        return result
    }
    fun accepted(ticket: GpuScienceStageTicket): String = JSONObject().put("schemaVersion", 2).put("accepted", true)
        .put("epoch", ticket.epoch).put("frameGeneration", ticket.frameGeneration).put("sequence", ticket.sequence)
        .put("token", ticket.token).put("manifestSha256", ticket.manifestSha256).toString()
    fun rejected(code: String): String = JSONObject().put("schemaVersion", 2).put("accepted", false).put("code", code).toString()
    fun poll(ticket: GpuScienceStageTicket, status: String, completed: Long, total: Long, code: String, summary: String?): String {
        require(status in setOf("pending", "running", "success_sealed", "failed_dead", "cancelled_dead"))
        require(completed in 0L..total)
        val result = JSONObject().put("schemaVersion", 2).put("epoch", ticket.epoch).put("frameGeneration", ticket.frameGeneration)
            .put("sequence", ticket.sequence).put("token", ticket.token).put("status", status)
            .put("completedPixels", completed).put("totalPixels", total).put("code", code)
        if (summary != null) result.put("summary", JSONObject(summary))
        return result.toString()
    }
}
