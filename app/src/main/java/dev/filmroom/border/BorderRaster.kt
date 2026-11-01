package dev.filmroom.border

import dev.filmroom.io.OutputColorSpace
import dev.filmroom.io.PhotoException
import kotlin.math.pow

/** A bounded, authored edge atlas in unassociated sRGB. Not a photograph-sized RGBA buffer.
 * Coordinates are physical atlas pixels; the sampler must tile only neutral/perforation runs,
 * anchor text once, and never stretch text/holes to fit a new aspect. Stock and 25/25A are baked in.
 * Implementations must be immutable and thread-safe. No network or user supplied paths.
 */
interface BorderRaster {
    val stock: String
    val version: String
    val railRatio: Double
    val endRatio: Double
    val residentBytes: Long
    val featherTotalAt3072: Double
    val supportsFixed135:Boolean get()=true
    /** Fully opaque border RGB in sRGB encoding, in [0,1]. Only called near/outside aperture. */
    fun validate(snapshot:BorderSnapshot) {
        require(residentBytes in 1..BorderCompositor.MAX_ATLAS_BYTES)
        val expected=if(snapshot.geometryVersion==1)BorderSnapshot.create(stock,version,snapshot.photoWidth,snapshot.photoHeight,railRatio,endRatio,featherTotalAt3072)
            else {require(supportsFixed135);BorderSnapshot.fixed135(stock,version,snapshot.sourceWidth,snapshot.sourceHeight,snapshot.canonicalWidth,snapshot.canonicalHeight,snapshot.composition,featherTotalAt3072)}
        require(snapshot==expected.copy(appearance=snapshot.appearance)) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5c82a6b4a003) }
    }
    fun sample(snapshot: BorderSnapshot, x: Double, y: Double, rgb: FloatArray)
}

/** Snapshot metadata is cheap; physical atlases resolve lazily on a worker thread. */
interface BorderProvider {
    fun snapshot(stock:String,width:Int,height:Int):BorderSnapshot
    fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform):BorderSnapshot {
        val legacy=snapshot(stock,width,height)
        return BorderSnapshot.fixed135(stock,legacy.assetVersion,width,height,canonicalWidth,canonicalHeight,composition,legacy.featherTotalAt3072).also{verifySnapshot(it)}
    }
    /** Presentation preference; providers without selectable mattes retain their authored fixture. */
    fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform,negativeWhiteHoles:Boolean):BorderSnapshot =
        snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition)
    /** Route-aware capture. Legacy fixture providers deliberately retain their authored frame. */
    fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform,negativeWhiteHoles:Boolean,outputRoute:String):BorderSnapshot =
        snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition,negativeWhiteHoles)
    /** Metadata-only preflight; must never decode or retain an atlas. */
    fun verifySnapshot(snapshot:BorderSnapshot)=Unit
    fun resolve(snapshot:BorderSnapshot):BorderRaster
    fun resolve(snapshot:BorderSnapshot,checkActive:()->Unit):BorderRaster {checkActive();return resolve(snapshot).also{checkActive()}}
}

/** Enabled ONLY in this isolated integration candidate after clean runtime/visual approval.
 * Production checkout is untouched; the user's EditorState.filmBorder still defaults OFF.
 */
