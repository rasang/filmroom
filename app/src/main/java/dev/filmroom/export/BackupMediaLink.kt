package dev.filmroom.export

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** A stored URI is an address, never a restored permission grant. */
internal object BackupMediaLink {
    enum class Status { READABLE, MISSING_LINK, NO_PERMISSION, MISSING, IDENTITY_MISMATCH, DECODE_UNSUPPORTED, UNAVAILABLE }
    private fun mediaItem(value:String?)=value!=null &&
        Regex("content://media/(external|external_primary|[0-9a-fA-F-]+)/(images/media|downloads)/[1-9][0-9]*").matches(value)
    fun validOutput(value:String?)=value!=null && dev.filmroom.io.OriginalPhotoUri.sanitize(value)!=null &&
        (Uri.parse(value).authority!="media" || mediaItem(value))
    fun validOriginal(value:String)=dev.filmroom.io.OriginalPhotoUri.sanitize(value)!=null
    private fun granted(context:Context,uri:Uri)=context.contentResolver.persistedUriPermissions.any{it.isReadPermission && it.uri==uri}
    private class HeaderInput(input:java.io.InputStream):java.io.FilterInputStream(input) {
        private var remaining=4L*1024*1024
        override fun read():Int { if(remaining<=0)return -1;return super.read().also{if(it>=0)remaining--} }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(length==0)return 0
            if(remaining<=0)return -1
            return `in`.read(buffer,offset,minOf(length.toLong(),remaining).toInt()).also{if(it>0)remaining-=it}
        }
        override fun skip(count:Long):Long=`in`.skip(minOf(count.coerceAtLeast(0),remaining)).also{remaining-=it}
        override fun markSupported()=false
    }
    private fun dimensions(context:Context,uri:Uri):Pair<Int,Int>? {
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(HeaderInput(it),null,options);Unit} ?: return null
        return if(options.outWidth>0 && options.outHeight>0)options.outWidth to options.outHeight else null
    }
    private fun sameSize(job:ExportJob,size:Pair<Int,Int>)=size.first==job.outputWidth && size.second==job.outputHeight
    /** MediaStore rows keep exact URI/name and app export directory checks even after owner attribution is lost. */
    fun inspect(context:Context,job:ExportJob):Status {
        if(!validOutput(job.mediaUri) || job.outputName.isNullOrBlank())return Status.MISSING_LINK
        val uri=Uri.parse(job.mediaUri)
        if(!mediaItem(job.mediaUri) && !granted(context,uri))return Status.NO_PERMISSION
        return try {
            val columns=if(mediaItem(job.mediaUri)) arrayOf(OpenableColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                else arrayOf(OpenableColumns.DISPLAY_NAME)
            val identity=context.contentResolver.query(uri,columns,null,null,null)?.use{cursor->
                if(!cursor.moveToFirst())return@use Status.MISSING
                if(cursor.getString(0)!=job.outputName)return@use Status.IDENTITY_MISMATCH
                if(mediaItem(job.mediaUri) && !granted(context,uri)) {
                    val path=cursor.getString(1)?.trimEnd('/')
                    val owner=cursor.getString(2)
                    if(owner!=context.packageName && path !in setOf("Pictures/暗房","Download/暗房","Downloads/暗房"))return@use Status.IDENTITY_MISMATCH
                }
                Status.READABLE
            } ?: Status.MISSING
            if(identity!=Status.READABLE)return identity
            val size=dimensions(context,uri) ?: return Status.DECODE_UNSUPPORTED
            if(sameSize(job,size))Status.READABLE else Status.IDENTITY_MISMATCH
        } catch(_:SecurityException){Status.NO_PERMISSION}
          catch(_:java.io.FileNotFoundException){Status.MISSING}
          catch(_:Exception){Status.UNAVAILABLE}
    }
    fun readable(context:Context,job:ExportJob)=inspect(context,job)==Status.READABLE
    /** User-action diagnostic: typed result only, never a URI, name or provider message. Call off Main. */
    fun inspectLogged(context:Context,job:ExportJob):Status {
        val trace=dev.filmroom.diagnostics.Diagnostics.begin(dev.filmroom.diagnostics.Operation.ARCHIVED_PHOTO_ACCESS)
        val result=inspect(context,job)
        if(result!=Status.READABLE) {
            val code=when(result) {
                Status.MISSING_LINK->dev.filmroom.diagnostics.ErrorCode.BACKUP_LINK_MISSING
                Status.MISSING->dev.filmroom.diagnostics.ErrorCode.BACKUP_MEDIA_MISSING
                Status.IDENTITY_MISMATCH->dev.filmroom.diagnostics.ErrorCode.BACKUP_MEDIA_IDENTITY_MISMATCH
                Status.DECODE_UNSUPPORTED->dev.filmroom.diagnostics.ErrorCode.UNSUPPORTED_FORMAT
                Status.UNAVAILABLE->dev.filmroom.diagnostics.ErrorCode.UNKNOWN
                else->dev.filmroom.diagnostics.ErrorCode.PERMISSION_LOST
            }
            dev.filmroom.diagnostics.Diagnostics.event(trace,dev.filmroom.diagnostics.Stage.ERROR,error=code)
        }
        dev.filmroom.diagnostics.Diagnostics.end(trace,if(result==Status.READABLE)dev.filmroom.diagnostics.Outcome.SUCCESS else dev.filmroom.diagnostics.Outcome.FAILED)
        return result
    }
    /** An explicit picker selection + confirmation, not a same-name search or an inferred grant. */
    fun selectedName(context:Context,uri:Uri):String? = try {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}
            ?.takeIf{it.isNotBlank() && it.length<=255 && !it.contains('/') && !it.contains('\\')}
    }catch(_:Exception){null}
    fun selectedMatches(context:Context,job:ExportJob,uri:Uri):Boolean {
        if(!validOutput(uri.toString()) || !granted(context,uri))return false
        return try {
            val name=selectedName(context,uri) ?: return false
            (job.outputName==null || name==job.outputName) && dimensions(context,uri)?.let{sameSize(job,it)}==true
        }catch(_:Exception){false}
    }
}
