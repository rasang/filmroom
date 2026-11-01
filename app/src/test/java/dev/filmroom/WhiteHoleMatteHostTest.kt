package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.border.*
import dev.filmroom.export.*
import dev.filmroom.io.*
import dev.filmroom.codec.*
import dev.filmroom.engine.NativeMemorySnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/** White holes are an explicit presentation preference, not a new claim about negative scans. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhiteHoleMatteHostTest {
    private val current get()=CandidateBorderDefinitions.definitions
    private val all get()=current+CandidateBorderDefinitions.legacyDefinitions

    @Test fun allTwentyProfilesChoosePureWhiteAndAll28ShippedContractsStayImmutable(){
        assertEquals(18,current.size);assertEquals(20,CandidateBorderDefinitions.profiles.size)
        val byKey=all.associateBy{it.key};assertEquals(45,byKey.size)
        for(key in CandidateBorderDefinitions.profiles.values)assertEquals(0xffffff,byKey.getValue(key).spec.holeMatteSrgb)
        val actual=all.associateBy{it.spec.version}
        for(line in shippedContracts.trim().lines()){
            val p=line.split('|');val d=actual.getValue(p[1]);val s=d.spec
            assertEquals(p[0],s.stock);assertEquals(p[2],s.sha256);assertEquals(p[3].toInt(),s.encodedBytes)
            assertEquals(p[4],d.assetPath);assertEquals(p[5].toInt(),s.holeMatteSrgb)
            assertEquals(1520,s.width);assertEquals(1400,s.height);assertEquals(4.0,s.featherTotalAt3072,0.0)
            if(s.holeMatteSrgb==0){
                val n=current.single{it.spec.stock==s.stock}
                if(s.sha256!=n.spec.sha256){assertEquals("Only approved typography payload differs", "kodak_portra_800",s.stock);continue}
                assertEquals("Only version/matte change; no rail, letters or alpha pixel edits",s.copy(version=n.spec.version,holeMatteSrgb=0xffffff),n.spec)
                assertEquals(d.assetPath,n.assetPath)
                assertEquals("fixed135-white-w10-"+s.sha256.take(16),n.spec.version)
            }
        }
        assertEquals(28,shippedContracts.trim().lines().size)
        assertEquals(15,CandidateBorderDefinitions.legacyDefinitions.count{it.spec.holeMatteSrgb==0})
        assertEquals(12,CandidateBorderDefinitions.legacyDefinitions.count{it.spec.holeMatteSrgb==0xffffff})
    }

    @Test fun negativeTogglePersistsInHistoryButNotScienceAndReversalsAlwaysRemainWhite(){
        val app=RuntimeEnvironment.getApplication();BorderCatalog.initialize(app.assets)
        val reversals=setOf("kodak_ektachrome_100","kodak_kodachrome_64","fujifilm_provia_100f","fujifilm_velvia_100")
        val definitions=all.associateBy{it.spec.version}
        assertTrue(EditorState().negativeWhiteHoles)
        assertTrue(EditorState.fromJson("{}").negativeWhiteHoles)
        for(stock in CandidateBorderDefinitions.profiles.keys){
            val saved=EditorState(filmStock=stock,outputRoute=if(FrameProfileCatalog.kind(stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct" else "print",filmBorder=true,composition=dev.filmroom.model.CompositionTransform(.6,.4,1.4))
            for(white in listOf(true,false)){
                val chosen=saved.copy(negativeWhiteHoles=white)
                val reopened=EditorState.fromJson(chosen.json());assertEquals(chosen,reopened)
                assertFalse("Editor preferences do not pin old queue assets",chosen.json().contains("assetVersion"))
                assertEquals(saved.json(includePresentation=false),chosen.json(includePresentation=false))
                for(p in listOf(reopened,reopened.copy(filmBorder=false).copy(filmBorder=true),reopened.copy(composition=dev.filmroom.model.CompositionTransform(.4,.6,2.0)),EditorState().copy(filmStock=stock,filmBorder=true,negativeWhiteHoles=white))){
                    val snapshot=BorderCatalog.snapshot(p.filmStock,144,96,144,96,p.composition,p.negativeWhiteHoles)
                    val expected=if(white || stock in reversals)0xffffff else 0
                    assertEquals("$stock switch=$white",expected,definitions.getValue(snapshot.assetVersion).spec.holeMatteSrgb)
                    assertEquals(CandidateBorderDefinitions.selectedDefinition(stock,white).spec.version,snapshot.assetVersion)
                }
            }
        }
        val history=dev.filmroom.model.EditHistory();history.update(history.current.copy(negativeWhiteHoles=false))
        assertTrue(history.canUndo);history.undo();assertTrue(history.current.negativeWhiteHoles)
        history.redo();assertFalse(history.current.negativeWhiteHoles)
    }

    @Test fun currentAndHistoricalQueueSnapshotsRoundTripWithoutMatteRebinding(){
        val app=RuntimeEnvironment.getApplication()
        BorderCatalog.initialize(app.assets){NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val root=Files.createTempDirectory("white-hole-jobs").toFile()
        try{for(d in all)for((w,h)in listOf(144 to 96,96 to 144)){
            val s=BorderSnapshot.fixed135(d.spec.stock,d.spec.version,w,h)
            // Shipped black jobs predate the new preference and restore its default=true;
            // their frozen black asset must still win over that editor preference.
            val historical=shippedContracts.trim().lines().any{it.split('|')[1]==d.spec.version}
            val params=EditorState(filmBorder=true,filmStock=d.spec.stock,outputRoute=if(FrameProfileCatalog.kind(d.spec.stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct" else "print",negativeWhiteHoles=historical || d.spec.holeMatteSrgb!=0)
            val original=ExportJob(ExportJobStore.newId(),1,params,w,h,ExportFormat.TIFF16,95,border=s)
            val store=ExportJobStore(root);store.update(original)
            val missingPreference=historical && d.spec.stock=="kodak_ektar_100" && d.spec.holeMatteSrgb==0 && w==144
            if(missingPreference){
                val file=File(store.directory(original.id),"manifest.json")
                val manifest=JSONObject(file.readText());val oldParams=JSONObject(manifest.getString("parameters"))
                oldParams.remove("negativeWhiteHoles");manifest.put("parameters",oldParams.toString());file.writeText(manifest.toString())
                assertFalse(JSONObject(JSONObject(file.readText()).getString("parameters")).has("negativeWhiteHoles"))
            }
            val restored=ExportJobStore(root).get(original.id)!!
            assertEquals(s,restored.border);assertEquals(original.parameters,restored.parameters);assertEquals(d.spec.version,restored.border!!.assetVersion)
            BorderCatalog.verifySnapshot(restored.border!!)
            store.update(restored.copy(state=ExportJobState.RETRY))
            val retried=ExportJobStore(root).get(original.id)!!
            assertEquals(s,retried.border)
            if(missingPreference){
                assertTrue(restored.parameters.negativeWhiteHoles);assertTrue(retried.parameters.negativeWhiteHoles)
                runBlocking{withContext(Dispatchers.IO){
                    for(pinned in listOf(restored.border!!,retried.border!!)){
                        val raster=BorderCatalog.resolve(pinned);val rgb=FloatArray(3)
                        raster.sample(pinned,pinned.frameOriginX+2.375*pinned.pixelsPerMm,pinned.frameOriginY+3.407*pinned.pixelsPerMm,rgb)
                        assertArrayEquals("Missing preference defaults ON but old frozen job stays black",floatArrayOf(0f,0f,0f),rgb,0f)
                    }
                }}
            }
            if(d.spec.holeMatteSrgb==0)assertNotEquals(d.spec.version,BorderCatalog.snapshot(d.spec.stock,w,h).assetVersion)
        }}finally{root.deleteRecursively();BorderCatalog.clearCache()}
    }

    @Test fun legacyInvalidReversalRouteIsRepairedWithoutRebindingItsFrozenMatte(){
        val app=RuntimeEnvironment.getApplication();BorderCatalog.initialize(app.assets)
        val root=Files.createTempDirectory("legacy-reversal-route").toFile()
        try{
            val definition=all.first{it.spec.stock=="kodak_ektachrome_100"}
            val border=BorderSnapshot.fixed135(definition.spec.stock,definition.spec.version,144,96)
            val params=EditorState(filmStock=definition.spec.stock,outputRoute="direct",filmBorder=true)
            val job=ExportJob(ExportJobStore.newId(),1,params,144,96,ExportFormat.TIFF16,95,border=border)
            val store=ExportJobStore(root);store.update(job)
            val file=File(store.directory(job.id),"manifest.json");val manifest=JSONObject(file.readText())
            val old=JSONObject(manifest.getString("parameters")).apply{put("outputRoute","print");remove("preferredOutputRoute")}
            file.writeText(manifest.put("parameters",old.toString()).toString())
            val loaded=ExportJobStore(root).get(job.id)!!
            assertEquals("direct",loaded.parameters.outputRoute);assertEquals("print",loaded.parameters.preferredOutputRoute)
            assertEquals(border,loaded.border);assertEquals(definition.spec.version,loaded.border!!.assetVersion)
            assertNull("Legacy queue appearance is not rebound from repaired route",loaded.border!!.appearance)
            BorderCatalog.verifySnapshot(loaded.border!!)
            val preferred=params.selectOutputRoute("print")
            val currentBorder=border.copy(appearance=FrameAppearanceContext.forProfile(preferred.filmStock,preferred.outputRoute,preferred.negativeWhiteHoles))
            val current=job.copy(id=ExportJobStore.newId(),parameters=preferred,border=currentBorder)
            store.update(current)
            assertEquals(5,JSONObject(File(store.directory(current.id),"manifest.json").readText()).getInt("version"))
            val currentReloaded=ExportJobStore(root).get(current.id)!!
            assertEquals(preferred,currentReloaded.parameters);assertEquals("print",currentReloaded.parameters.preferredOutputRoute)
            assertEquals("direct",currentReloaded.parameters.outputRoute);assertEquals(currentBorder,currentReloaded.border)
            BorderCatalog.verifySnapshot(currentReloaded.border!!)
            assertEquals(definition.spec.holeMatteSrgb,all.single{it.spec.version==loaded.border!!.assetVersion}.spec.holeMatteSrgb)
        }finally{root.deleteRecursively();BorderCatalog.clearCache()}
    }

    @Test fun optInActualEveryStockAndHistoricalBlackHolesMatchFastAndSettledInBothOrientations(){
        assumeTrue("Actual atlas decode belongs in a fresh small rendering JVM",System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        val app=RuntimeEnvironment.getApplication()
        for(d in current+CandidateBorderDefinitions.legacyDefinitions.filter{it.spec.holeMatteSrgb==0}){
            val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            for((w,h)in listOf(144 to 96,96 to 144)){
                val s=BorderSnapshot.fixed135(d.spec.stock,d.spec.version,w,h)
                val photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff557799.toInt())}
                val fast=InteractiveBorderPreview.create(s,raster)
                val settled=BorderPreview.compose(photo,s,raster)
                try{
                    val expected=if(d.spec.holeMatteSrgb==0)Color.BLACK else Color.WHITE
                    for(cx in listOf(2.375,7.125,11.875,16.625,21.375,26.125,30.875,35.625))for(cy in listOf(3.407,31.593)){
                        val x=(s.frameOriginX+(if(s.portrait)35.0-cy else cx)*s.pixelsPerMm).toInt()
                        val y=(s.frameOriginY+(if(s.portrait)cx else cy)*s.pixelsPerMm).toInt()
                        assertEquals("Fast ${d.spec.stock}/${d.spec.version} portrait=${s.portrait} hole=$cx,$cy",expected,fast.getPixel(x,y))
                        assertEquals("Settled hole keeps identical matte",expected,settled.getPixel(x,y))
                    }
                    assertEquals("Aperture is still clear in fast overlay",0,fast.getPixel(fast.width/2,fast.height/2))
                    assertEquals("Photo itself is not recolored",photo.getPixel(w/2,h/2),settled.getPixel(settled.width/2,settled.height/2))
                }finally{photo.recycle();fast.recycle();settled.recycle()}
            }
        }
    }

    @Test fun optInRealCodecKeepsNewWhiteAndQueuedBlackInBothOrientations()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertTrue(NativeCodecBridge.available)
        val app=RuntimeEnvironment.getApplication()
        BorderCatalog.initialize(app.assets)
        val jobs=File(app.filesDir,"export-jobs").apply{mkdirs()}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val currentEktar=current.single{it.spec.stock=="kodak_ektar_100"}
        val oldBlack=CandidateBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_ektar_100" && it.spec.holeMatteSrgb==0}
        val codecs=PhotoCodecs(app.cacheDir,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)},exportJobsRoot=jobs)
        try{for(d in listOf(currentEktar,oldBlack)){
            val raster=StraightAlphaBorderLoader.load(d.spec,{app.assets.open(d.assetPath)})
            for((w,h)in listOf(384 to 256,256 to 384)){
                val s=BorderCatalog.snapshot(d.spec.stock,w,h,w,h,dev.filmroom.model.CompositionTransform(),d.spec.holeMatteSrgb!=0)
                assertEquals(d.spec.version,s.assetVersion)
                val input=File(job,"input.rgb")
                val source=ByteBuffer.allocate(w*h*12).order(ByteOrder.LITTLE_ENDIAN)
                repeat(w*h){source.putFloat(.12f);source.putFloat(.23f);source.putFloat(.34f)};input.writeBytes(source.array())
                for(space in OutputColorSpace.entries){
                    val framed=File(job,"film-result-${UUID.randomUUID()}.rgb")
                    BorderCompositor.composite(input,framed,s,raster,space)
                    for(format in listOf("JPEG","PNG","TIFF16")){
                        val encoded=File(job,"codec-output-${UUID.randomUUID()}.${if(format=="JPEG")"jpg" else if(format=="PNG")"png" else "tif"}")
                        if(format=="PNG")framed.inputStream().use{src->encoded.outputStream().use{out->LinearRgbPng.encode(src,s.outputWidth,s.outputHeight,out,colorSpace=space,iccProfile=if(space==OutputColorSpace.SRGB)null else app.assets.open("color-profiles/${space.iccAsset}").use{it.readBytes()})}}
                        else codecs.encode(framed,encoded,s.outputWidth,s.outputHeight,format,95,space.id)
                        val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,encoded)
                        assertNotNull(decoded);assertEquals(s.outputWidth,decoded.javaClass.getMethod("getWidth").invoke(decoded));assertEquals(s.outputHeight,decoded.javaClass.getMethod("getHeight").invoke(decoded))
                        val getRgb=decoded.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                        for(cx in listOf(2.375,16.625,35.625))for(cy in listOf(3.407,31.593)){
                            val x=(s.frameOriginX+(if(s.portrait)35.0-cy else cx)*s.pixelsPerMm).toInt()
                            val y=(s.frameOriginY+(if(s.portrait)cx else cy)*s.pixelsPerMm).toInt()
                            val pixel=getRgb.invoke(decoded,x,y) as Int
                            val expected=if(d.spec.holeMatteSrgb==0)0 else 255
                            for(shift in listOf(16,8,0))assertEquals("$format/$space ${d.spec.version} portrait=${s.portrait}",expected.toDouble(),((pixel ushr shift)and 255).toDouble(),if(format=="JPEG")3.0 else 0.0)
                            if(format=="TIFF16"){
                                val outRaster=decoded.javaClass.getMethod("getRaster").invoke(decoded)
                                val sample=outRaster.javaClass.getMethod("getSample",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                                for(c in 0..2)assertEquals(if(expected==0)0 else 65535,sample.invoke(outRaster,x,y,c))
                            }
                        }
                        encoded.delete()
                    };framed.delete()
                }
                assertArrayEquals("Scientific checkpoint stays unmodified",source.array(),input.readBytes())
            }
        }}finally{job.deleteRecursively()}
    }

    // Exact shipped0.1.13 contract inventory, captured before the new aesthetic preference.
    private val shippedContracts="""
kodak_portra_160|fixed135-w7-matte0-3ae4743dd68a758c|3ae4743dd68a758c7dd7a80029abedf94dc85ce6a318ca96bbd9bdce0dc60779|681662|fixed135/kodak_portra_160.webp|0
kodak_portra_400|fixed135-w7-matte0-9be6e195f89027ab|9be6e195f89027ab3ebe9547ebe89c28c088de2c518bb3b2d58b544c5d3d557f|351830|fixed135/kodak_portra_400.webp|0
kodak_portra_800|fixed135-positive-w6-4d25fc40108f9efb|4d25fc40108f9efb2cd8b39097ce45e288a96869a6c78e266128eeb60c9cb9fc|146038|fixed135/kodak_portra_800.webp|0
kodak_gold_200|fixed135-positive-w4-518661ef3e7d0ecf|518661ef3e7d0ecf32982b91e9dce6cbf50784b76d1d5063be4423a4bf638585|125304|fixed135/kodak_gold_200.webp|0
kodak_ultramax_400|fixed135-positive-w5-94a3b54a300c8e2f|94a3b54a300c8e2fd2dbfeaaf306afd1ccd2eab08a9af8e8742766b49d664d06|120896|fixed135/kodak_ultramax_400.webp|0
kodak_ektar_100|fixed135-positive-w4-b4b5e3a0f4e291f7|b4b5e3a0f4e291f7c7dad12949ae6d8f23ccb9a83716d0dc8ed952e802ed0e64|149224|fixed135/kodak_ektar_100.webp|0
fujifilm_c200|fixed135-w8-matte0-52bee3891ea2ef23|52bee3891ea2ef233687719621b4bac0168b26e529419959e3751789874fdb7a|363674|fixed135/fujifilm_c200.webp|0
fujifilm_xtra_400|fixed135-w8-matte0-2928e7dd9d7ae9b5|2928e7dd9d7ae9b57f570c5c0933fe5778c56e024ca808878872f5363ba728c1|329758|fixed135/fujifilm_xtra_400.webp|0
fujifilm_pro_400h|fixed135-w2-1b9ae4d34bf8acf5|1b9ae4d34bf8acf506a6cb0aeef075dfc3e4f659fb1e3158b407383a354e683f|445336|fixed135/fujifilm_pro_400h.webp|16777215
fujifilm_velvia_100|fixed135-w1-a2869851c334e3db|a2869851c334e3db75051244949f56ac80f1865267688ee43355a6be7392a4a0|225096|fixed135/fujifilm_velvia_100.webp|16777215
fujifilm_provia_100f|fixed135-w1-288a880372171c62|288a880372171c62c8274cfb9642afcf392fe448e9b43397b1a77cffb2a32cc7|174410|fixed135/fujifilm_provia_100f.webp|16777215
kodak_ektachrome_100|fixed135-w1-8051b5616b5c8b77|8051b5616b5c8b77a0d32bc1713ed54a70298d4f48e0762b0b6ccfbb9773cec7|341230|fixed135/kodak_ektachrome_100.webp|16777215
kodak_kodachrome_64|fixed135-w1-9cce81c18c7a72f0|9cce81c18c7a72f0ce90f295e72cb3690e08b5a7e19b646b23e32e6036b4a35b|443816|fixed135/kodak_kodachrome_64.webp|16777215
kodak_vision3_50d|fixed135-w1-dcfd713d6bee40db|dcfd713d6bee40db3eecd38de1e21f323bfa319d5e74a5900755865c8115f4e4|729254|fixed135/kodak_vision3_50d.webp|16777215
kodak_vision3_200t|fixed135-w9-matte0-50debdc5fbd3b559|50debdc5fbd3b559159b023df356ed5eaa770329d44de234b012a1d9f935e5a4|581062|fixed135/kodak_vision3_200t.webp|0
kodak_vision3_250d|fixed135-w8-matte0-efd9392bef22b185|efd9392bef22b1859ce3021630fb8805fcb58309db3b04ae3c4e78e4de26abb5|440458|fixed135/kodak_vision3_250d.webp|0
kodak_vision3_500t|fixed135-w1-3e631dc672438f8d|3e631dc672438f8dd4a6da07ec78808d16c3856659ff3c5551b3fa541123def0|612282|fixed135/kodak_vision3_500t.webp|16777215
kodak_verita_200d|fixed135-w1-8a4bf32cea38f93f|8a4bf32cea38f93f466e971daa441c90618c9706a280daad6372390281e8a45b|589964|fixed135/kodak_verita_200d.webp|16777215
kodak_ektar_100|fixed135-w2-3e39bb5a8668e076|3e39bb5a8668e076cad5b373c43e41a058c7cd70f241d1fc202d40ff1f26f516|790082|fixed135/kodak_ektar_100-legacy-v0112.webp|16777215
kodak_gold_200|fixed135-w3-4df06240171d3505|4df06240171d35051ba050cf6aa4e429a7e472decb2e2f3715a006b97150f5b9|481082|fixed135/kodak_gold_200-legacy-v0112.webp|16777215
kodak_ultramax_400|fixed135-w2-22c634ff44ed603b|22c634ff44ed603bf2035d33d79deccb49467adbec17f3087c80ec8ca8e95d53|463788|fixed135/kodak_ultramax_400-legacy-v0112.webp|16777215
kodak_portra_800|fixed135-w2-a1b5591132cce48b|a1b5591132cce48bb5c122eb7448e2ddcd2b9fa818edf439cb0c7c7f0e1a672f|667742|fixed135/kodak_portra_800-legacy-v0112.webp|16777215
kodak_portra_160|fixed135-w2-3ae4743dd68a758c|3ae4743dd68a758c7dd7a80029abedf94dc85ce6a318ca96bbd9bdce0dc60779|681662|fixed135/kodak_portra_160.webp|16777215
kodak_portra_400|fixed135-w2-9be6e195f89027ab|9be6e195f89027ab3ebe9547ebe89c28c088de2c518bb3b2d58b544c5d3d557f|351830|fixed135/kodak_portra_400.webp|16777215
fujifilm_c200|fixed135-w2-52bee3891ea2ef23|52bee3891ea2ef233687719621b4bac0168b26e529419959e3751789874fdb7a|363674|fixed135/fujifilm_c200.webp|16777215
fujifilm_xtra_400|fixed135-w3-2928e7dd9d7ae9b5|2928e7dd9d7ae9b57f570c5c0933fe5778c56e024ca808878872f5363ba728c1|329758|fixed135/fujifilm_xtra_400.webp|16777215
kodak_vision3_250d|fixed135-w1-efd9392bef22b185|efd9392bef22b1859ce3021630fb8805fcb58309db3b04ae3c4e78e4de26abb5|440458|fixed135/kodak_vision3_250d.webp|16777215
kodak_vision3_200t|fixed135-w1-50debdc5fbd3b559|50debdc5fbd3b559159b023df356ed5eaa770329d44de234b012a1d9f935e5a4|581062|fixed135/kodak_vision3_200t.webp|16777215
    """
}
