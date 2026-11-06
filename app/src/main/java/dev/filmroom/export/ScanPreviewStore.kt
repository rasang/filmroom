package dev.filmroom.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.filmroom.border.*
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.crop.CropPreview
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Optional, private presentation artifacts. A token and matching unique filename form one generation. */
internal object ScanPreviewStore {
    private const val TOKEN="scan-preview-attempt"
    private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val source=Regex("scan-source-([0-9a-f-]{36})\\.png")
    fun clear(dir:File) {NegativePreviewStore.clear(dir);dir.listFiles()?.filter{it.name==TOKEN||it.name=="$TOKEN.part"||it.name.startsWith("scan-source-")||it.name.startsWith("scan-preview-")}.orEmpty().forEach{if(it.isFile)it.delete()}}
    fun begin(dir:File):String {
        clear(dir);File(dir,"negative-preview.png").delete();val id=UUID.randomUUID().toString();val part=File(dir,"$TOKEN.part")
        try {FileOutputStream(part).use{it.write(id.toByteArray(Charsets.US_ASCII));it.fd.sync()};Files.move(part.toPath(),File(dir,TOKEN).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally{part.delete()}
        NegativePreviewStore.begin(id)
        return id
    }
    internal fun token(dir:File):String?=runCatching {File(dir,TOKEN).takeIf{it.isFile&&it.length()==36L}?.readText(Charsets.US_ASCII)?.takeIf{uuid.matches(it)}}.getOrNull()
    /** The atomic attempt token is written only when this job actually starts,
     * and retained through successful cleanup. Queue waiting time is not counted. */
    internal fun startedAtMillis(dir:File):Long? {
        val id=token(dir) ?: return null
        val started=File(dir,TOKEN).lastModified().takeIf{it>0} ?: return null
        return started.takeIf{token(dir)==id}
    }
    fun select(dir:File):File? {
        val id=token(dir)?:return null
        return File(dir,"scan-preview-$id.jpg").takeIf{it.isFile&&it.length() in 1..524288L && token(dir)==id}
    }
    fun publish(dir:File,id:String,input:File,job:ExportJob,provider:BorderProvider,checkActive:()->Unit) {
        checkActive();require(uuid.matches(id)&&token(dir)==id)
        val match=source.matchEntire(input.name)?:error("Unexpected scan source name")
        require(uuid.matches(match.groupValues[1]) && input.canonicalFile.parentFile==dir.canonicalFile && input.isFile && input.length() in 1..524288L)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(input.path,bounds)
        require(bounds.outWidth in 1..256&&bounds.outHeight in 1..256)
        val original=checkNotNull(BitmapFactory.decodeFile(input.path,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888}))
        var composed:Bitmap?=null;var small:Bitmap?=null
        val part=File(dir,"scan-preview-$id.jpg.part")
        try {
            checkActive()
            composed=job.border?.let{frozen->
                provider.verifySnapshot(frozen)
                val raster=provider.resolve(frozen,checkActive)
                val snapshot=(if(frozen.geometryVersion==1)BorderSnapshot.create(frozen.stock,frozen.assetVersion,original.width,original.height,raster.railRatio,raster.endRatio,frozen.featherTotalAt3072)
                    else if(frozen.geometryVersion==5)BorderSnapshot.medium120(frozen.stock,frozen.assetVersion,original.width,original.height,
                        frozen.canonicalWidth,frozen.canonicalHeight,frozen.composition,checkNotNull(frozen.mediumFormat),frozen.mediumPortrait,
                        checkNotNull(frozen.stripCenterMm),frozen.featherTotalAt3072)
                    else BorderSnapshot.authored(frozen.stock,frozen.assetVersion,original.width,original.height,frozen.canonicalWidth,frozen.canonicalHeight,frozen.composition,frozen.featherTotalAt3072)).copy(appearance=frozen.appearance)
                val imprint=dev.filmroom.imprint.ImprintPlan.create(job.parameters.imprint,job.photoMetadata?.let(dev.filmroom.io.PhotoMetadata::fromJson) ?: dev.filmroom.io.PhotoMetadata.unavailable(),snapshot.outputWidth,snapshot.outputHeight,snapshot)
                BorderPreview.compose(original,snapshot,raster,imprint,checkActive)
            } ?: job.crop?.takeUnless{it.isIdentity && !job.parameters.imprint.enabled}?.let{crop->
                val snapshot=CropSnapshot.create(original.width,original.height,crop.canonicalWidth,crop.canonicalHeight,crop.settings)
                val imprint=dev.filmroom.imprint.ImprintPlan.create(job.parameters.imprint,job.photoMetadata?.let(dev.filmroom.io.PhotoMetadata::fromJson) ?: dev.filmroom.io.PhotoMetadata.unavailable(),snapshot.outputWidth,snapshot.outputHeight)
                CropPreview.compose(original,snapshot,imprint,checkActive)
            } ?: original
            val image=checkNotNull(composed);val scale=minOf(1.0,256.0/maxOf(image.width,image.height))
            small=if(scale==1.0)image else Bitmap.createScaledBitmap(image,(image.width*scale).toInt().coerceAtLeast(1),(image.height*scale).toInt().coerceAtLeast(1),true)
            FileOutputStream(part).use{check(checkNotNull(small).compress(Bitmap.CompressFormat.JPEG,88,it));it.fd.sync()}
            checkActive();check(token(dir)==id)
            Files.move(part.toPath(),File(dir,"scan-preview-$id.jpg").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{part.delete();if(small!==composed)small?.recycle();if(composed!==original)composed?.recycle();original.recycle()}
    }
}
