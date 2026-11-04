package dev.filmroom.border

import dev.filmroom.io.ExportFormat
import dev.filmroom.io.PhotoException

/** Existing codec limits apply to the FINAL canvas. Never silently resize a requested original. */
object BorderExportPolicy {
    data class Option(val label:String,val available:Boolean)
    /** One unsupported full size must not crash or disable a valid smaller export. */
    fun option(stock:String,width:Int,height:Int,enabled:Boolean,provider:BorderProvider=BorderCatalog,canonicalWidth:Int=width,canonicalHeight:Int=height,composition:dev.filmroom.model.CompositionTransform=dev.filmroom.model.CompositionTransform(),negativeWhiteHoles:Boolean=true,outputRoute:String?=null,mediumFormat:MediumFormat?=null,mediumPortrait:Boolean=false):Option {
        if(!enabled)return Option("$width × $height",true)
        return try {
            require(mediumFormat!=null || !mediumPortrait)
            val s=if(mediumFormat!=null)provider.snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition,negativeWhiteHoles,requireNotNull(outputRoute){"120 capture requires explicit output route"},mediumFormat,mediumPortrait)
                else if(outputRoute==null)provider.snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition,negativeWhiteHoles)
                else provider.snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition,negativeWhiteHoles,outputRoute)
            Option(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ad1d18f9d3e5, s.outputWidth, s.outputHeight),true)
        }catch(_:IllegalArgumentException){Option(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_96fa923afb1a, width, height),false)}
        catch(_:PhotoException){Option(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6e8f80256c84, width, height),false)}
    }
    // Framed float spool + two conservative JPEG-sized encoded copies (metadata rewrite / publication).
    fun additionalDiskBytes(s:BorderSnapshot):Long = Math.addExact(Math.multiplyExact(s.outputBytes,3L),8L*1024*1024)
    fun validate(snapshot:BorderSnapshot,format:ExportFormat) {
        if(format!=ExportFormat.PNG && snapshot.outputWidth.toLong()*snapshot.outputHeight>84_000_000L)
            throw PhotoException(PhotoException.Reason.TOO_LARGE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_ff27ed115be8))
    }
}
