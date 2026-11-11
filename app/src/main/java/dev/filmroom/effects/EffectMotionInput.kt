package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import dev.filmroom.diagnostics.PairLookupStatus
import dev.filmroom.diagnostics.PairMatchStatus
import dev.filmroom.diagnostics.PairPermission
import dev.filmroom.diagnostics.Diagnostics
import dev.filmroom.diagnostics.MotionInputDiagnosticInfo
import dev.filmroom.diagnostics.MotionInputStatus
import dev.filmroom.diagnostics.Operation
import dev.filmroom.diagnostics.Outcome
import dev.filmroom.diagnostics.SafeInfo
import dev.filmroom.livephoto.importer.MotionPhotoExtractor
import java.io.File
import java.util.concurrent.CancellationException

/** Bounded original JPEG motion input; typed diagnostics never contain source identifiers. */
internal object EffectMotionInput {
    private const val MAX_INPUT=134217728L
    private val pairStates=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    /** True means a paired source; the Activity separately owns its successfully extracted video. */
    fun needsPair(uri:String?):Boolean = uri!=null && pairStates[uri]==true
    /** Validate a user-authorized companion before remembering it. */
    fun associate(context:Context,imageUri:String,videoUri:Uri,checkActive:()->Unit={}):Boolean {
        if(videoUri.scheme!="content")return false
        val progress=Progress()
        val output=try {prepareChecked(context,imageUri,checkActive,progress,videoUri.toString())}
            catch(e:CancellationException){throw e}
            catch(_:Exception){null}
            catch(_:OutOfMemoryError){null}
            ?: return false
        output.delete()
        EffectMotionPairAccess.remember(context,imageUri,videoUri)
        return true
    }
    fun prepare(context:Context,uri:String?,correlationId:Long=EffectCorrelation.next(),checkActive:()->Unit={}):File? =
        prepareWithDiagnostics(context,uri,checkActive) { report ->
            val info=SafeInfo(motionInput=report.copy(correlationId=correlationId))
            val outcome=when(report.status) {
                MotionInputStatus.SUCCESS -> Outcome.SUCCESS
                MotionInputStatus.CANCELLED -> Outcome.CANCELLED
                else -> Outcome.FAILED
            }
            Diagnostics.end(Diagnostics.begin(Operation.IMPORT,info),outcome,info)
        }

