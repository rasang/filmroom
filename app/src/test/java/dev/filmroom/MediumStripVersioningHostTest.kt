package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumStripVersioningHostTest {
    private fun route(stock:String)=if(stock in setOf("kodak_ektachrome_100","fujifilm_provia_100f","fujifilm_velvia_100"))"direct" else "print"
    private fun frozen(d:MediumStripDefinitions.Definition,center:Double=d.defaultCenterMm,stock:String=d.stock)=
        BorderSnapshot.medium120(stock,d.version,192,128,192,128,CompositionTransform(),MediumFormat.MF617,false,center)
            .copy(appearance=FrameAppearanceContext.forProfile(stock,route(stock),true))
    private fun rejected(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}}

    @Test fun registryKeepsExactFrozenIdentityAndUsesLastRevisionForNewSnapshots(){
        val old=MediumStripDefinitions.legacyDefinitions.first()
        val next=old.copy(path="medium120/v2/fixture.png",version="fixture-v2",defaultCenterMm=390.0,coverageStartMm=290.0,coverageEndMm=500.0)
        val other=old.copy(stock="another-stock",version=old.version)
        val registry=MediumStripDefinitions.Registry(listOf(old,next,other))
        assertEquals(next,registry.latest(old.stock))
        assertEquals(old,registry.exact(old.stock,old.version))
        assertEquals(next,registry.exact(next.stock,next.version))
        assertEquals(other,registry.exact(other.stock,other.version))
        rejected{registry.exact(other.stock,next.version)}
        rejected{registry.exact(old.stock,"missing")}
        rejected{MediumStripDefinitions.Registry(listOf(old,old))}
        next.verifyCoverage(frozen(next))
        next.verifyCoverage(frozen(next,377.0)) // Exactly touches the lower documented limit.
        next.verifyCoverage(frozen(next,413.0)) // Exactly touches the upper documented limit.
        rejected{next.verifyCoverage(frozen(next,376.99))}
        rejected{next.verifyCoverage(frozen(next,413.01))}
        old.verifyCoverage(frozen(old,450.0)) // Existing frozen coordinates remain valid.
    }

    @Test fun everyLegacySnapshotRetainsOriginalPathHashAndShiftedCenter(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try {
            worker.submit<Unit>{
                for(old in MediumStripDefinitions.legacyDefinitions){
                    val s=BorderSnapshot.fromJson(frozen(old,450.0).json())
                    MediumStripProvider.verify(s)
                    assertEquals(450.0,s.stripCenterMm!!,0.0)
                    val paths=mutableListOf<String>()
                    MediumStripProvider.clearCache()
                    val raster=MediumStripProvider.resolve(s,{path->paths+=path;app.assets.open(path)},{})
                    assertEquals(listOf(old.path),paths)
                    assertEquals(old.version,raster.version)
                    raster.validate(s) // Decode checks original encoded size, SHA-256, dimensions and opacity.
                }
            }.get()
        }finally{worker.shutdown();MediumStripProvider.clearCache()}
    }

    @Test fun currentCaptureUsesDefaultWhileAlternatingFrozenVersionsCannotHitWrongCache(){
        var revisedStocks=0
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try {
            for(old in MediumStripDefinitions.legacyDefinitions){
                val current=MediumStripDefinitions.registry.latest(old.stock)
                val fresh=MediumStripProvider.snapshot(old.stock,192,128,192,128,CompositionTransform(),true,route(old.stock),MediumFormat.MF617,false)
                assertEquals(current.version,fresh.assetVersion)
                assertEquals(current.defaultCenterMm,fresh.stripCenterMm!!,0.0)
                for(format in MediumFormat.entries)for(portrait in listOf(false,true)){
                    val capture=MediumStripProvider.snapshot(old.stock,192,128,192,128,CompositionTransform(),true,route(old.stock),format,portrait)
                    assertEquals(current.version,capture.assetVersion)
                    assertEquals(current.defaultCenterMm,capture.stripCenterMm!!,0.0)
                    val restored=BorderSnapshot.fromJson(capture.json())
                    assertEquals(capture,restored)
                    MediumStripProvider.verify(restored)
                }
                if(current.version==old.version)continue
                revisedStocks++
                worker.submit<Unit>{
                    MediumStripProvider.clearCache()
                    val paths=mutableListOf<String>()
                    fun resolve(s:BorderSnapshot)=MediumStripProvider.resolve(s,{p->paths+=p;app.assets.open(p)},{})
                    val original=frozen(old)
                    assertEquals(old.version,resolve(original).version)
                    assertEquals(current.version,resolve(fresh).version)
                    assertEquals(current.version,resolve(fresh).version) // Same version is cached.
                    assertEquals(old.version,resolve(original).version)
                    assertEquals(listOf(old.path,current.path,old.path),paths)
                    rejected{MediumStripProvider.verify(original.copy(assetVersion="unknown-version"))}
                    rejected{MediumStripProvider.verify(fresh.copy(stripCenterMm=current.coverageEndMm))}
                }.get()
            }
            assertTrue("The upgrade regression must exercise at least one revised stock",revisedStocks>0)
        }finally{worker.shutdown();MediumStripProvider.clearCache()}
    }

    @Test fun everyReviewedIntermediateSnapshotRemainsResolvableAfterLaterRevisions(){
        // Pin every reviewed candidate identity, including intermediate revisions that may no
        // longer be selected for a new photo. Queue snapshots must not silently migrate.
        val revisions=listOf(
            "kodak_ektachrome_100" to "mf120-v2-e100-221b4b71c6e5",
            "fujifilm_pro_400h" to "mf120-v2-pro400h-2d036c5cba8c",
            "fujifilm_velvia_100" to "mf120-v2-velvia100-9836225fb731",
            "fujifilm_provia_100f" to "mf120-v2-provia100f-a897d5f2ea77",
            "kodak_portra_400" to "mf120-v2-portra400-a096b757d518",
            "kodak_gold_200" to "mf120-v2-gold200-12416fd08dba",
            "kodak_ektar_100" to "mf120-v2-ektar100-98df0ed38e94",
            "kodak_portra_400" to "mf120-v4-portra400-e4cbfdada2fd",
            "kodak_portra_800" to "mf120-v4-portra800-d54e235ec886",
            "kodak_gold_200" to "mf120-v4-gold200-6fad2b869ee0",
            "kodak_ektar_100" to "mf120-v4-ektar100-91d648b6bb15",
            "kodak_ektachrome_100" to "mf120-v4-e100-12d17d86a868",
            "kodak_portra_160" to "mf120-v5-portra160-a970a81fe0dc",
            "kodak_portra_400" to "mf120-v5-portra400-b6bc018bda16",
            "kodak_portra_800" to "mf120-v5-portra800-64b689c66093",
        )
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try{worker.submit<Unit>{
            for((stock,version) in revisions){
                val revision=MediumStripDefinitions.registry.exact(stock,version)
                assertTrue(revision.path.startsWith("medium120/${version.split('-')[1]}/"))
                assertTrue(version.endsWith(revision.sha256.take(12)))
                val current=MediumStripDefinitions.registry.latest(stock)
                val restored=BorderSnapshot.fromJson(frozen(revision).json())
                assertEquals(version,restored.assetVersion)
                MediumStripProvider.verify(restored)
                MediumStripProvider.clearCache()
                val paths=mutableListOf<String>()
                fun resolve(s:BorderSnapshot)=MediumStripProvider.resolve(s,{p->paths+=p;app.assets.open(p)},{})
                val held=resolve(restored)
                held.validate(restored)
                resolve(frozen(current)).validate(frozen(current))
                assertEquals(version,resolve(restored).version)
                assertEquals(version,resolve(restored).version) // Exact same version hits cache.
                held.validate(restored) // Old held raster remains valid after cache replacement.
                assertEquals(if(current.version==version)listOf(revision.path)
                    else listOf(revision.path,current.path,revision.path),paths)
            }
        }.get()}finally{worker.shutdown();MediumStripProvider.clearCache()}
    }

    @Test fun aliasesKeepFrozenVersionAndShareOnlyTheirPhysicalRevision(){
        val old=MediumStripDefinitions.legacyDefinitions.single{it.stock=="kodak_portra_800"}
        val revised=MediumStripDefinitions.registry.latest("kodak_gold_200")
        val latest800=MediumStripDefinitions.registry.latest(old.stock)
        for(stock in listOf(old.stock,"kodak_portra_800_push1","kodak_portra_800_push2")){
            val fresh=MediumStripProvider.snapshot(stock,192,128,192,128,CompositionTransform(),true,"print",MediumFormat.MF617,false)
            assertEquals(latest800.version,fresh.assetVersion)
            assertEquals(latest800.defaultCenterMm,fresh.stripCenterMm!!,0.0)
            MediumStripProvider.verify(BorderSnapshot.fromJson(fresh.json()))
        }
        val app=ApplicationProvider.getApplicationContext<Application>()
        val worker=Executors.newSingleThreadExecutor()
        try{worker.submit<Unit>{
            MediumStripProvider.clearCache()
            val paths=mutableListOf<String>()
            fun resolve(s:BorderSnapshot)=MediumStripProvider.resolve(s,{p->paths+=p;app.assets.open(p)},{})
            val alias1=BorderSnapshot.fromJson(frozen(old,450.0,"kodak_portra_800_push1").json())
            val alias2=BorderSnapshot.fromJson(frozen(old,450.0,"kodak_portra_800_push2").json())
            val first=resolve(alias1)
            first.validate(alias1)
            assertEquals(alias1.stock,first.stock)
            assertEquals(old.version,first.version)
            resolve(frozen(old,450.0)).validate(frozen(old,450.0))
            resolve(alias2).validate(alias2)
            assertEquals(listOf(old.path),paths)
            assertEquals(revised.version,resolve(frozen(revised)).version)
            resolve(alias1).validate(alias1)
            assertEquals(listOf(old.path,revised.path,old.path),paths)
            first.validate(alias1) // Held aliases survive eviction without changing version.
            rejected{MediumStripProvider.verify(alias1.copy(assetVersion=revised.version))}
            MediumStripProvider.clearCache();paths.clear()
            val currentAliases=listOf(old.stock,"kodak_portra_800_push1","kodak_portra_800_push2").map{stock->
                MediumStripProvider.snapshot(stock,192,128,192,128,CompositionTransform(),true,"print",MediumFormat.MF617,false)
            }
            for(capture in currentAliases){
                val raster=resolve(capture)
                assertEquals(capture.stock,raster.stock)
                assertEquals(latest800.version,raster.version)
                raster.validate(capture)
            }
            assertEquals("Current physical strip and both processing aliases must decode only once",listOf(latest800.path),paths)
            if(latest800.version!=old.version){
                resolve(alias1).validate(alias1)
                resolve(currentAliases.last()).validate(currentAliases.last())
                assertEquals(listOf(latest800.path,old.path,latest800.path),paths)
            }
        }.get()}finally{worker.shutdown();MediumStripProvider.clearCache()}
    }

    @Test fun revisedRailsPreviewAndFloatExportMatchForAllFormatsAndOrientations(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        val photo=android.graphics.Bitmap.createBitmap(38,27,android.graphics.Bitmap.Config.ARGB_8888)
        photo.eraseColor(0xff808080.toInt())
        val dir=java.nio.file.Files.createTempDirectory("medium120-versioned-parity").toFile()
        val worker=Executors.newSingleThreadExecutor()
        try{
            val bytes=java.nio.ByteBuffer.allocate(38*27*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(38*27*3){bytes.putFloat(dev.filmroom.io.LinearRgb.srgb8ToLinear[128])}
            val input=java.io.File(dir,"input.rgb").apply{writeBytes(bytes.array())}
            worker.submit<Unit>{
                for(old in MediumStripDefinitions.legacyDefinitions){
                    val current=MediumStripDefinitions.registry.latest(old.stock)
                    if(current.version==old.version)continue
                    for(format in MediumFormat.entries)for(portrait in listOf(false,true)){
                        val s=MediumStripProvider.snapshot(old.stock,38,27,38,27,CompositionTransform(),true,route(old.stock),format,portrait)
                        val raster=MediumStripProvider.resolve(s,{app.assets.open(it)},{})
                        val preview=BorderPreview.compose(photo,s,raster)
                        try{
                            val output=java.io.File(dir,"output.rgb")
                            BorderCompositor.composite(input,output,s,raster,dev.filmroom.io.OutputColorSpace.SRGB)
                            val rgb=java.nio.ByteBuffer.wrap(output.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                            for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                                val r=dev.filmroom.io.OutputColorSpace.SRGB.encode8(rgb.get())
                                val g=dev.filmroom.io.OutputColorSpace.SRGB.encode8(rgb.get())
                                val b=dev.filmroom.io.OutputColorSpace.SRGB.encode8(rgb.get())
                                assertEquals("${old.stock} $format portrait=$portrait at $x,$y",(0xff shl 24)or(r shl 16)or(g shl 8)or b,preview.getPixel(x,y))
                            }
                        }finally{preview.recycle()}
                    }
                }
            }.get()
        }finally{worker.shutdown();photo.recycle();dir.deleteRecursively();MediumStripProvider.clearCache()}
    }

}
