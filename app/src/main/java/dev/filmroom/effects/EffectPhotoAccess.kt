package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import dev.filmroom.diagnostics.OriginalPhotoGrant
import dev.filmroom.export.BackupMediaLink
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.io.OriginalPhotoAccess

/** A URI is remembered only after a verified persistent read grant and a durable record write. */
internal object EffectPhotoAccess {
    data class Links(val source:String?,val result:String?)
    fun restoredLinks(record:ExportJob?,source:String?,result:String?,sourceTemporary:Boolean,resultTemporary:Boolean)=
        Links(if(record!=null && !sourceTemporary)record.originalUri else source,
              if(record!=null && !resultTemporary)record.mediaUri else result)
    enum class Status { SAVED, TEMPORARY_PERMISSION, RECORD_UNAVAILABLE, RECORD_CHANGED, SAVE_FAILED, INVALID_RESULT }
    fun remember(context:Context,store:ExportJobStore,jobId:String?,original:Boolean,expectedUri:String?,selected:Uri,
                 retain:()->Boolean={OriginalPhotoAccess.retainRead(context,selected).let{it==OriginalPhotoGrant.PERSISTED || it==OriginalPhotoGrant.ALREADY_PERSISTED}},
                 outputName:(ExportJob)->String?={job->if(BackupMediaLink.selectedMatches(context,job,selected))BackupMediaLink.selectedName(context,selected)else null}):Status {
        if(!runCatching(retain).getOrDefault(false))return Status.TEMPORARY_PERMISSION
        val job=jobId?.let(store::get)?.takeIf{it.state==ExportJobState.COMPLETED} ?: return Status.RECORD_UNAVAILABLE
        val current=if(original)job.originalUri else job.mediaUri
        // Idempotent replay after a durable commit whose Activity callback was cancelled.
        if(current==selected.toString())return Status.SAVED
        if(current!=expectedUri)return Status.RECORD_CHANGED
        val name=if(original)null else {outputName(job) ?: return Status.INVALID_RESULT}
        return try {
            if(store.relinkEffectPhoto(job.id,original,expectedUri,selected.toString(),name))Status.SAVED else Status.RECORD_CHANGED
        } catch(_:Exception){Status.SAVE_FAILED}
    }
}