object BorderCatalog:BorderProvider {
    const val PROTOTYPE_ENABLED = true
    private data class Source(val assets:android.content.res.AssetManager,val memory:()->dev.filmroom.engine.NativeMemorySnapshot)
    @Volatile private var source:Source?=null
    /** Installs only a trusted stream source; no decode, hashing or disk I/O on Main. */
    fun initialize(assets:android.content.res.AssetManager,memory:()->dev.filmroom.engine.NativeMemorySnapshot={dev.filmroom.engine.NativeWorkReservations.unavailableMemory}){
        source=Source(assets,memory)
    }
    private val registry by lazy {
        LazyBorderRegistry(CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+DirectBorderDefinitions.definitions,CandidateBorderDefinitions.profiles){definition,checkActive->
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"Border atlas decoding must run off Main"}
            val input=checkNotNull(source){"Border asset source not initialized"}
            BorderMemoryAdmission.withBudget(BorderMemoryAdmission.DECODE_ESTIMATE_BYTES,input.memory){
                checkActive()
                StraightAlphaBorderLoader.load(definition.spec,{input.assets.open(definition.assetPath)},checkActive)
            }
        }
    }
    fun available(stock:String)=PROTOTYPE_ENABLED && registry.available(stock)
    fun clearCache(){registry.clearCache()}
    override fun verifySnapshot(snapshot:BorderSnapshot){enabled();registry.verifySnapshot(snapshot)}
    private fun enabled(){if(!PROTOTYPE_ENABLED)throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1384551c8e44))}
    override fun snapshot(stock:String,width:Int,height:Int):BorderSnapshot {
        enabled();return registry.snapshot(stock,width,height)
    }
    override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform):BorderSnapshot {
        enabled();return registry.snapshot(stock,width,height,canonicalWidth,canonicalHeight,composition)
    }
    override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform,negativeWhiteHoles:Boolean):BorderSnapshot {
        enabled()
        val spec=CandidateBorderDefinitions.selectedDefinition(stock,negativeWhiteHoles).spec
        return BorderSnapshot.fixed135(stock,spec.version,width,height,canonicalWidth,canonicalHeight,composition,spec.featherTotalAt3072).also{registry.verifySnapshot(it)}
    }
    override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:dev.filmroom.model.CompositionTransform,negativeWhiteHoles:Boolean,outputRoute:String):BorderSnapshot {
        enabled()
        val context=FrameAppearanceContext.forProfile(stock,outputRoute,negativeWhiteHoles)
        val definition=if(context.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)DirectBorderDefinitions.selectedDefinition(stock,context)
            else CandidateBorderDefinitions.selectedDefinition(stock,negativeWhiteHoles)
        val spec=definition.spec
        return BorderSnapshot.fixed135(stock,spec.version,width,height,canonicalWidth,canonicalHeight,composition,spec.featherTotalAt3072)
            .copy(appearance=context).also{registry.verifySnapshot(it)}
    }
    override fun resolve(snapshot:BorderSnapshot)=resolve(snapshot,{})
    override fun resolve(snapshot:BorderSnapshot,checkActive:()->Unit):BorderRaster {
        enabled();return registry.resolve(snapshot,checkActive)
    }
}

internal object BorderColor {
    private fun decode(v: Float): Double {
        require(v.isFinite() && v in 0f..1f)
        return if(v<=0.04045f)v/12.92 else ((v+0.055)/1.055).pow(2.4)
    }
    /** D65 linear sRGB -> D65 output primaries, normalized primary matrices (neutral white preserved).
     * Photo values already use output primaries. No chromatic adaptation is needed between D65 spaces. */
    fun convert(rgb: FloatArray, color: OutputColorSpace) {
        val r=decode(rgb[0]); val g=decode(rgb[1]); val b=decode(rgb[2])
        when(color) {
            OutputColorSpace.SRGB -> {rgb[0]=r.toFloat();rgb[1]=g.toFloat();rgb[2]=b.toFloat()}
            OutputColorSpace.DISPLAY_P3 -> {
                rgb[0]=(.8224619687*r+.1775380313*g).toFloat();rgb[1]=(.0331941989*r+.9668058011*g).toFloat()
                rgb[2]=(.0170826307*r+.0723974407*g+.9105199286*b).toFloat()
            }
            OutputColorSpace.ADOBE_RGB -> {
                rgb[0]=(.71512561*r+.28487439*g).toFloat();rgb[1]=g.toFloat();rgb[2]=(.04116195*g+.95883805*b).toFloat()
            }
        }
    }
}
