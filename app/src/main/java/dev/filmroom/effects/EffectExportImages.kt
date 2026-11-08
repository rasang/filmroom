package dev.filmroom.effects

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
             quality:EffectExportQuality, live:Boolean=false, cancelled:()->Boolean) {
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
        requested=EffectExportResolution.desired(kind,input.width,input.height,output.width,output.height,quality)
        val native=EffectExportResolution.desired(kind,input.width,input.height,output.width,output.height,EffectExportQuality.ORIGINAL)
        val runtime=Runtime.getRuntime()
        val remaining=(runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())).coerceAtLeast(0)
        val budget=decodeBudgetEdge(runtime.maxMemory(),remaining,live)
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
        source=if(kind==EffectKind.LIGHT_TABLE)result else read(requireNotNull(sourceUri),input)
    }
    override fun close(){owned.forEach{if(!it.isRecycled)it.recycle()};owned.clear()}
    companion object {
        /** Live additionally reserves space for video frame extraction and an SDR cover copy. */
        internal fun decodeBudgetEdge(maxHeap:Long, remaining:Long, live:Boolean=false):Int {
            val safeBytes=(remaining/2).coerceAtLeast(0)
            return minOf(EffectExportResolution.budgetEdge(maxHeap),sqrt(safeBytes/(if(live)24.0 else 16.0)).toInt()).coerceAtLeast(1)
        }
    }
}
