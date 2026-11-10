package dev.filmroom.diagnostics

/** Fixed vocabulary and bounded numbers only. Never retains codec names, URIs or exception messages. */
enum class EffectExportScope { INPUT_DECODE, VIDEO, LIVE_PHOTO, PREFLIGHT, PRELOAD, AUDIO_PREPARE, AUDIO_PLAYBACK }
enum class EffectExportEvent { START, DECODE_BUDGET, INPUT_DECODED, CANDIDATE, CODEC_REJECTED, CODEC_ACCEPTED, ATTEMPT_START, GL_LIMITS, ATTEMPT_FAILED, VIDEO_SAVED, COVER_RETRY, LIVE_SAVED, ERROR, END, STEP, AUDIO_CACHE_HIT, AUDIO_ABSENT, AUDIO_READY, AUDIO_FALLBACK, AUDIO_FOCUS_DENIED, AUDIO_PLAYER_ERROR, AUDIO_UPGRADED, PREFLIGHT_RESOLVED, RECOVERY_COMPLETE, STREAM_COMPLETE, CLEANUP_FAILED, LIVE_COMMITTED, INPUT_FORMAT, RAW_FALLBACK }
enum class EffectExportReason { NONE, DECODE_MEMORY_BUDGET, HEAP_EDGE_CAP, INPUT_RESOLUTION_LIMIT, CODEC_CAPABILITIES_UNAVAILABLE, CODEC_NO_SURFACE, CODEC_NO_VIDEO_CAPABILITIES, CODEC_ALIGNMENT, CODEC_SIZE_RATE_UNSUPPORTED, CODEC_CAPABILITY_QUERY_FAILED, NO_ENCODER_FOR_SIZE, GL_LIMIT, ENCODER_TIMEOUT, ENCODER_FORMAT_CHANGED, ENCODER_SIZE_CHANGED, ENCODER_FORMAT_MISSING, ENCODER_BUFFER_MISSING, INCOMPLETE_VIDEO, OUTPUT_CREATE_FAILED, OUTPUT_OPEN_FAILED, OUTPUT_PUBLISH_FAILED, OUT_OF_MEMORY, CODEC_ERROR, IO_ERROR, INVALID_STATE, INVALID_ARGUMENT, UNKNOWN, COVER_DECODE_FAILED, COVER_RETRY_EXHAUSTED, MOTION_DECODE_FAILED, PERMISSION_DENIED, AUDIO_SOURCE_MISSING, AUDIO_TRACK_ABSENT, AUDIO_BASE_UNAVAILABLE, LIVE_INVALID_INPUT, LIVE_UNSUPPORTED_PROFILE, LIVE_RESOURCE_LIMIT, LIVE_VERIFY_FAILED, LIVE_PUBLISH_FAILED, LIVE_BUSY, LIVE_OUTPUT_EXISTS }
enum class EffectExportStep { NONE, CREATE_CODEC, CONFIGURE_CODEC, CREATE_SURFACE, CREATE_EGL, QUERY_GL_LIMITS, PREPARE_TEXTURES, INITIALIZE_RENDERER, START_CODEC, OPEN_OUTPUT, DRAIN, DRAW, PRESENT, SIGNAL_EOS, FINALIZE, PUBLISH, LIVE_PROBE, COVER, LIVE_PACKAGE, LIVE_VERIFY, LIVE_PUBLISH, IMAGE_BOUNDS, DECODE_RESULT, DECODE_SOURCE, COMPOSE_SOURCE, MOTION_EXTRACT, AUDIO_LOCK, AUDIO_DECODE, AUDIO_MIX, AUDIO_ENCODE, AUDIO_PREPARE, AUDIO_PLAY, LIVE_RESOLVE, LIVE_RECOVER }

