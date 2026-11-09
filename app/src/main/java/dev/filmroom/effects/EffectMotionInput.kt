package dev.filmroom.effects

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.filmroom.livephoto.importer.MotionPhotoExtractor
import java.io.File
import java.util.concurrent.CancellationException

/** Reconstructed from beta27 DEX; copies only bounded JPEG motion-photo inputs. */
internal object EffectMotionInput {
    private const val MAX_INPUT=134217728L
    fun prepare(context:Context,uri:String?,checkActive:()->Unit={}):File? = try {
        prepareChecked(context,uri,checkActive)
    } catch(e:CancellationException){throw e}
      catch(e:Exception){Log.w("EffectMotionInput","Motion input unavailable; using still photo",e);null}
      catch(_:OutOfMemoryError){null}
    private fun prepareChecked(context:Context,uri:String?,checkActive:()->Unit):File? {
        uri ?: return null
        val directory=File(context.cacheDir,"effect-motion").apply{mkdirs()}
        val input=File.createTempFile("input-",".jpg",directory)
        var video:File?=null
        var retained=false
        try {
            checkActive()
            val stream=context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            stream.use { src -> input.outputStream().use { dst ->
                if(src.read()!=255 || src.read()!=216)return null
                dst.write(byteArrayOf(-1,-40))
                val buffer=ByteArray(65536);var total=2L
                while(true){checkActive();val count=src.read(buffer);if(count<0)break
                    total+=count;if(total>MAX_INPUT)return null;dst.write(buffer,0,count)}
            }}
            checkActive()
            val inspected=MotionPhotoExtractor.inspect(input,checkActive=checkActive) ?: return null
            val target=File.createTempFile("video-",".mp4",directory);video=target
            check(target.delete())
            MotionPhotoExtractor.extract(inspected,target,checkActive)
            checkActive();retained=true;return target
        } catch(e:CancellationException){throw e}
          catch(e:Exception){Log.w("EffectMotionInput","Original motion video unavailable; using still photo",e);return null}
          finally {input.delete();if(!retained)video?.delete()}
    }
}
