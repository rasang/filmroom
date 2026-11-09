package dev.filmroom.effects

/** One logical layout shared by preview, video, source fitting, bezel and etched marks. */
internal object EffectGeometry {
    /** Ignore only the aspect uncertainty from independently rounded preview dimensions.
     * Each bitmap edge may differ by one raster pixel; film rails are much wider than this band.
     */
    fun needsPreviewFinder(sourceWidth:Int,sourceHeight:Int,resultWidth:Int,resultHeight:Int):Boolean {
        require(sourceWidth>0 && sourceHeight>0 && resultWidth>0 && resultHeight>0)
        val cross=kotlin.math.abs(sourceWidth.toLong()*resultHeight-resultWidth.toLong()*sourceHeight)
        val roundingBand=sourceWidth.toLong()+sourceHeight+resultWidth+resultHeight
        return cross>roundingBand
    }
    /** Opening finder contained in the unchanged result canvas, shared by preview and video/Live export. */
    data class PreviewFinder(val scaleX:Float,val scaleY:Float,val rasterWidth:Int,val rasterHeight:Int,val aspect:Float)
    fun previewFinder(width:Int,height:Int,sourceAspect:Float):PreviewFinder {
        require(width>0 && height>0 && sourceAspect>0f && sourceAspect.isFinite())
        val canvasAspect=width.toFloat()/height
        val sx=if(canvasAspect>sourceAspect)sourceAspect/canvasAspect else 1f
        val sy=if(canvasAspect>sourceAspect)1f else canvasAspect/sourceAspect
        return PreviewFinder(sx,sy,kotlin.math.round(width*sx).toInt().coerceAtLeast(1),
            kotlin.math.round(height*sy).toInt().coerceAtLeast(1),sourceAspect)
    }
    data class OpticalLayout(val outerW:Float,val outerH:Float,val padX:Float,val padY:Float,val portrait:Boolean)
    fun opticalLayout(sourceAspect:Float):OpticalLayout {
        require(sourceAspect>0 && sourceAspect.isFinite())
        val imageW=if(sourceAspect>=1f)400f else 400f*sourceAspect
        val imageH=if(sourceAspect>=1f)400f/sourceAspect else 400f
        // A portrait finder is a clockwise-rotated camera: its top readout is on the right.
        // Use the final, already oriented/composed bitmap aspect, never raw EXIF orientation.
        val portrait=sourceAspect<1f
        val scale=1f+44f/minOf(imageW,imageH)
        val pad=(1f-1f/scale)/2f
        return OpticalLayout(imageW*scale,imageH*scale,pad,pad,portrait)
    }
    fun sceneAspect(kind:EffectKind,sourceAspect:Float):Float =
        if(kind==EffectKind.LIGHT_TABLE)1.1124f else opticalLayout(sourceAspect).let{it.outerW/it.outerH}
    fun sourceViewportAspect(width:Int,height:Int,sourceAspect:Float):Float {
        require(width>0 && height>0)
        val l=opticalLayout(sourceAspect)
        return width.toFloat()/height*(1f-2f*l.padX)/(1f-2f*l.padY)
    }
    data class Marks(val width:Float,val height:Float,val imageWidth:Float,val imageHeight:Float,
                     val frameWidth:Float,val frameHeight:Float,val radius:Float,val arm:Float,val crossHalf:Float,val stroke:Float=.5208333f)
    fun marks(width:Int,height:Int,sourceAspect:Float,canvasAspect:Float=sourceAspect):Marks {
        require(width>0 && height>0)
        val l=opticalLayout(canvasAspect)
        val iw=l.outerW*(1f-2f*l.padX)
        // Match the actual render target (including encoder alignment) with isotropic logical units.
        val ih=(l.outerW*height/width)*(1f-2f*l.padY)
        val viewport=iw/ih
        val imageW=if(viewport>sourceAspect)ih*sourceAspect else iw
        val imageH=if(viewport>sourceAspect)ih else iw/sourceAspect
        val fw=imageW*.56f;val fh=imageH*.56f;val small=kotlin.math.min(fw,fh)
        return Marks(iw,ih,imageW,imageH,fw,fh,kotlin.math.min(16.25f,small*.22f),
            kotlin.math.min(11.25f,small*.20f),kotlin.math.min(3.75f,small*.04f))
    }
}
