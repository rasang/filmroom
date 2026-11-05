package dev.filmroom.engine

import android.content.Context
import org.json.JSONObject

/** Immutable execution choice shared by preview/detail. No image or RNG settings change. */
internal data class ScientificGpuSelection(
    val camera:Boolean=false,val scanner:Boolean=false,val optical:Boolean=false,
    val exposure:Boolean=false,val dir:Boolean=false,val science:Boolean=false
) {
    val enabled get()=camera || scanner || optical || exposure || dir || science
    fun applyTo(parameters:JSONObject,perfBuild:Boolean=dev.filmroom.BuildConfig.PERF_BUILD):JSONObject=parameters.apply {
        applyFullGpuDiffusionPolicy(this,camera);applyGpuScannerPolicy(this,scanner)
        applyGpuOpticalPolicy(this,optical);applyGpuExposurePolicy(this,exposure)
        applyGpuDirPolicy(this,dir);applyGpuSciencePolicy(this,science)
        if(enabled)put("previewBrightnessReuse",false)
        if(perfBuild)put("useGpu",false).put("gpuResourceReuse",false)
    }
    fun router(context:Context,parentToken:Long?=null)=GpuExportRouter(context,camera,scanner,optical,exposure,dir,science,parentToken)
    companion object {
        fun from(snapshot:OptimizationSnapshot,perfBuild:Boolean,mode:String)=ScientificGpuSelection(
            snapshot.experimentalDiffusionEnabled(perfBuild,mode),snapshot.experimentalScannerEnabled(perfBuild,mode),
            snapshot.experimentalOpticalEnabled(perfBuild,mode),snapshot.experimentalExposureEnabled(perfBuild,mode),
            snapshot.experimentalDirEnabled(perfBuild,mode),snapshot.experimentalScienceEnabled(perfBuild,mode))
    }
}
/** Testable ownership boundary; production always carries a closed GpuExportRouter. */
internal class DetailGpuBuild(val delegate:Any,val drain:suspend ()->Boolean,val whenSafeToRelease:((()->Unit)->Unit)) {
    constructor(router:GpuExportRouter):this(router,{router.awaitSafeTermination()},{release->router.whenSafeToRelease(release)})
}
