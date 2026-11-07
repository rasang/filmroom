package dev.filmroom.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Display-only derivative of the already-saved photograph. Never changes the saved media. */
internal object ExportFinalPreview {
    const val MAX_EDGE=1024
    const val WORKING_BYTES=16L*1024*1024
    fun decode(context:Context,uri:String,maxEdge:Int=MAX_EDGE):Bitmap {
        require(maxEdge in 1..MAX_EDGE)
        return ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver,Uri.parse(uri))
    ) { decoder,info,_->
        val scale=minOf(1.0,maxEdge.toDouble()/maxOf(info.size.width,info.size.height))
        decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
        decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    }
    fun write(bitmap:Bitmap,dir:File) {
        require(bitmap.width in 1..MAX_EDGE && bitmap.height in 1..MAX_EDGE)
        val part=File(dir,"final-preview.jpg.part")
        try {
            FileOutputStream(part).use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,it));it.fd.sync()}
            Files.move(part.toPath(),File(dir,"final-preview.jpg").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{part.delete()}
    }
}
