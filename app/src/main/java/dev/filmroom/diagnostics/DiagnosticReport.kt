package dev.filmroom.diagnostics

import java.io.File

data class DiagnosticShare(val file: File, val summary: String, val eventCount: Int, val windowMinutes: Int)

/** Metadata is numeric/enum-like only, not Build.MODEL/DEVICE/FINGERPRINT strings. */
internal enum class DiagnosticAbi(val id: String) { ARM64("arm64-v8a"), ARMV7("armeabi-v7a"), X86("x86"), X86_64("x86_64"), UNKNOWN("unknown") }
internal data class DiagnosticMetadata(
    val appVersionCode: Long = 0, val androidApi: Int = 0, val abi: DiagnosticAbi = DiagnosticAbi.UNKNOWN,
    val processorCount: Int = 0, val heapMaxBytes: Long = 0, val memoryClassMb: Int = 0,
) {
    init {
        require(appVersionCode in 0..1_000_000_000 && androidApi in 0..1000 && processorCount in 0..1024)
        require(heapMaxBytes in 0..(1L shl 40) && memoryClassMb in 0..1_000_000)
    }
    fun text(): String = dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_60ef08049a72, appVersionCode, androidApi, abi.id, processorCount, heapMaxBytes, memoryClassMb)
}

internal object ChineseDiagnostics {
    private fun operation(value: Operation): String = when (value) {
        Operation.SESSION -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5c1712cb2d38); Operation.RESTORE -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b1336f82fea4); Operation.IMPORT -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7fc4b2dbd50)
        Operation.PREVIEW_RENDER, Operation.PREVIEW_PRESENTATION -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5a9df9227ec6); Operation.ORIGINAL_SPOOL -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9ced68312ac6)
        Operation.EXPORT_PLAN -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_71cab785c5d9); Operation.EXPORT_RENDER -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a3bbae44150); Operation.EXPORT_ENCODE -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2fa4917d052e)
        Operation.SHARE_DIAGNOSTICS -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_73fff6a5d6e6); Operation.CLEANUP -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f8281de2df36)
    }
    private fun stage(value: Stage): String = when (value) {
        Stage.START -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d2bb025a2e51); Stage.DECODE_HEADER -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4077a1f6397d); Stage.DECODE_STRIPE -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_56e09dd8e1fc)
        Stage.NATIVE_ENTER -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_77f67305d365); Stage.NATIVE_PROGRESS -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2bd9ae163aa5); Stage.NATIVE_EXIT -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_98407f5c987e); Stage.ENCODE_START -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_222b0d3c08c4)
        Stage.ENCODE_PROGRESS -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9e388ac3f491); Stage.MEDIA_INSERTED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2560088669b6); Stage.MEDIA_COMMITTED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6ef11d1e8c02)
        Stage.CANCEL_REQUESTED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2625bda74112); Stage.CLEANUP -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d43461bfe6fc); Stage.ERROR -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ae173945fb80)
        Stage.PREVIOUS_UNFINISHED -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2c4c4e5e32fa); Stage.END -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c7b24e7997e9)
    }
    fun summary(snapshot: DiagnosticSnapshot, metadata: DiagnosticMetadata): String = buildString {
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_df800a26392c)).append(metadata.appVersionCode).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_66e4acfbd6bd)).append(snapshot.minutes).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5b3a75c53bbd))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2428420e407f)).append(snapshot.events.size).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e8a56d1c0e70))
        if (snapshot.effectiveWallTimes.isNotEmpty()) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_366712d2d80e)).append(java.time.Instant.ofEpochMilli(snapshot.effectiveWallTimes.min())).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8727f227e2c6))
            .append(java.time.Instant.ofEpochMilli(snapshot.effectiveWallTimes.max())).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0e898ec43b0b))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fa1c6dd0bfc3)).append(snapshot.expiredDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6e94c47fde22)).append(snapshot.capacityDiscarded)
            .append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4af5bd6fb80e)).append(snapshot.malformedDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e9d6840052fc)).append(snapshot.futureDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1701c7739625))
        // Historical recovery markers remain in the event list, but are not
        // relabelled as the current run's latest failure in the short summary.
        val failure = snapshot.events.lastOrNull { it.stage != Stage.PREVIOUS_UNFINISHED && (it.error != null || it.outcome == Outcome.FAILED) }
        if (failure != null) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5bf7bab64ce3)).append(operation(failure.operation)).append(" · ").append(stage(failure.stage)).append("。\n")
        snapshot.events.lastOrNull { it.info.nativePhase != null }?.info?.nativePhase?.let { append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e779813b6cc2)).append(it.chinese).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e5b01c51fa19)) }
        if (snapshot.recoveredIncomplete) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4d7c038cbc95))
        if (snapshot.events.isEmpty()) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7d01902a70ae))
        if (snapshot.crossRunCount > 0) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_aebe06f7e024))
        if (!snapshot.storageHealthy) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a89d10b60775))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a6a08f5857fd))
    }
    fun json(snapshot: DiagnosticSnapshot, metadata: DiagnosticMetadata): String = buildString {
        // All strings originate in fixed enums/allowlists. No general-purpose raw JSON pass-through.
        append("{\"schemaVersion\":1,\"appVersionCode\":").append(metadata.appVersionCode)
        append(",\"engineBuildRevision\":4,\"engineInterfaceVersion\":1,\"androidApi\":").append(metadata.androidApi)
        append(",\"abi\":").append(jsonString(metadata.abi.id)).append(",\"processorCount\":").append(metadata.processorCount)
        append(",\"heapMaxBytes\":").append(metadata.heapMaxBytes).append(",\"memoryClassMb\":").append(metadata.memoryClassMb)
        append(",\"minutes\":").append(snapshot.minutes).append(",\"snapshotUtcMs\":").append(snapshot.snapshotWallMs)
        append(",\"snapshotElapsedMs\":").append(snapshot.snapshotElapsedMs).append(",\"eventCount\":").append(snapshot.events.size)
        append(",\"crossRunTimeUncertain\":").append(snapshot.crossRunCount > 0)
        append(",\"expiredDiscardedThisRun\":").append(snapshot.expiredDiscarded)
        append(",\"capacityDiscardedThisRun\":").append(snapshot.capacityDiscarded)
        append(",\"malformedDiscardedThisRun\":").append(snapshot.malformedDiscarded)
        append(",\"futureDiscardedThisRun\":").append(snapshot.futureDiscarded).append(",\"events\":[")
        snapshot.events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append("{\"recordedUtcMs\":").append(event.wallMs).append(",\"windowUtcMs\":").append(snapshot.effectiveWallTimes[index])
            append(",\"elapsedMs\":").append(event.elapsedMs).append(",\"runId\":").append(jsonString(event.run))
            append(",\"sequence\":").append(event.sequence).append(",\"operationId\":").append(event.operationId)
            append(",\"operation\":").append(jsonString(event.operation.name)).append(",\"stage\":").append(jsonString(event.stage.name))
            event.outcome?.let { append(",\"outcome\":").append(jsonString(it.name)) }
            event.error?.let { append(",\"errorCode\":").append(jsonString(it.name)) }
            append(",\"info\":{")
            event.info.typedFields().entries.forEachIndexed { field, (key, value) ->
                if (field > 0) append(',')
                append(jsonString(key)).append(':')
                if (value is String) append(jsonString(value)) else append(value)
            }
            append("}}")
        }
        append("]}")
    }
}

/** JSON escaping, not a new logging entry point: callers still accept only typed safe data. */
internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c < ' ' || c.isSurrogate()) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
    append('"')
}
