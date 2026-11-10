package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportQueue
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** One bounded, consumable hand-off. No Activity, URI permission or bitmap crosses process death. */
internal class PreparedEffectPhoto(val record:ExportJob,val input:Bitmap,val output:Bitmap,
    val composition:EffectSourceComposition?,val readout:EffectCameraReadout,val motionFile:File?):AutoCloseable {
    fun matches(other:ExportJob?)=other!=null && record.id==other.id && record.originalUri==other.originalUri &&
        record.mediaUri==other.mediaUri && record.crop==other.crop && record.border==other.border && record.photoMetadata==other.photoMetadata &&
        record.parameters==other.parameters && record.width==other.width && record.height==other.height &&
        record.sourceWidth==other.sourceWidth && record.sourceHeight==other.sourceHeight && record.state==other.state
    override fun close(){motionFile?.delete();if(!input.isRecycled)input.recycle();if(output!==input&&!output.isRecycled)output.recycle()}
}

/** At most one 1600px pair and one extracted Live clip, expires even when Activity launch fails. */
internal object EffectEntryHandoff {
    private val handler=Handler(Looper.getMainLooper())
    private var item:Pair<String,PreparedEffectPhoto>?=null
    private var expiry:Runnable?=null
    @Synchronized fun put(photo:PreparedEffectPhoto):String {
        clear();val token=UUID.randomUUID().toString();item=token to photo
        expiry=Runnable{discard(token)}.also{handler.postDelayed(it,15_000)}
        return token
    }
    @Synchronized fun take(token:String?,record:ExportJob?):PreparedEffectPhoto? {
        val pair=item?:return null
        if(pair.first!=token)return null
        item=null;expiry?.let{handler.removeCallbacks(it)};expiry=null
        return if(pair.second.matches(record))pair.second else {pair.second.close();null}
    }
    @Synchronized fun discard(token:String){if(item?.first==token)clear()}
    @Synchronized private fun clear(){expiry?.let{handler.removeCallbacks(it)};expiry=null;item?.second?.close();item=null}
}

/** Starts while the effect chooser is open. A cold launch waits on the existing list, never an empty page. */
class EffectPreviewEntry(private val activity:ComponentActivity,private val job:ExportJob):AutoCloseable {
    private val correlationId=EffectCorrelation.next()
    private var staged:PreparedEffectPhoto?=null
    private var waiting:Job?=null
    private var dialog:AlertDialog?=null
    private var closed=false
    private var launchRequested=false
    private val preparation=activity.lifecycleScope.launch {
        var owned:PreparedEffectPhoto?=null
        val log=EffectExportLog(EffectExportScope.PRELOAD,correlationId)
        var outcome=Outcome.FAILED
        try {
            owned=prepare(activity.applicationContext,job,log)
            ensureActive()
            staged=owned;owned=null
            if(staged!=null)outcome=Outcome.SUCCESS
        }catch(e:CancellationException){outcome=Outcome.CANCELLED;throw e}
        catch(e:Exception){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.ERROR,EffectExportLog.reason(e),log.step))}
        catch(_:OutOfMemoryError){log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.ERROR,EffectExportReason.OUT_OF_MEMORY,log.step))}
        finally{owned?.close();log.end(outcome,flush=false)}
    }
    fun launch(kind:EffectKind){
        if(launchRequested)return
        launchRequested=true
        if(closed){open(kind,null);return}
        if(kind!=EffectKind.OPTICAL_LEAK){close();open(kind,null);return}
        waiting=activity.lifecycleScope.launch {
            try {
                // Delay the small cancelable indicator so warm-cache selection has no flash.
                val indicator=launch {delay(120);if(preparation.isActive){
                    dialog=AlertDialog.Builder(activity).setMessage(if(zh())"正在准备取景器…" else "Preparing viewfinder…")
                        .setNegativeButton(if(zh())"取消"else "Cancel"){_,_->close()}.setOnCancelListener{close()}.show()
                }}
                try{preparation.join()}finally{indicator.cancel();dialog?.dismiss();dialog=null}
                ensureActive()
                if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)||activity.isFinishing)return@launch
                val photo=staged;staged=null
                val token=photo?.let{EffectEntryHandoff.put(it)}
                try{open(kind,token)}catch(_:Exception){token?.let{EffectEntryHandoff.discard(it)}}
            }finally{close()}
        }
    }
    private fun zh()=dev.filmroom.i18n.L10n.language(activity).startsWith("zh")
    private fun open(kind:EffectKind,token:String?){activity.startActivity(Intent(activity,PhotoEffectActivity::class.java)
        .putExtra(PhotoEffectActivity.CORRELATION_ID,correlationId)
        .putExtra(PhotoEffectActivity.KIND,kind.name).putExtra(PhotoEffectActivity.RESULT_URI,job.mediaUri)
        .putExtra(PhotoEffectActivity.SOURCE_URI,job.originalUri).putExtra(PhotoEffectActivity.JOB_ID,job.id)
        .apply{if(token!=null)putExtra(PhotoEffectActivity.PREPARED_ENTRY,token)})}
    override fun close(){if(closed)return;closed=true;preparation.cancel();waiting?.cancel();staged?.close();staged=null;dialog?.dismiss();dialog=null}
    companion object {
        private suspend fun prepare(context:Context,requested:ExportJob,log:EffectExportLog):PreparedEffectPhoto? {
            var completed:PreparedEffectPhoto?=null
            try { withContext(Dispatchers.IO){
            val record=ExportQueue.get(context).store.get(requested.id)?:return@withContext
            // Recovery/picker interactions stay in the destination. Never guess alternate originals here.
            val composition=EffectSourceComposition.from(record)
            var input:Bitmap?=null;var output:Bitmap?=null;var motion:File?=null
            try {
                log.phase(EffectExportStep.DECODE_RESULT)
                output=decode(context,requireNotNull(record.mediaUri),log,allowRawFallback=false);ensureActive()
                log.phase(EffectExportStep.DECODE_SOURCE)
                val raw=decode(context,requireNotNull(record.originalUri),log)
                log.phase(EffectExportStep.COMPOSE_SOURCE)
                try{input=composition?.apply(raw){ensureActive()}?:raw;if(input!==raw)raw.recycle()}
                catch(t:Throwable){raw.recycle();throw t}
                val readout=EffectCameraReadout.read(context.contentResolver,record.originalUri,record.photoMetadata)
                log.phase(EffectExportStep.MOTION_EXTRACT)
                motion=EffectMotionInput.prepare(context,record.originalUri,log.correlationId){ensureActive()};ensureActive()
                completed=PreparedEffectPhoto(record,requireNotNull(input),requireNotNull(output),composition,readout,motion).also{input=null;output=null;motion=null}
            }finally{motion?.delete();input?.takeUnless{it.isRecycled}?.recycle();if(output!==input)output?.takeUnless{it.isRecycled}?.recycle()}
            }
                currentCoroutineContext().ensureActive()
                return completed
            } catch(t:Throwable){completed?.close();throw t}
        }
        private suspend fun decode(context:Context,uri:String,log:EffectExportLog,allowRawFallback:Boolean=true):Bitmap {
            var format=dev.filmroom.io.formats.PhotoFormat.UNKNOWN
            try { return EffectImageDecoder(context,log.correlationId).use { decoder ->
                decoder.read(uri,1600,allowRawFallback=allowRawFallback,report={format=EffectInputFormat.fromDecodedHeader(it)})
            } } finally {log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.INPUT_FORMAT,step=log.step,inputFormat=format))}
        }
    }
}
