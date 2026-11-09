package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/** Export owns these bitmaps; preview textures/bitmaps are never reused as export inputs. */
internal class EffectExportImages : AutoCloseable {
    lateinit var source: Bitmap; private set
    lateinit var result: Bitmap; private set
    lateinit var requested: EffectExportSize; private set
    private val owned = mutableListOf<Bitmap>()

    fun load(resolver:ContentResolver, kind:EffectKind, sourceUri:String?, resultUri:String,
             quality:EffectExportQuality, live:Boolean=false, composition:EffectSourceComposition?=null, cancelled:()->Boolean) = EffectExportLog.trace(EffectExportScope.INPUT_DECODE) { log ->
        fun checkCancel(){if(cancelled())throw CancellationException("Image decode cancelled")}
        fun bounds(uri:String):EffectExportSize {
            checkCancel()
            var size:EffectExportSize?=null
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,Uri.parse(uri))){d,info,_->
                size=EffectExportSize(info.size.width,info.size.height)
                d.setTargetSize(1,1); d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }.recycle()
            checkCancel();return requireNotNull(size)
        }
        val output=bounds(resultUri)
        val input=if(kind==EffectKind.LIGHT_TABLE)output else bounds(requireNotNull(sourceUri))
        val composed=if(kind==EffectKind.LIGHT_TABLE)input else composition?.size(input.width,input.height) ?: input
        requested=EffectExportResolution.desired(kind,composed.width,composed.height,output.width,output.height,quality)
        val native=EffectExportResolution.desired(kind,composed.width,composed.height,output.width,output.height,EffectExportQuality.ORIGINAL)
        val runtime=Runtime.getRuntime()
        val remaining=(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())).coerceAtLeast(0)
        val budget=decodeBudgetEdge(runtime.maxMemory(),remaining,live,composition!=null && kind!=EffectKind.LIGHT_TABLE)
        val qualityScale=minOf(1.0,requested.longEdge.toDouble()/native.longEdge)
        val memoryScale=budget.toDouble()/maxOf(input.longEdge,output.longEdge)
        log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.DECODE_BUDGET,
            if(memoryScale<qualityScale)EffectExportReason.DECODE_MEMORY_BUDGET else EffectExportReason.NONE,
            requestedWidth=requested.width,requestedHeight=requested.height,
            sourceWidth=input.width,sourceHeight=input.height,resultWidth=output.width,resultHeight=output.height,
            maxHeapBytes=runtime.maxMemory(),remainingBytes=remaining,budgetEdge=budget))
        check(budget>=480){"Not enough memory to decode export photos"}
        val scale=minOf(1.0,requested.longEdge.toDouble()/native.longEdge,
            budget.toDouble()/maxOf(input.longEdge,output.longEdge))
        fun read(uri:String,size:EffectExportSize):Bitmap {
            checkCancel()
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,Uri.parse(uri))){d,_,_->
                d.setTargetSize((size.width*scale).toInt().coerceAtLeast(1),(size.height*scale).toInt().coerceAtLeast(1))
                d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
            owned.add(bitmap);checkCancel();return bitmap
        }
        result=read(resultUri,output)
        source=if(kind==EffectKind.LIGHT_TABLE)result else {
            val raw=read(requireNotNull(sourceUri),input)
            val transformed=composition?.applyForExport(raw,::checkCancel) ?: raw
            if(transformed!==raw){owned.add(transformed);owned.remove(raw);raw.recycle()}
            transformed
        }
        log.record(EffectExportDiagnosticInfo(log.scope,EffectExportEvent.INPUT_DECODED,
            sourceWidth=source.width,sourceHeight=source.height,resultWidth=result.width,resultHeight=result.height,
            requestedWidth=requested.width,requestedHeight=requested.height))
    }
    override fun close(){owned.forEach{if(!it.isRecycled)it.recycle()};owned.clear()}
    companion object {
        /** Live additionally reserves space for video frame extraction and an SDR cover copy. */
        internal fun decodeBudgetEdge(maxHeap:Long, remaining:Long, live:Boolean=false, composing:Boolean=false):Int {
            val safeBytes=(remaining/2).coerceAtLeast(0)
            return minOf(EffectExportResolution.budgetEdge(maxHeap),sqrt(safeBytes/((if(live)24.0 else 16.0)+(if(composing)8.0 else 0.0))).toInt()).coerceAtLeast(1)
        }
    }
}
