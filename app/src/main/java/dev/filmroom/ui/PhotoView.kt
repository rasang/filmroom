package dev.filmroom.ui

import android.content.Context
import android.graphics.*
import android.view.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.model.CompositionTransform
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.model.CropSettings
import dev.filmroom.model.CropMode
import dev.filmroom.roi.*

/** Underlay, comparison and local cells share one source-oriented fit/zoom/pan transform. */
class PhotoView(context: Context): View(context), AutoCloseable {
    val viewport = Viewport()
    var bitmap: Bitmap?=null; set(value) {
        val previous=transform();field=value
        if(logicalAspect==null && value!=null)viewport.sourceAspect(value.width.toDouble()/value.height)
        if(detail==null)viewport.restore(previous[0].toDouble(),previous[1].toDouble(),previous[2].toDouble())
        invalidate()
    }
    private var imprintPlan:dev.filmroom.imprint.ImprintPlan?=null
    private var imprintOverlayUnavailable=false
    private var imprintOverlays:List<Pair<dev.filmroom.imprint.ImprintPlan.Stamp,Bitmap>> = emptyList()
    private var imprintDraftPreview = false
    fun bindImprint(plan:dev.filmroom.imprint.ImprintPlan?, draftPreview:Boolean=false){
        if(imprintPlan===plan && imprintDraftPreview==draftPreview)return
        imprintDraftPreview=draftPreview
        imprintOverlays.forEach{it.second.recycle()};imprintOverlays=emptyList();imprintPlan=plan;imprintOverlayUnavailable=false
        invalidate()
    }
    /** Transient drag uses Canvas like the existing photo/frame interaction. Settled output uses linear rows. */
    private fun drawLiveImprint(canvas:Canvas):Boolean{
        val plan=imprintPlan ?: return true
        if(imprintOverlayUnavailable)return false
        if(imprintOverlays.isEmpty() && !plan.isEmpty){
            val created=mutableListOf<Pair<dev.filmroom.imprint.ImprintPlan.Stamp,Bitmap>>()
            try {
                for(stamp in plan.stamps){
                    val r=stamp.raster;val b=Bitmap.createBitmap(r.width,r.height,Bitmap.Config.ARGB_8888)
                    try {val pixels=IntArray(r.width);for(y in 0 until r.height){r.writeArgbRow(y,pixels);b.setPixels(pixels,0,r.width,0,y,r.width,1)}}catch(t:Throwable){b.recycle();throw t}
                    created+=stamp to b
                }
                imprintOverlays=created
            }catch(_:OutOfMemoryError){created.forEach{it.second.recycle()};imprintOverlayUnavailable=true;return false}
        }
        for((stamp,b) in imprintOverlays){
            val rect=RectF((viewport.left+stamp.left*viewport.imageWidth/plan.width).toFloat(),(viewport.top+stamp.top*viewport.imageHeight/plan.height).toFloat(),
                (viewport.left+stamp.right*viewport.imageWidth/plan.width).toFloat(),(viewport.top+stamp.bottom*viewport.imageHeight/plan.height).toFloat())
            canvas.drawBitmap(b,null,rect,paint)
        }
        return true
    }
    private var presentation: BorderSnapshot? = null
    private var cropPresentation: CropSnapshot? = null
    private var freeCropping=false
    private var handle=0
    private val canonicalWidth get()=presentation?.canonicalWidth ?: cropPresentation?.canonicalWidth ?: 1
    private val canonicalHeight get()=presentation?.canonicalHeight ?: cropPresentation?.canonicalHeight ?: 1
    private fun exactComposition()=presentation?.composition ?: cropPresentation?.settings?.effectiveTransform(canonicalWidth,canonicalHeight)
    private fun viewportRect()=RectF(viewport.left.toFloat(),viewport.top.toFloat(),(viewport.left+viewport.imageWidth).toFloat(),(viewport.top+viewport.imageHeight).toFloat())
    private val freeEdit get()=freeCropping && compositionEditing && cropPresentation!=null
    private var composition = CompositionTransform()
    private var compositionEditing = false
    private var compositionDirty = false
    private var compositionSource: Bitmap? = null
    private var interactiveFrame: Bitmap? = null
    /** Pre-imaged source is the adopted full scientific frame (including imaged imprints), never the original photo. */
    var preImagingCompositionPreview:Boolean=false
        set(value){
            if(field==value)return
            field=value;compositionDirty=false;liveComposition=false;handle=0;invalidate()
        }
    private var liveComposition = false
    private var lastBoundComposition = CompositionTransform()
    internal val compositionGeometryReady: Boolean get() {
        if(freeEdit)return true // Free handles alter aspect in source coordinates, not a fixed aperture.
        val requested=composition.aspect(canonicalWidth,canonicalHeight)
        val shown=presentation?.let{if(it.geometryVersion==1)return true else it.composition.aspect(it.canonicalWidth,it.canonicalHeight)}
            ?: cropPresentation?.settings?.effectiveTransform(canonicalWidth,canonicalHeight)?.aspect(canonicalWidth,canonicalHeight)
            ?: return false
        return kotlin.math.abs(requested-shown)<=1e-9*maxOf(1.0,shown)
    }
    internal val isLiveComposition get() = liveComposition && usableInteractiveFrame() && compositionGeometryReady
    private fun usableInteractiveFrame() = compositionSource?.isRecycled == false && ((interactiveFrame?.isRecycled == false && presentation != null) || cropPresentation!=null ||
        (preImagingCompositionPreview && presentation?.let{compositionSource?.width==it.outputWidth && compositionSource?.height==it.outputHeight}==true))
    private var compositionChanged: ((CompositionTransform, Boolean)->Unit)? = null
    private var lockedViewer: DoubleArray? = null
    private var pendingViewer: DoubleArray? = null
    internal val isCompositionEditing get() = compositionEditing
    /** Canonical processed-photo bounds; independent of the fixed border/viewer transform. */
    internal fun compositionPhotoRect(transform:CompositionTransform=composition): RectF? {
        if(presentation==null && cropPresentation==null)return null
        val aperture=compositionAperture() ?: return null
        val c=transform.clamped(canonicalWidth,canonicalHeight)
        val r=c.rect(canonicalWidth,canonicalHeight)
        val w=aperture.width()/r.width;val h=aperture.height()/r.height
        val left=aperture.centerX()-c.centerX*w;val top=aperture.centerY()-c.centerY*h
        return RectF(left.toFloat(),top.toFloat(),(left+w).toFloat(),(top+h).toFloat())
    }
    private fun displayedComposition()=if(isLiveComposition || freeEdit)composition else exactComposition() ?: composition
    private fun presentedPhotoRect():RectF?=exactComposition()?.let{compositionPhotoRect(if(isLiveComposition || freeEdit)composition else it)}
    internal fun compositionAperture(): RectF? {
        val p=presentation
        if(p==null){
            if(cropPresentation==null)return null
            val r=viewportRect()
            if(freeEdit){val c=composition.rect(canonicalWidth,canonicalHeight);return RectF((r.left+c.left*r.width()).toFloat(),(r.top+c.top*r.height()).toFloat(),(r.left+c.right*r.width()).toFloat(),(r.top+c.bottom*r.height()).toFloat())}
            return r
        }
        val sx=viewport.imageWidth/p.outputWidth;val sy=viewport.imageHeight/p.outputHeight
        return RectF((viewport.left+p.apertureLeft*sx).toFloat(),(viewport.top+p.apertureTop*sy).toFloat(),
            (viewport.left+(p.apertureLeft+p.photoWidth)*sx).toFloat(),(viewport.top+(p.apertureTop+p.photoHeight)*sy).toFloat())
    }
    /** Aperture-only canonical demand. Controller adapter must opt in; never samples frame art. */
    internal fun compositionVisibleRect(key:RenderKey):PixelRect? {
        val a=compositionAperture() ?: return viewport.visibleRect(key)
        val p=presentedPhotoRect() ?: return null
        if(p.width()<=0 || p.height()<=0 || !a.intersect(0f,0f,width.toFloat(),height.toFloat()))return null
        val aperture=compositionAperture() ?: return null
        val points=floatArrayOf(a.left,a.top,a.right,a.top,a.right,a.bottom,a.left,a.bottom)
        Matrix().apply{setRotate(-displayedComposition().straightenDegrees.toFloat(),aperture.centerX(),aperture.centerY());mapPoints(points)}
        val xs=(0..3).map{((points[it*2]-p.left)/p.width()).coerceIn(0f,1f)}
        val ys=(0..3).map{((points[it*2+1]-p.top)/p.height()).coerceIn(0f,1f)}
        val x0=kotlin.math.floor(xs.min()*key.virtualFullWidth).toInt()
        val y0=kotlin.math.floor(ys.min()*key.virtualFullHeight).toInt()
        val x1=kotlin.math.ceil(xs.max()*key.virtualFullWidth).toInt()
        val y1=kotlin.math.ceil(ys.max()*key.virtualFullHeight).toInt()
        if(x1<=x0 || y1<=y0)return null
        return PixelRect(x0,y0,x1,y1)
    }
    internal fun compositionVisibleTiles(key:RenderKey):List<TileId> {
        val r=compositionVisibleRect(key) ?: return emptyList()
        val x0=r.left;val y0=r.top;val x1=r.right;val y1=r.bottom
        val result=mutableListOf<TileId>()
        for(y in y0/TileId.EDGE..(y1-1)/TileId.EDGE)for(x in x0/TileId.EDGE..(x1-1)/TileId.EDGE)result+=TileId(x,y)
        return result.sortedBy{val r=it.rect(key);val dx=(r.left+r.right-x0-x1).toDouble();val dy=(r.top+r.bottom-y0-y1).toDouble();dx*dx+dy*dy}
    }
    internal fun compositionDetailZoom():Double {
        if(presentation==null && cropPresentation==null)return viewport.zoom
        val bounds=presentedPhotoRect() ?: return 1.0
        val fit=minOf(width.toDouble(),height.toDouble()*canonicalWidth/canonicalHeight)
        return if(fit>0)bounds.width()/fit else 1.0
    }
    fun bindComposition(presentation: BorderSnapshot?, source: Bitmap?, transform: CompositionTransform,
                        editing: Boolean, onChanged: (CompositionTransform, Boolean)->Unit) =
        bindComposition(presentation,source,transform,editing,null,onChanged)
    fun bindComposition(presentation: BorderSnapshot?, source: Bitmap?, transform: CompositionTransform,
                        editing: Boolean, interactiveBorder: Bitmap?,
                        onChanged: (CompositionTransform, Boolean)->Unit) = bindPresentation(presentation,null,source,transform,editing,false,interactiveBorder,onChanged)
    fun bindCrop(snapshot:CropSnapshot?,source:Bitmap?,settings:CropSettings,editing:Boolean,onChanged:(CompositionTransform,Boolean)->Unit) =
        bindPresentation(null,snapshot,source,settings.effectiveTransform(snapshot?.canonicalWidth ?: source?.width ?: 1,snapshot?.canonicalHeight ?: source?.height ?: 1),editing,settings.mode==CropMode.FREE,null,onChanged)
    private fun bindPresentation(presentation:BorderSnapshot?,crop:CropSnapshot?,source:Bitmap?,transform:CompositionTransform,editing:Boolean,free:Boolean,interactiveBorder:Bitmap?,onChanged:(CompositionTransform,Boolean)->Unit) {
        val previous=this.presentation
        val sameBorder=previous?.let{it.copy(composition=CompositionTransform(targetAspect=it.composition.targetAspect))}==presentation?.let{it.copy(composition=CompositionTransform(targetAspect=it.composition.targetAspect))}
        val sameCrop=(cropPresentation==null && crop==null) || (cropPresentation!=null && crop!=null && cropPresentation!!.canonicalWidth==crop.canonicalWidth && cropPresentation!!.canonicalHeight==crop.canonicalHeight)
        val sameContent=source===compositionSource && sameBorder && sameCrop
        val active=editing && (presentation!=null || crop!=null) && source!=null
        // A new processed source/asset invalidates a gesture; never commit its crop into new content.
        if(!sameContent && (presentation!=null || crop!=null))compositionDirty=false
        if(compositionEditing && (!active || this.presentation?.stock!=presentation?.stock))finishCompositionGesture()
        if(!sameContent || (crop==null && presentation==null)){liveComposition=false;compositionDirty=false}
        // A delayed bind carrying the previously bound parameters must not undo a newer local MOVE.
        if((!liveComposition && !compositionDirty) || transform!=lastBoundComposition)composition=transform
        lastBoundComposition=transform
        this.presentation=presentation;cropPresentation=crop;freeCropping=free;compositionSource=source;interactiveFrame=interactiveBorder;compositionChanged=onChanged
        if(!compositionDirty && exactComposition()==composition && transform==composition)liveComposition=false
        else if(usableInteractiveFrame() && (crop!=null || sameContent || previous==null))liveComposition=true
        if(active!=compositionEditing){
            if(active){lockedViewer=pendingViewer ?: doubleArrayOf(viewport.zoom,viewport.centerX,viewport.centerY);pendingViewer=null;viewport.reset()}
            else {lockedViewer?.let(::applyViewer);lockedViewer=null}
            compositionEditing=active
        }
        if(!compositionGeometryReady){liveComposition=false;compositionDirty=false;handle=0}
        if(crop!=null){
            val aspect=if(freeEdit)canonicalWidth.toDouble()/canonicalHeight else if(compositionGeometryReady)composition.aspect(canonicalWidth,canonicalHeight) else crop.settings.effectiveTransform(canonicalWidth,canonicalHeight).aspect(canonicalWidth,canonicalHeight)
            logicalAspect=aspect;viewport.sourceAspect(aspect)
        }
        if(active)viewport.reset()
        contentDescription=if(freeEdit)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_70ceea90cbaf) else if(active)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c8da2b308ae6) else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c85ef88eb1b1)
        bindDetailState();changed()
    }
    fun discardCompositionGesture(){compositionDirty=false;liveComposition=false;handle=0;moving=false;changed()}
    fun finishCompositionGesture(){
        if(!compositionDirty)return
        compositionDirty=false
        compositionChanged?.invoke(composition,true)
        if(exactComposition()==composition)liveComposition=false
    }
    fun viewerState():FloatArray=(lockedViewer ?: pendingViewer ?: doubleArrayOf(viewport.zoom,viewport.centerX,viewport.centerY)).map{it.toFloat()}.toFloatArray()
    fun restoreViewerState(values:FloatArray){
        if(values.size!=3 || values.any{!it.isFinite()})return
        val state=doubleArrayOf(values[0].toDouble(),values[1].toDouble(),values[2].toDouble())
        if(compositionEditing)lockedViewer=state else {pendingViewer=state;applyPendingTransform()};changed()
    }
    private fun applyViewer(state:DoubleArray){
        viewport.restore(state[0],0.0,0.0)
        viewport.restore(state[0],(.5-state[1])*viewport.imageWidth,(.5-state[2])*viewport.imageHeight)
    }
    fun straighten(degrees:Double,commit:Boolean){
        if(!compositionEditing || !compositionGeometryReady || !degrees.isFinite())return
        editComposition(composition.copy(straightenDegrees=degrees.coerceIn(-CompositionTransform.MAX_STRAIGHTEN,CompositionTransform.MAX_STRAIGHTEN)).clamped(canonicalWidth,canonicalHeight))
        if(commit)finishCompositionGesture()
    }
    private fun editComposition(value:CompositionTransform){
        if(value==composition)return
        composition=value;compositionDirty=true;liveComposition=usableInteractiveFrame();compositionChanged?.invoke(value,false);changed()
    }
    private var logicalAspect: Double? = null
    private val target=RectF()
    private val sampleTarget=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var lastX=0f; private var lastY=0f
    private var moving=false
    private var pendingTransform: FloatArray?=null
    private var detail: EditorDetailController?=null
    private var detailSource:PhotoSource?=null
    private var detailParameters=EditorState()
    private var detailEligible=false
    private var detailComparing=false
    private var detailPresentedBorder=false
    private var disposed=false
    private var foreground=true
    var onDetailStatusChanged:((DetailStatus)->Unit)?=null
    val detailStatus get()=detail?.status ?: DetailStatus(DetailPhase.Paused)
    internal val detailKey get()=detail?.key
    internal val detailCacheBytes get()=detail?.cacheBytes ?: 0L
    private val scale=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean { pinch(detector.scaleFactor.toDouble(),detector.focusX.toDouble(),detector.focusY.toDouble());return true }
    })
    private val taps=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onDoubleTap(e:MotionEvent):Boolean { if(compositionEditing)return true; if(viewport.zoom>1)resetZoom() else pinch(2.0,e.x.toDouble(),e.y.toDouble());return true }
    })
    init { contentDescription=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c85ef88eb1b1);isFocusable=true;setBackgroundColor(FilmroomPalette.background) }
    fun configureDetail(provider: EditorDetailProvider) {
        check(!disposed)
        detail?.close(); detail=EditorDetailController(provider,viewport,{invalidate();onDetailStatusChanged?.invoke(detailStatus)},visibleRegion=::compositionVisibleRect,effectiveZoom=::compositionDetailZoom,retainContextWhileEditing={compositionEditing}).also{it.foreground(foreground)}
    }
    fun disableDetail(){detail?.close();detail=null;logicalAspect=null;bitmap?.let{viewport.sourceAspect(it.width.toDouble()/it.height)}}
    fun bindDetail(source: PhotoSource?, parameters: EditorState, eligible: Boolean, compareOriginal:Boolean=false, presentedBorder:Boolean=parameters.filmBorder && !compareOriginal) {
        val framed=presentedBorder && !compareOriginal
        logicalAspect=if(framed)bitmap?.let{it.width.toDouble()/it.height} else if(detail!=null) source?.let { it.originalWidth.toDouble()/it.originalHeight } else null
        logicalAspect?.let(viewport::sourceAspect)
        applyPendingTransform()
        detailSource=source;detailParameters=parameters.copy(filmBorder=false,negativeWhiteHoles=true,composition=CompositionTransform(),crop=CropSettings())
        detailEligible=eligible;detailComparing=compareOriginal;detailPresentedBorder=framed
        bindDetailState()
        invalidate()
    }
    private fun bindDetailState(){detail?.bind(detailSource,detailParameters,detailEligible && (!detailPresentedBorder || presentation!=null || cropPresentation!=null),detailComparing)}
    fun setForeground(value: Boolean) { if(!value)finishCompositionGesture();foreground=value; detail?.foreground(value) }
    fun releaseDetail() { detail?.releaseForPressure() }
    fun transform()=pendingTransform ?: floatArrayOf(viewport.zoom.toFloat(),((.5-viewport.centerX)*viewport.imageWidth).toFloat(),((.5-viewport.centerY)*viewport.imageHeight).toFloat())
    fun restoreTransform(values:FloatArray){if(values.size==3 && values.all{it.isFinite()}){pendingTransform=values.copyOf();applyPendingTransform();changed()}}
    private fun applyPendingTransform(){if(width>0 && height>0){
        pendingTransform?.let{viewport.restore(it[0].toDouble(),it[1].toDouble(),it[2].toDouble());pendingTransform=null}
        pendingViewer?.let{applyViewer(it);pendingViewer=null}
    }}
    fun resetZoom(){compositionDirty=false;lockedViewer=null;pendingViewer=null;pendingTransform=null;viewport.reset();changed()}
    fun pan(dx: Double,dy: Double){
        if(compositionEditing){if(!compositionGeometryReady)return;val a=compositionAperture() ?: return
            if(freeEdit){val r=composition.rect(canonicalWidth,canonicalHeight);editComposition(composition.copy(centerX=(composition.centerX+dx/viewport.imageWidth).coerceIn(r.width/2,1-r.width/2),centerY=(composition.centerY+dy/viewport.imageHeight).coerceIn(r.height/2,1-r.height/2)).clamped(canonicalWidth,canonicalHeight));return}
            editComposition(composition.pan(dx,dy,a.width().toDouble(),a.height().toDouble(),canonicalWidth,canonicalHeight));return}
        viewport.drag(dx,dy);changed()
    }
    fun pinch(factor: Double,x: Double,y: Double){
        if(compositionEditing){if(!compositionGeometryReady)return;val a=compositionAperture() ?: return
            if(a.width()<=0 || a.height()<=0)return
            editComposition(composition.zoomBy(factor,(x-a.left)/a.width(),(y-a.top)/a.height(),canonicalWidth,canonicalHeight));return}
        if(detail!=null)viewport.pinch(factor,x,y)
        else if(factor.isFinite() && factor>0){
            // Disabled-default path retains the existing center zoom and raw-pixel pan offsets.
            val previous=transform();viewport.restore(viewport.zoom*factor,previous[1].toDouble(),previous[2].toDouble())
        }
        changed()
    }
    private fun changed(){detail?.demand(moving);invalidate();onDetailStatusChanged?.invoke(detailStatus)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        val previous=transform();viewport.size(w,h)
        if(detail==null)viewport.restore(previous[0].toDouble(),previous[1].toDouble(),previous[2].toDouble())
        applyPendingTransform();if(compositionEditing)viewport.reset();changed()
    }
    var onNextPreviewDraw:((Bitmap)->Unit)?=null
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val b=bitmap?.takeUnless{it.isRecycled} ?: return
        target.set(viewport.left.toFloat(),viewport.top.toFloat(),(viewport.left+viewport.imageWidth).toFloat(),(viewport.top+viewport.imageHeight).toFloat())
        canvas.drawBitmap(b,null,target,paint)
        onNextPreviewDraw?.also{onNextPreviewDraw=null}?.invoke(b)
        if(isLiveComposition || freeEdit || (imprintDraftPreview && usableInteractiveFrame() && compositionGeometryReady)){
            if(preImagingCompositionPreview){
                drawProcessedComposition(canvas)
            }else{
            val p=presentation;val aperture=if(freeEdit)viewportRect()else compositionAperture()!!
            val photo=compositionPhotoRect()!!
            val save=canvas.save()
            // The precomputed overlay supplies the narrow frame alpha edge; no frame resynthesis here.
            if(p!=null){
                val guard=if(p.geometryVersion==4)4*p.pixelsPerMm else p.featherRadius
                aperture.inset(-(guard*viewport.imageWidth/p.outputWidth).toFloat(),
                    -(guard*viewport.imageHeight/p.outputHeight).toFloat())
            }
            canvas.clipRect(target)
            val pitch=p?.layout?.pitch ?: 0.0
            val shifts=if(pitch>0)listOf(-1,0,1)else listOf(0)
            for(shift in shifts){
                val dx=if(p?.portrait==true)(shift*pitch*p.pixelsPerMm*viewport.imageWidth/p.outputWidth).toFloat()else 0f
                val dy=if(p!=null && !p.portrait)(shift*pitch*p.pixelsPerMm*viewport.imageHeight/p.outputHeight).toFloat()else 0f
                val clip=RectF(aperture).apply{offset(dx,dy)}
                val destination=RectF(photo).apply{offset(dx,dy)}
                val repeatSave=canvas.save();canvas.clipRect(clip)
                val center=compositionAperture()!!
                canvas.rotate(composition.straightenDegrees.toFloat(),center.centerX()+dx,center.centerY()+dy)
                canvas.drawBitmap(compositionSource!!,null,destination,paint)
                canvas.restoreToCount(repeatSave)
            }
            canvas.restoreToCount(save)
            interactiveFrame?.takeUnless{it.isRecycled}?.let{canvas.drawBitmap(it,null,target,paint)}
            if(!drawLiveImprint(canvas))canvas.drawBitmap(b,null,target,paint) // Preserve exact settled glyphs under low memory.
            }
        }
        // Canvas is a transient interaction approximation. Exact shared-renderer output is canonical
        // after matching crop/source/asset handoff; detail uses the currently visible photo mapping.
        val aperture=compositionAperture();val photoRect=presentedPhotoRect()
        val key=detail?.key
        if(key==null){if(compositionEditing)drawAlignmentGrid(canvas);if(freeEdit)drawCropHandles(canvas);return}
        val save=canvas.save()
        canvas.clipRect(0,0,width,height);canvas.clipRect(target)
        imprintPlan?.let{plan->for(stamp in plan.stamps){
            canvas.clipOutRect((viewport.left+stamp.left*viewport.imageWidth/plan.width).toFloat(),(viewport.top+stamp.top*viewport.imageHeight/plan.height).toFloat(),
                (viewport.left+stamp.right*viewport.imageWidth/plan.width).toFloat(),(viewport.top+stamp.bottom*viewport.imageHeight/plan.height).toFloat())
        }}
        val sourceBounds=if(aperture!=null && photoRect!=null)photoRect else target
        if(aperture!=null && presentation!=null){
            // Detail cells must never overwrite the final compositor's aperture feather band.
            val p=presentation!!;val interior=RectF(aperture)
            interior.inset((p.featherRadius*viewport.imageWidth/p.outputWidth).toFloat(),(p.featherRadius*viewport.imageHeight/p.outputHeight).toFloat())
            if(p.geometryVersion==4){
                val scale=(p.pixelsPerMm*viewport.imageWidth/p.outputWidth).toFloat()
                interior.inset(4*scale,4*scale)
                val radius=(if(p.stock=="kodak_trix")40f else if(p.stock=="kodak_2302")12f else 4f)*scale
                val mask=android.graphics.Path().apply{addRoundRect(interior,radius,radius,android.graphics.Path.Direction.CW)}
                canvas.clipPath(mask)
            }else canvas.clipRect(interior)
        }
        if(aperture!=null){
            if(presentation==null)canvas.clipRect(aperture)
            canvas.rotate(displayedComposition().straightenDegrees.toFloat(),aperture.centerX(),aperture.centerY())
        }
        canvas.translate(sourceBounds.left,sourceBounds.top)
        canvas.scale(sourceBounds.width()/key.virtualFullWidth,sourceBounds.height()/key.virtualFullHeight)
        detail?.forEachDrawable { tile ->
            if(tile.key==key && !tile.bitmap.isRecycled){
                val tileSave=canvas.save();val core=tile.coreRect;val sample=tile.sampleRect
                canvas.clipRect(core.left,core.top,core.right,core.bottom)
                sampleTarget.set(sample.left.toFloat(),sample.top.toFloat(),sample.right.toFloat(),sample.bottom.toFloat())
                canvas.drawBitmap(tile.bitmap,null,sampleTarget,paint)
                tile.diagnosticTiming?.drawn();detail?.onTileDrawn(tile.diagnosticTiming)
                canvas.restoreToCount(tileSave)
            }
        }
        canvas.restoreToCount(save)
        if(compositionEditing)drawAlignmentGrid(canvas)
        if(freeEdit)drawCropHandles(canvas)
    }
    /** Transient delta on the adopted scientific image. Missing old crop pixels keep the adopted
     * base; no original-photo pixels, frame lettering, or approximate frame can enter export. */
    private fun drawProcessedComposition(canvas:Canvas){
        val p=presentation ?: return
        val source=compositionSource?.takeUnless{it.isRecycled} ?: return
        val aperture=compositionAperture() ?: return
        // onDraw already painted the adopted frame once. Repainting its antialiased outer edge
        // would change frame pixels; imprints are already imaged and receive no second overlay.
        fun points(t:CompositionTransform):FloatArray {
            val a=t.sourcePoint(0.0,0.0,canonicalWidth,canonicalHeight)
            val b=t.sourcePoint(1.0,0.0,canonicalWidth,canonicalHeight)
            val c=t.sourcePoint(0.0,1.0,canonicalWidth,canonicalHeight)
            return floatArrayOf(a.first.toFloat(),a.second.toFloat(),b.first.toFloat(),b.second.toFloat(),c.first.toFloat(),c.second.toFloat())
        }
        val sourceWindow=RectF(p.apertureLeft.toFloat(),p.apertureTop.toFloat(),(p.apertureLeft+p.photoWidth).toFloat(),(p.apertureTop+p.photoHeight).toFloat())
        val bitmapPoints=floatArrayOf(sourceWindow.left,sourceWindow.top,sourceWindow.right,sourceWindow.top,sourceWindow.left,sourceWindow.bottom)
        val screenPoints=floatArrayOf(aperture.left,aperture.top,aperture.right,aperture.top,aperture.left,aperture.bottom)
        val toCanonical=Matrix();val toScreen=Matrix();val delta=Matrix()
        if(toCanonical.setPolyToPoly(bitmapPoints,0,points(p.composition),0,3) && toScreen.setPolyToPoly(points(composition),0,screenPoints,0,3)){
            delta.setConcat(toScreen,toCanonical)
            fun interior(rect:RectF,scaleX:Float,scaleY:Float):Path {
                val r=RectF(rect)
                r.inset((maxOf(.5,p.featherRadius)*scaleX).toFloat(),(maxOf(.5,p.featherRadius)*scaleY).toFloat())
                return Path().apply{
                    if(p.geometryVersion==4){
                        r.inset((4*p.pixelsPerMm*scaleX).toFloat(),(4*p.pixelsPerMm*scaleY).toFloat())
                        val radius=(if(p.stock=="kodak_trix")40 else if(p.stock=="kodak_2302")12 else 4)*p.pixelsPerMm
                        addRoundRect(r,(radius*scaleX).toFloat(),(radius*scaleY).toFloat(),Path.Direction.CW)
                    }else addRect(r,Path.Direction.CW)
                }
            }
            val available=interior(sourceWindow,1f,1f).apply{transform(delta)}
            val visible=interior(aperture,target.width()/p.outputWidth,target.height()/p.outputHeight)
            val save=canvas.save();canvas.clipRect(target);canvas.clipPath(visible);canvas.clipPath(available)
            canvas.drawBitmap(source,delta,paint);canvas.restoreToCount(save)
        }
    }
    private val cropLine=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f*resources.displayMetrics.density}
    private val cropShade=Paint().apply{color=0x66000000}
    private fun drawAlignmentGrid(canvas:Canvas){
        val r=compositionAperture() ?: return
        val save=canvas.save();canvas.clipRect(r);cropLine.alpha=130;cropLine.strokeWidth=resources.displayMetrics.density
        for(i in 1..3){val f=i/4f;canvas.drawLine(r.left+r.width()*f,r.top,r.left+r.width()*f,r.bottom,cropLine);canvas.drawLine(r.left,r.top+r.height()*f,r.right,r.top+r.height()*f,cropLine)}
        canvas.restoreToCount(save);cropLine.alpha=255;cropLine.strokeWidth=2f*resources.displayMetrics.density
    }
    private fun drawCropHandles(canvas:Canvas){
        val r=compositionAperture() ?: return;val t=viewportRect()
        canvas.drawRect(t.left,t.top,t.right,r.top,cropShade);canvas.drawRect(t.left,r.bottom,t.right,t.bottom,cropShade)
        canvas.drawRect(t.left,r.top,r.left,r.bottom,cropShade);canvas.drawRect(r.right,r.top,t.right,r.bottom,cropShade)
        canvas.drawRect(r,cropLine)
        val radius=5f*resources.displayMetrics.density
        canvas.drawCircle(r.left,r.top,radius,cropLine);canvas.drawCircle(r.right,r.top,radius,cropLine)
        canvas.drawCircle(r.right,r.bottom,radius,cropLine);canvas.drawCircle(r.left,r.bottom,radius,cropLine)
    }
    private fun hitCropHandle(x:Float,y:Float):Int {
        if(!freeEdit)return 0
        val r=compositionAperture() ?: return 0;val d=24f*resources.displayMetrics.density
        fun near(a:Float,b:Float)=kotlin.math.abs(a-b)<=d
        return when{
            near(x,r.left)&&near(y,r.top)->1;near(x,r.right)&&near(y,r.top)->2
            near(x,r.right)&&near(y,r.bottom)->3;near(x,r.left)&&near(y,r.bottom)->4
            near(x,r.left)&&y in r.top..r.bottom->5;near(y,r.top)&&x in r.left..r.right->6
            near(x,r.right)&&y in r.top..r.bottom->7;near(y,r.bottom)&&x in r.left..r.right->8
            else->0
        }
    }
    private fun resizeCrop(x:Float,y:Float){
        if(!compositionGeometryReady)return
        val r=composition.rect(canonicalWidth,canonicalHeight)
        val nx=((x-viewport.left)/viewport.imageWidth).coerceIn(0.0,1.0)
        val ny=((y-viewport.top)/viewport.imageHeight).coerceIn(0.0,1.0)
        var l=r.left;var top=r.top;var right=r.right;var bottom=r.bottom
        val minSize=1.0/16
        if(handle==1 || handle==4 || handle==5)l=nx.coerceAtMost(right-minSize).coerceAtLeast(0.0)
        if(handle==2 || handle==3 || handle==7)right=nx.coerceAtLeast(l+minSize).coerceAtMost(1.0)
        if(handle==1 || handle==2 || handle==6)top=ny.coerceAtMost(bottom-minSize).coerceAtLeast(0.0)
        if(handle==3 || handle==4 || handle==8)bottom=ny.coerceAtLeast(top+minSize).coerceAtMost(1.0)
        val rw=right-l;val rh=bottom-top
        if(rw<=0.0 || rh<=0.0)return
        val aspect=rw*canonicalWidth/(rh*canonicalHeight)
        val zoom=minOf(1.0,aspect/(canonicalWidth.toDouble()/canonicalHeight))/rw/CompositionTransform(targetAspect=aspect,straightenDegrees=composition.straightenDegrees).rotationCover(canonicalWidth,canonicalHeight)
        // Extreme source aspects can make a handle request impossible within the serialized contract.
        // Reject that MOVE, preserving the last valid crop rather than crashing or stretching it.
        if(!aspect.isFinite() || aspect !in (1.0/30000.0)..30000.0 || !zoom.isFinite() || zoom<1.0-1e-9 || zoom>16.0+1e-9)return
        editComposition(CropSettings.fromRect(CompositionTransform.Rect(l,top,rw,rh),canonicalWidth,canonicalHeight,straightenDegrees=composition.straightenDegrees).transform)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN){moving=true;detail?.demand(true)}
        scale.onTouchEvent(e);taps.onTouchEvent(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{handle=hitCropHandle(e.x,e.y);lastX=e.x;lastY=e.y;parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_POINTER_DOWN->{handle=0}
            MotionEvent.ACTION_POINTER_UP->{val index=if(e.actionIndex==0)1 else 0;if(index<e.pointerCount){lastX=e.getX(index);lastY=e.getY(index)}}
            MotionEvent.ACTION_MOVE->{if(!scale.isInProgress&&e.pointerCount==1){if(handle!=0)resizeCrop(e.x,e.y)else pan((e.x-lastX).toDouble(),(e.y-lastY).toDouble())};lastX=e.x;lastY=e.y}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{finishCompositionGesture();handle=0;moving=false;changed();parent?.requestDisallowInterceptTouchEvent(false);if(e.actionMasked==MotionEvent.ACTION_UP)performClick()}
        };return true
    }
    override fun onDetachedFromWindow(){onNextPreviewDraw=null;finishCompositionGesture();detail?.foreground(false);super.onDetachedFromWindow()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(!disposed)detail?.foreground(foreground)}
    override fun close(){if(disposed)return;finishCompositionGesture();disposed=true;bindImprint(null);compositionChanged=null;compositionSource=null;interactiveFrame=null;liveComposition=false;detail?.close();detail=null;bitmap=null}
    override fun performClick():Boolean {super.performClick();return true}
}
