package dev.filmroom.livephoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.filmroom.livephoto.core.*
import java.io.File
import java.io.IOException

internal class AndroidMediaSink(private val context:Context):MediaSink {
    private val resolver=context.contentResolver
    private fun collection(mime:String):Uri = if(mime.startsWith("image/"))MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    override fun insertPending(displayName:String,mimeType:String,relativePath:String):String {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,displayName)
            put(MediaStore.MediaColumns.MIME_TYPE,mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH,relativePath)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        return resolver.insert(collection(mimeType),values)?.toString() ?: throw IOException("MediaStore insert returned null")
    }
    override fun copyAndVerify(id:String,source:File,expectedSha256:String,checkActive:()->Unit) {
        val uri=Uri.parse(id)
        requireOwned(uri)
        val fd=resolver.openFileDescriptor(uri,"w") ?: throw IOException("No output descriptor")
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            source.inputStream().use { input ->
                val buf=ByteArray(SdkLimits.BUFFER_BYTES)
                while(true) { checkActive(); val n=input.read(buf); if(n<0)break; out.write(buf,0,n) }
            }
            out.flush(); out.fd.sync()
        }
        checkActive()
        val hash=resolver.openInputStream(uri)?.use { FileDigests.sha256(it,checkActive) } ?: throw IOException("Cannot read back new media")
        if(hash!=expectedSha256)throw LivePhotoException(ErrorCode.VERIFICATION_FAILED,"MediaStore readback hash mismatch")
    }
    override fun publish(id:String) {
        val uri=Uri.parse(id); requireOwned(uri)
        val values=ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) }
        if(resolver.update(uri,values,null,null)!=1)throw IOException("Could not publish media row")
    }
    override fun existsOwned(id:String):Boolean = owner(Uri.parse(id))==context.packageName
    override fun deleteOwned(id:String):Boolean {
        val uri=Uri.parse(id); val owner=owner(uri) ?: return true
        if(owner!=context.packageName)throw SecurityException("Refusing to delete a foreign media row")
        return resolver.delete(uri,null,null)==1
    }
    override fun findOwned(displayName:String,mimeType:String,relativePath:String):List<String> {
        val base=collection(mimeType); val ids=mutableListOf<String>()
        resolver.query(base,arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(displayName,relativePath),null).let { it ?: throw IOException("MediaStore lookup returned null") }.use { cursor ->
            while(cursor.moveToNext()) if(cursor.getString(1)==context.packageName)ids+=ContentUris.withAppendedId(base,cursor.getLong(0)).toString()
        }
        return ids
    }
    private fun requireOwned(uri:Uri) {
        if(owner(uri)!=context.packageName)throw SecurityException("Media row ownership is unknown or different")
    }
    private fun owner(uri:Uri):String? {
        if(uri.scheme!="content" || uri.authority!="media")throw SecurityException("Invalid MediaStore Uri")
        resolver.query(uri,arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null).let { it ?: throw IOException("MediaStore ownership query returned null") }.use { c ->
            if(c.moveToFirst())return c.getString(0) ?: throw SecurityException("MediaStore owner is unavailable")
        }
        return null
    }
}