    /** Injection is only for tests; all production reports go through the persistent typed store. */
    internal fun prepareWithDiagnostics(context:Context,uri:String?,checkActive:()->Unit={},report:(MotionInputDiagnosticInfo)->Unit):File? {
        val progress=Progress()
        return try {
            prepareChecked(context,uri,checkActive,progress)
        } catch(e:CancellationException) { progress.status=MotionInputStatus.CANCELLED;throw e }
          catch(_:Exception) { null }
          catch(_:OutOfMemoryError) { progress.status=MotionInputStatus.MEMORY_LIMIT;null }
          finally { runCatching { report(MotionInputDiagnosticInfo(progress.status,progress.copied,progress.videoBytes, pairLookup=progress.lookup, pairMatch=progress.match, pairPermission=progress.permission, pairCandidates=progress.candidates, pairReadFailures=progress.readFailures, pairRejected=progress.rejected, pairSavedFallback=progress.fallback)) } }
    }
    private class Progress {
        var status=MotionInputStatus.READ_FAILED
        var copied=0L
        var videoBytes:Long?=null
        var lookup:PairLookupStatus?=null
        var match:PairMatchStatus?=null
        var permission:PairPermission?=null
        var candidates=0
        var readFailures=0
        var rejected=0
        var fallback=false
    }
    private fun prepareChecked(context:Context,uri:String?,checkActive:()->Unit,progress:Progress,selectedVideo:String?=null):File? {
        if(uri==null) { progress.status=MotionInputStatus.SOURCE_MISSING;return null }
        val directory=File(context.cacheDir,"effect-motion").apply{mkdirs()}
        val input=File.createTempFile("input-",".jpg",directory)
        var video:File?=null
        var retained=false
        try {
            checkActive()
            val stream=context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { src -> input.outputStream().use { dst ->
                if(src.read()!=255 || src.read()!=216) { progress.status=MotionInputStatus.NON_JPEG;return null }
                dst.write(byteArrayOf(-1,-40));progress.copied=2
                val buffer=ByteArray(65536);var total=2L
                while(true){checkActive();val count=src.read(buffer);if(count<0)break
                    total+=count
                    if(total>MAX_INPUT) { progress.status=MotionInputStatus.INPUT_TOO_LARGE;return null }
                    dst.write(buffer,0,count);progress.copied=total}
            }}
            checkActive()
            progress.status=MotionInputStatus.NOT_RECOGNIZED
            var inspected=MotionPhotoExtractor.inspect(input,checkActive=checkActive)
            val paired=inspected==null && MotionPhotoExtractor.inspectPairImage(input,checkActive)!=null
            // Do not evict a pending pair while its Activity can still export.
            if(paired && inspected==null)pairStates[uri]=true else pairStates.remove(uri)
            if(selectedVideo!=null) {if(!paired)return null;inspected=null}
            var companion:File?=null
            try {
                if(inspected==null && paired) {
                    progress.status=MotionInputStatus.PAIR_REQUIRED
                    progress.permission=when { EffectMotionPairAccess.hasFullVideoAccess(context)->PairPermission.FULL; EffectMotionPairAccess.canQueryVideos(context)->PairPermission.PARTIAL; else->PairPermission.NONE }
                    val candidates=selectedVideo?.let{progress.lookup=PairLookupStatus.SAVED;listOf(it)} ?: EffectMotionPairAccess.candidates(context,uri,report={progress.lookup=it})
                    val saved=selectedVideo==null && progress.lookup==PairLookupStatus.SAVED
                    progress.match=PairMatchStatus.NONE
                    val matches=mutableListOf<Pair<File,dev.filmroom.livephoto.importer.MotionPhotoSource>>()
                    try {
                        // A valid explicit selection wins. Only failed remembered selections fall back.
                        for (batch in 0..(if(saved) 1 else 0)) {
                            val batchCandidates=if(batch==0) candidates else {
                                if(matches.isNotEmpty())break
                                progress.fallback=true
                                EffectMotionPairAccess.candidates(context,uri,includeSaved=false,report={progress.lookup=it})
                            }
                            progress.candidates+=batchCandidates.size
                            for(candidate in batchCandidates.take(8)) {
                                checkActive()
                                val copy=File.createTempFile("pair-",".mp4",directory)
                                var matched=false
                                try {
                                    context.contentResolver.openInputStream(Uri.parse(candidate))?.use { src ->
                                        copy.outputStream().use { dst ->
                                            val buffer=ByteArray(65536);var total=0L
                                            while(true){checkActive();val n=src.read(buffer);if(n<0)break
                                                total+=n;require(total<=MAX_INPUT);dst.write(buffer,0,n)}
                                        }
                                    } ?: throw java.io.IOException()
                                    MotionPhotoExtractor.inspect(input,copy,checkActive)?.let { matches+=copy to it;matched=true }
                                    if(!matched) {progress.rejected++;progress.match=PairMatchStatus.INVALID_PAIR}
                                } catch(e:CancellationException){throw e}
                                  catch(_:SecurityException) {progress.readFailures++;progress.match=PairMatchStatus.ACCESS_DENIED}
                                  catch(_:Exception) {progress.readFailures++;progress.match=PairMatchStatus.UNREADABLE} finally {if(!matched)copy.delete()}
                            }
                        }
                        // Even multiple valid candidates are ambiguous: never choose by filename/order.
                        if(matches.size!=1) {if(matches.size>1)progress.match=PairMatchStatus.AMBIGUOUS;return null}
                        progress.match=PairMatchStatus.MATCHED
                        companion=matches.single().first;inspected=matches.single().second
                    } finally {matches.forEach{if(it.first!=companion)it.first.delete()}}
                }
                val source=inspected ?: return null
            progress.status=MotionInputStatus.EXTRACTION_FAILED
            val target=File.createTempFile("video-",".mp4",directory);video=target
            check(target.delete())
            MotionPhotoExtractor.extract(source,target,checkActive)
            checkActive();retained=true
            progress.videoBytes=target.length();progress.status=MotionInputStatus.SUCCESS
            return target
            } finally {companion?.delete()}
        } finally {input.delete();if(!retained)video?.delete()}
    }
}
