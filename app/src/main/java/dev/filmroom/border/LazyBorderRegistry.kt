package dev.filmroom.border

import dev.filmroom.io.PhotoException
import dev.filmroom.model.CompositionTransform

/** Immutable trusted definitions, lazy single-physical-atlas cache. No network or user paths.
 * A returned raster owns its immutable pixels: eviction drops only the registry reference and
 * must never recycle an in-flight preview/export's atlas. Consequently concurrent borrowers can
 * retain older atlases; the cache itself retains at most one, not all 18 stock images.
 */
class LazyBorderRegistry(
    definitions:List<Definition>,
    currentProfiles:Map<String,Key>,
    private val load:(Definition,()->Unit)->BorderRaster,
):BorderProvider {
    data class Key(val physicalStock:String,val version:String)
    data class Definition(val spec:StraightAlphaBorderLoader.Spec,val assetPath:String,val material:FrameAppearanceContext.Material=FrameAppearanceContext.Material.POSITIVE_DISPLAY) {
        val key get()=Key(spec.stock,spec.version)
        init {require(assetPath.matches(Regex("fixed135/[a-zA-Z0-9_.-]+\\.(png|webp)")))}
    }
    private val definitions=definitions.associateBy{it.key}.also{require(it.size==definitions.size)}
    private val profiles=currentProfiles.toMap()
    private data class Cached(val key:Key,val raster:BorderRaster)
    private val loadLock=Any()
    private val cacheStateLock=Any()
    private var cached:Cached?=null
    private var cacheGeneration=0L
    init {
        require(this.definitions.isNotEmpty())
        // Snapshot persists assetVersion, so it must identify exactly one physical asset globally.
        // A future profile alias remap must fail, never reinterpret an old human label like v1.
        require(this.definitions.values.groupBy{it.spec.version}.values.all{group->group.size==1})
        require(profiles.isNotEmpty())
        profiles.forEach{(profile,key)->
            require(profile.matches(Regex("[a-z0-9_]{1,80}")))
            require(this.definitions.containsKey(key))
        }
    }
    fun available(stock:String)=profiles.containsKey(stock)
    internal val cachedAtlasCount get()=synchronized(cacheStateLock){if(cached==null)0 else 1}
    private fun unsupported():Nothing=throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_bec36dff07be))
    private fun definition(stock:String,version:String?=null):Definition {
        val key=profiles[stock] ?: unsupported()
        return definitions[if(version==null)key else key.copy(version=version)] ?: unsupported()
    }
    override fun snapshot(stock:String,width:Int,height:Int)=snapshot(stock,width,height,width,height,CompositionTransform())
    override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:CompositionTransform):BorderSnapshot {
        val spec=definition(stock).spec
        return BorderSnapshot.fixed135(stock,spec.version,width,height,canonicalWidth,canonicalHeight,composition,spec.featherTotalAt3072)
    }
    /** Main-thread safe: never waits for stream I/O/decode or the serialized load lock.
     * Incrementing the epoch prevents an already-running load from undoing this trim.
     * Borrowers (including the running load's caller) remain valid and immutable.
     */
    fun clearCache(){synchronized(cacheStateLock){cached=null;cacheGeneration++}}
    override fun verifySnapshot(snapshot:BorderSnapshot) {
        if(snapshot.geometryVersion !in 2..3)unsupported()
        val definition=definition(snapshot.stock,snapshot.assetVersion)
        val expected=BorderSnapshot.fixed135(snapshot.stock,definition.spec.version,snapshot.sourceWidth,snapshot.sourceHeight,
            snapshot.canonicalWidth,snapshot.canonicalHeight,snapshot.composition,definition.spec.featherTotalAt3072)
        if(definition.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)requireNotNull(snapshot.appearance) { "Missing negative frame appearance contract" }
        snapshot.appearance?.let {
            require(it.material==definition.material && it.holeMatteSrgb==definition.spec.holeMatteSrgb) { "Frame appearance does not match frozen asset contract" }
        }
        require(snapshot==expected.copy(appearance=snapshot.appearance)){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d1c5599a9bff)}
    }
    override fun resolve(snapshot:BorderSnapshot)=resolve(snapshot,{})
    override fun resolve(snapshot:BorderSnapshot,checkActive:()->Unit):BorderRaster {
        checkActive();verifySnapshot(snapshot)
        val definition=definition(snapshot.stock,snapshot.assetVersion)
        val physical=synchronized(cacheStateLock){cached?.takeIf{it.key==definition.key}?.raster}
            ?: synchronized(loadLock){
                checkActive()
                // Another worker may have filled the cache while this worker waited.
                val (hit,epoch)=synchronized(cacheStateLock){
                    val match=cached?.takeIf{it.key==definition.key}?.raster
                    if(match==null)cached=null // Keep at most one cached physical atlas.
                    match to cacheGeneration
                }
                hit ?: run {
                    val loaded=load(definition,checkActive)
                    checkActive()
                    require(loaded.stock==definition.spec.stock && loaded.version==definition.spec.version)
                    loaded.validate(snapshot.copy(stock=loaded.stock))
                    checkActive() // Cancellation must not publish a fully decoded atlas.
                    synchronized(cacheStateLock){
                        if(cacheGeneration==epoch)cached=Cached(definition.key,loaded)
                    }
                    // If a trim happened during decode, return the borrowed result only.
                    loaded
                }
            }
        checkActive()
        return if(physical.stock==snapshot.stock)physical else Alias(snapshot.stock,physical)
    }
    /** Profile alias changes identity only; no ARGB allocation or texture duplication. */
    private class Alias(override val stock:String,private val physical:BorderRaster):BorderRaster by physical {
        override fun validate(snapshot:BorderSnapshot){require(snapshot.stock==stock);physical.validate(snapshot.copy(stock=physical.stock))}
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray)=physical.sample(snapshot,x,y,rgb)
    }
}
