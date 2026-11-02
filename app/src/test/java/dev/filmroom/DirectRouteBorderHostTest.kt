package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectRouteBorderHostTest {
    private fun initialize():Application=ApplicationProvider.getApplicationContext<Application>().also {app->
        BorderCatalog.initialize(app.assets){NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
    }
    @Test fun allTwentyProfilesSelectRouteAndMatteWithoutChangingPrintContracts() {
        initialize();assertEquals(28,DirectBorderDefinitions.definitions.size)
        assertEquals(14,DirectBorderDefinitions.definitions.map{it.spec.stock}.distinct().size)
        for((profile,kind) in FrameProfileCatalog.kinds.filterKeys{it in CandidateBorderDefinitions.profiles})for(white in listOf(false,true))for((w,h)in listOf(144 to 96,96 to 144)) {
            val route=if(kind==FrameAppearanceContext.FilmKind.REVERSAL)"direct"else "print"
            val positive=BorderCatalog.snapshot(profile,w,h,w,h,CompositionTransform(),white,route)
            assertEquals(CandidateBorderDefinitions.selectedDefinition(profile,white).spec.version,positive.assetVersion)
            assertEquals(FrameAppearanceContext.Material.POSITIVE_DISPLAY,positive.appearance!!.material)
            val direct=BorderCatalog.snapshot(profile,w,h,w,h,CompositionTransform(),white,"direct")
            BorderCatalog.verifySnapshot(direct);assertEquals(direct,BorderSnapshot.fromJson(direct.json()))
            assertEquals(if(kind==FrameAppearanceContext.FilmKind.REVERSAL || white)0xffffff else 0,direct.appearance!!.holeMatteSrgb)
            if(kind==FrameAppearanceContext.FilmKind.NEGATIVE){
                assertNotEquals(positive.assetVersion,direct.assetVersion)
                assertNotEquals(InteractiveBorderPreview.key(positive),InteractiveBorderPreview.key(direct))
                assertEquals(FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION,direct.appearance!!.material)
            } else assertEquals(positive,direct)
        }
        for(white in listOf(false,true)){
            val versions=listOf("kodak_portra_800","kodak_portra_800_push1","kodak_portra_800_push2").map{BorderCatalog.snapshot(it,144,96,144,96,CompositionTransform(),white,"direct").assetVersion}
            assertEquals(1,versions.distinct().size)
        }
    }
    @Test fun allNewAndHistoricalJobsRemainVersionPinnedAfterRestartAndRetry() {
        initialize();val root=Files.createTempDirectory("direct-route-queue").toFile()
        try {
            val store=ExportJobStore(root)
            val snapshots=DirectBorderDefinitions.definitions.map{d->
                BorderCatalog.snapshot(d.spec.stock,144,96,144,96,CompositionTransform(),d.spec.holeMatteSrgb!=0,"direct")
            }+(CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions).map{d->BorderSnapshot.fixed135(d.spec.stock,d.spec.version,144,96)}
            assertEquals(73,snapshots.size)
            for((index,s)in snapshots.withIndex()){
                val p=EditorState(filmStock=s.stock,filmBorder=true,outputRoute="direct",negativeWhiteHoles=s.appearance?.negativeWhiteHoles ?: true)
                store.update(ExportJob(ExportJobStore.newId(),index.toLong()+1,p,144,96,ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=s))
            }
            val reopened=ExportJobStore(root);assertEquals(73,reopened.jobs.value.size)
            reopened.jobs.value.forEachIndexed{i,job->
                assertEquals(snapshots[i],job.border);BorderCatalog.verifySnapshot(job.border!!)
                reopened.update(job.copy(state=ExportJobState.RETRY))
            }
            val again=ExportJobStore(root)
            again.jobs.value.forEachIndexed{i,job->assertEquals(snapshots[i],job.border)}
            assertEquals(45,again.jobs.value.count{it.border!!.appearance==null})
        } finally {root.deleteRecursively()}
    }
    @Test fun allRuntimePayloadsArePinnedAndOldFortyFiveRecordsRemainPresent() {
        val app=initialize();val json=JSONObject(app.assets.open("fixed135/runtime-manifest.json").bufferedReader().use{it.readText()})
        assertEquals(18,json.getJSONArray("assets").length());assertEquals(27,json.getJSONArray("legacy_assets").length());assertEquals(28,json.getJSONArray("direct_assets").length())
        val definitions=CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions+DirectBorderDefinitions.definitions
        assertEquals(73,definitions.size);assertEquals(38,definitions.map{it.assetPath}.distinct().size)
        for(d in definitions.distinctBy{it.assetPath}){
            val bytes=app.assets.open(d.assetPath).use{it.readBytes()}
            assertEquals(d.spec.encodedBytes,bytes.size)
            assertEquals(d.spec.sha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        }
    }
    @Test fun optInEveryDirectMatteMatchesFastAndSettledBothOrientations()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        withContext(Dispatchers.IO) {
            initialize()
            var decodeIndex=0
            for(d in DirectBorderDefinitions.definitions)for((w,h)in listOf(144 to 96,96 to 144)){
                val s=BorderCatalog.snapshot(d.spec.stock,w,h,w,h,CompositionTransform(),d.spec.holeMatteSrgb!=0,"direct")
                decodeIndex++
                if(System.getenv("FILMROOM_ATLAS_HEAP_TRACE")=="1"){
                    val rt=Runtime.getRuntime();val used=rt.totalMemory()-rt.freeMemory()
                    println("ATLAS_HEAP index=$decodeIndex stock=${d.spec.stock} version=${d.spec.version} width=$w height=$h max=${rt.maxMemory()} used=$used headroom=${rt.maxMemory()-used}")
                }
                val raster=BorderCatalog.resolve(s)
                val photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff557799.toInt())}
                val fast=InteractiveBorderPreview.create(s,raster);val settled=BorderPreview.compose(photo,s,raster)
                try{
                    val expected=if(d.spec.holeMatteSrgb==0)Color.BLACK else Color.WHITE
                    for(cx in listOf(2.375,7.125,11.875,16.625,21.375,26.125,30.875,35.625))for(cy in listOf(3.407,31.593)){
                        val x=(s.frameOriginX+(if(s.portrait)35-cy else cx)*s.pixelsPerMm).toInt()
                        val y=(s.frameOriginY+(if(s.portrait)cx else cy)*s.pixelsPerMm).toInt()
                        assertEquals(expected,fast.getPixel(x,y));assertEquals(expected,settled.getPixel(x,y))
                    }
                    assertEquals(0,fast.getPixel(fast.width/2,fast.height/2))
                    assertEquals(photo.getPixel(w/2,h/2),settled.getPixel(settled.width/2,settled.height/2))
                }finally{photo.recycle();fast.recycle();settled.recycle()}
                BorderCatalog.clearCache()
            }
            System.getenv("FILMROOM_BORDER_QA_DIR")?.let{RuntimeBorderVisualQa.render(java.io.File(it))}
            Unit
        }
    }
}