data class EffectExportDiagnosticInfo(
    val scope:EffectExportScope, val event:EffectExportEvent,
    val reason:EffectExportReason=EffectExportReason.NONE, val step:EffectExportStep=EffectExportStep.NONE,
    val requestedWidth:Int?=null,val requestedHeight:Int?=null,
    val width:Int?=null,val height:Int?=null,
    val availableWidth:Int?=null,val availableHeight:Int?=null,
    val sourceWidth:Int?=null,val sourceHeight:Int?=null,
    val resultWidth:Int?=null,val resultHeight:Int?=null,
    val maxHeapBytes:Long?=null,val remainingBytes:Long?=null,
    val budgetEdge:Int?=null,val codecIndex:Int?=null,val attempt:Int?=null,
    val textureLimit:Int?=null,val viewportWidth:Int?=null,val viewportHeight:Int?=null,
    val codecErrorCode:Int?=null,
    val elapsedMs:Long?=null,val count:Int?=null,val bytes:Long?=null,
    val profileIndex:Int?=null,val supportIndex:Int?=null,val mediaErrorExtra:Int?=null,
    val correlationId:Long?=null,
    val parentCorrelationId:Long?=null,
    val inputFormat:dev.filmroom.io.formats.PhotoFormat?=null,
) {
    internal fun fields():Map<String,Any> = linkedMapOf<String,Any>(
        "effectScope" to scope.name,"effectEvent" to event.name,"effectReason" to reason.name,"effectStep" to step.name
    ).apply {
        fun number(key:String,value:Long?,max:Long=1_000_000,min:Long=0){if(value!=null && value in min..max)put("effect"+key,value)}
        number("CorrelationId",correlationId,Long.MAX_VALUE,1)
        number("ParentCorrelationId",parentCorrelationId,Long.MAX_VALUE,1)
        inputFormat?.let{put("effectInputFormat",it.name)}
        number("RequestedWidth",requestedWidth?.toLong());number("RequestedHeight",requestedHeight?.toLong())
        number("Width",width?.toLong());number("Height",height?.toLong())
        number("AvailableWidth",availableWidth?.toLong());number("AvailableHeight",availableHeight?.toLong())
        number("SourceWidth",sourceWidth?.toLong());number("SourceHeight",sourceHeight?.toLong())
        number("ResultWidth",resultWidth?.toLong());number("ResultHeight",resultHeight?.toLong())
        number("MaxHeapBytes",maxHeapBytes,1L shl 40);number("RemainingBytes",remainingBytes,1L shl 40)
        number("BudgetEdge",budgetEdge?.toLong());number("CodecIndex",codecIndex?.toLong(),10000)
        number("Attempt",attempt?.toLong(),10000);number("TextureLimit",textureLimit?.toLong())
        number("ViewportWidth",viewportWidth?.toLong());number("ViewportHeight",viewportHeight?.toLong())
        number("CodecErrorCode",codecErrorCode?.toLong(),Int.MAX_VALUE.toLong(),Int.MIN_VALUE.toLong())
        number("ElapsedMs",elapsedMs,86_400_000);number("Count",count?.toLong(),1_000_000)
        number("Bytes",bytes,1L shl 40);number("ProfileIndex",profileIndex?.toLong(),100)
        number("SupportIndex",supportIndex?.toLong(),100)
        number("MediaErrorExtra",mediaErrorExtra?.toLong(),Int.MAX_VALUE.toLong(),Int.MIN_VALUE.toLong())
    }
    companion object {
        internal fun parse(fields:Map<String,String>):EffectExportDiagnosticInfo? {
            val scope=EffectExportScope.entries.firstOrNull{it.name==fields["effectScope"]}?:return null
            val event=EffectExportEvent.entries.firstOrNull{it.name==fields["effectEvent"]}?:return null
            fun n(key:String)=fields["effect"+key]?.toLongOrNull()
            fun i(key:String)=n(key)?.takeIf{it in Int.MIN_VALUE..Int.MAX_VALUE}?.toInt()
            return EffectExportDiagnosticInfo(scope,event,
                EffectExportReason.entries.firstOrNull{it.name==fields["effectReason"]}?:EffectExportReason.UNKNOWN,
                EffectExportStep.entries.firstOrNull{it.name==fields["effectStep"]}?:EffectExportStep.NONE,
                i("RequestedWidth"),i("RequestedHeight"),i("Width"),i("Height"),i("AvailableWidth"),i("AvailableHeight"),
                i("SourceWidth"),i("SourceHeight"),i("ResultWidth"),i("ResultHeight"),n("MaxHeapBytes"),n("RemainingBytes"),
                i("BudgetEdge"),i("CodecIndex"),i("Attempt"),i("TextureLimit"),i("ViewportWidth"),i("ViewportHeight"),i("CodecErrorCode"),n("ElapsedMs"),i("Count"),n("Bytes"),i("ProfileIndex"),i("SupportIndex"),i("MediaErrorExtra"),n("CorrelationId")?.takeIf{it>0},n("ParentCorrelationId")?.takeIf{it>0},
                fields["effectInputFormat"]?.let{v->dev.filmroom.io.formats.PhotoFormat.entries.firstOrNull{it.name==v}?:dev.filmroom.io.formats.PhotoFormat.UNKNOWN})
        }
    }
}
