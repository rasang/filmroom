package dev.filmroom.livephoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.filmroom.livephoto.core.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Only a frame-decoder/resource failure; never a file/publish/permission failure. */
internal class CoverDecodeException(message:String, cause:Throwable?=null): java.io.IOException(message,cause)

internal object MediaProbe {
    data class Info(val width: Int, val height: Int, val rotation: Int, val durationUs: Long, val mime: String, val hdr: Boolean, val hasAudio: Boolean) {
        val displayWidth get() = if(rotation == 90 || rotation == 270) height else width
        val displayHeight get() = if(rotation == 90 || rotation == 270) width else height
    }
    private fun int(f:MediaFormat,key:String,default:Int=0) = if(f.containsKey(key))f.getInteger(key) else default
    private fun inspect(extractor:MediaExtractor):Info {
        var video:MediaFormat?=null; var audio=false
        for(i in 0 until extractor.trackCount) {
            val f=extractor.getTrackFormat(i); val mime=f.getString(MediaFormat.KEY_MIME).orEmpty()
            if(mime.startsWith("video/")) {
                if(video!=null)throw LivePhotoException(ErrorCode.UNSUPPORTED_INPUT,"Only a single video track is supported")
                video=f
            }
            if(mime.startsWith("audio/"))audio=true
        }
        val v=video ?: throw LivePhotoException(ErrorCode.UNSUPPORTED_INPUT,"No video track")
        val duration=if(v.containsKey(MediaFormat.KEY_DURATION))v.getLong(MediaFormat.KEY_DURATION) else -1L
        val transfer=int(v,MediaFormat.KEY_COLOR_TRANSFER)
        val width=int(v,MediaFormat.KEY_WIDTH); val height=int(v,MediaFormat.KEY_HEIGHT)
        val rotation=int(v,MediaFormat.KEY_ROTATION)
        if(duration<=0 || width<=0 || height<=0 || width.toLong()*height>67_108_864 || rotation !in setOf(0,90,180,270))
            throw LivePhotoException(ErrorCode.UNSUPPORTED_INPUT,"Missing/unsupported duration, dimensions or rotation")
        return Info(width,height,rotation,duration,v.getString(MediaFormat.KEY_MIME).orEmpty(),
            transfer==MediaFormat.COLOR_TRANSFER_ST2084 || transfer==MediaFormat.COLOR_TRANSFER_HLG || v.getString(MediaFormat.KEY_MIME)=="video/dolby-vision",audio)
    }
    fun input(context:Context,uri:Uri):Info {
        val e=MediaExtractor()
        try { e.setDataSource(context,uri,null); return inspect(e) } finally { e.release() }
    }
    fun normalized(file:File):Info {
        val e=MediaExtractor()
        try {
            e.setDataSource(file.absolutePath); val info=inspect(e)
            if(info.mime!="video/avc" || info.hdr) throw LivePhotoException(ErrorCode.TRANSCODE_FAILED,"Expected SDR H.264 output")
            for(i in 0 until e.trackCount) {
                val f=e.getTrackFormat(i); val mime=f.getString(MediaFormat.KEY_MIME).orEmpty()
                if(mime.startsWith("audio/") && mime!="audio/mp4a-latm")throw LivePhotoException(ErrorCode.TRANSCODE_FAILED,"Expected AAC audio")
            }
            return info
        } finally { e.release() }
    }
    /** Enumerate actual presentation timestamps: no duration*fps approximation, including VFR inputs. */
    fun coverInfo(file:File,requestedUs:Long,checkActive:()->Unit):CoverInfo {
        val info=normalized(file); val e=MediaExtractor()
        try {
            e.setDataSource(file.absolutePath)
            val track=(0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            e.selectTrack(track)
            val pts=mutableListOf<Long>()
            while(e.sampleTime>=0) {
                checkActive()
                if(e.sampleSize<=0 || pts.size>=100_000)throw LivePhotoException(ErrorCode.RESOURCE_LIMIT,"Invalid or excessive video samples")
                pts += e.sampleTime
                if(!e.advance())break
            }
            if(pts.isEmpty())throw LivePhotoException(ErrorCode.COVER_FAILED,"Empty normalized video")
            pts.sort()
            if(pts.toSet().size!=pts.size)throw LivePhotoException(ErrorCode.UNSUPPORTED_INPUT,"Duplicate video presentation timestamps")
            if(requestedUs<pts.first() || requestedUs>=info.durationUs) {
                // Some muxers start video a few microseconds after zero. A requested first frame is still valid.
                if(requestedUs!=0L)throw LivePhotoException(ErrorCode.INVALID_REQUEST,"Cover position is outside the normalized clip")
            }
            val index=pts.indices.minByOrNull { abs(pts[it]-requestedUs) }!!
            val step=if(pts.size>1) (pts.last()-pts[pts.lastIndex-1]).coerceAtLeast(1) else 33_333L
            val duration=maxOf(info.durationUs,pts.last()+step)
            if(duration>60_000_000)throw LivePhotoException(ErrorCode.RESOURCE_LIMIT,"Normalized clip exceeds 60 seconds")
            return CoverInfo(pts[index],index,pts.size,duration)
        } finally { e.release() }
    }
    internal fun <T:Any> decodeCover(decode:()->T?):T {
        try { return decode() ?: throw CoverDecodeException("Could not decode cover frame") }
        catch(error:java.util.concurrent.CancellationException) { throw error }
        catch(error:SecurityException) { throw error }
        catch(error:OutOfMemoryError) { throw CoverDecodeException("Not enough memory to decode cover frame",error) }
        catch(error:RuntimeException) { throw CoverDecodeException("Could not decode cover frame",error) }
    }
    fun writeCover(file:File,cover:CoverInfo,output:File,quality:Int,maxEdge:Int,checkActive:()->Unit) {
        checkActive()
        val expected=normalized(file)
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            // Exact output-sample PTS, not the original video's time. The retriever applies rotation.
            val frame=decodeCover {
                retriever.getFrameAtTime(cover.timestampUs,MediaMetadataRetriever.OPTION_CLOSEST)
            }
            try {
                if(frame.width!=expected.displayWidth || frame.height!=expected.displayHeight)
                    throw CoverDecodeException("Decoder changed the cover dimensions")
                if(maxOf(frame.width,frame.height)>maxEdge+16)throw LivePhotoException(ErrorCode.COVER_FAILED,"Decoder returned an unexpectedly large cover")
                val reusable=frame.config==Bitmap.Config.ARGB_8888 && frame.colorSpace==ColorSpace.get(ColorSpace.Named.SRGB)
                val sdr=if(reusable)frame else try {
                    Bitmap.createBitmap(frame.width,frame.height,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.SRGB))
                } catch(error:OutOfMemoryError) { throw CoverDecodeException("Not enough memory for cover frame",error) }
                try {
                    if(sdr!==frame)Canvas(sdr).drawBitmap(frame,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
                    checkActive()
                    FileOutputStream(output).use { out ->
                        if(!sdr.compress(Bitmap.CompressFormat.JPEG,quality,out))throw LivePhotoException(ErrorCode.COVER_FAILED,"JPEG encoding failed")
                        out.fd.sync()
                    }
                } finally { if(sdr!==frame)sdr.recycle() }
            } finally { frame.recycle() }
        } finally { retriever.release() }
    }
}

