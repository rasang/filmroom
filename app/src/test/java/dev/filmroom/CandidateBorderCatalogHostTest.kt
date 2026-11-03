package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.io.File
import org.junit.Assume.assumeTrue
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateBorderCatalogHostTest {
    @Test fun isolatedCandidateCoversExactlyCurrentFilmProfilesWithUserToggleOff(){
        assertTrue(BorderCatalog.PROTOTYPE_ENABLED) // Explicit candidate authorization, NOT production.
        assertFalse(EditorState().filmBorder)
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val profiles=JSONObject(app.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val films=(0 until profiles.length()).map{profiles.getJSONObject(it)}.filter{it.getString("kind") in setOf("negative","reversal")}.map{it.getString("id")}.toSet()
        assertEquals(22,films.size);assertEquals(films,CandidateBorderDefinitions.profiles.keys+setOf("kodak_doublex","kodak_trix"))
        assertEquals(18,CandidateBorderDefinitions.definitions.size)
        for(stock in films){assertTrue(BorderCatalog.available(stock));assertEquals(stock,BorderCatalog.snapshot(stock,96,64).stock)}
        assertFalse(BorderCatalog.available("kodak_2383"));assertFalse(BorderCatalog.available("missing"))
        val normal=BorderCatalog.snapshot("kodak_portra_800",96,64)
        for(alias in listOf("kodak_portra_800_push1","kodak_portra_800_push2"))assertEquals(normal.assetVersion,BorderCatalog.snapshot(alias,96,64).assetVersion)
    }
    @Test fun bundledLosslessBytesAndCleanManifestMatchCompiledPinnedDefinitions(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val manifestText=app.assets.open("fixed135/runtime-manifest.json").bufferedReader().use{it.readText()}
        assertFalse(manifestText.contains("/workspace/"));assertFalse(manifestText.contains("/tmp/"))
        val manifest=JSONObject(manifestText);val assets=manifest.getJSONArray("assets")
        assertEquals(18,assets.length())
        var total=0L
        for(definition in CandidateBorderDefinitions.definitions){
            val spec=definition.spec
            assertEquals(StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP,spec.encoding)
            assertEquals("Current presentation uses explicit pure white",0xffffff,spec.holeMatteSrgb)
            val bytes=app.assets.open(definition.assetPath).use{it.readBytes()}
            assertEquals(spec.encodedBytes,bytes.size)
            assertEquals(spec.sha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)})
            StraightAlphaBorderLoader.validateEncoding(bytes,spec.encoding)
            val recorded=(0 until assets.length()).map{assets.getJSONObject(it)}.single{it.getString("physical_stock")==spec.stock}
            assertEquals(spec.version,recorded.getString("asset_version"));assertEquals(spec.sha256,recorded.getString("sha256"))
            assertEquals(spec.encodedBytes,recorded.getInt("bytes"));assertTrue(recorded.has("source_png"))
            total+=bytes.size
        }
        assertEquals(6866628L,total)
        val transcode=manifest.getJSONObject("transcode")
        assertEquals(total,transcode.getLong("runtime_webp_bytes"))
        assertEquals((0 until assets.length()).sumOf{assets.getJSONObject(it).getJSONObject("source_png").getLong("bytes")},transcode.getLong("source_png_bytes"))
        val oldAssets=manifest.getJSONArray("legacy_assets")
        val oldBytes=(0 until oldAssets.length()).sumOf{oldAssets.getJSONObject(it).getLong("bytes")}
        assertEquals(oldBytes,transcode.getLong("legacy_runtime_webp_bytes"));val uniqueRows=((0 until assets.length()).map{assets.getJSONObject(it)}+(0 until oldAssets.length()).map{oldAssets.getJSONObject(it)}).associateBy{it.getString("asset_path")}
        assertEquals(uniqueRows.values.sumOf{it.getLong("bytes")},transcode.getLong("bundled_runtime_webp_bytes"))
        assertEquals(24,uniqueRows.size);assertEquals(45,transcode.getInt("definition_count"));assertEquals(6,transcode.getInt("legacy_additional_artifact_count"))
        assertEquals(18,transcode.getInt("current_asset_count"));assertEquals(27,transcode.getInt("legacy_asset_count"));assertEquals(24,transcode.getInt("bundled_asset_count"))
        val filenames=app.assets.list("fixed135")!!.toSet()
        // The old45 positive/legacy contracts above remain immutable. Direct route adds28
        // versioned contracts sharing14 additional byte-pinned payloads.
        val direct=manifest.getJSONArray("direct_assets")
        assertEquals(28,direct.length());assertEquals(28,DirectBorderDefinitions.definitions.size)
        val allRows=uniqueRows.toMutableMap()
        for(definition in DirectBorderDefinitions.definitions){
            val spec=definition.spec
            val recorded=(0 until direct.length()).map{direct.getJSONObject(it)}.single{it.getString("asset_version")==spec.version}
            assertEquals(definition.assetPath,recorded.getString("asset_path"))
            assertEquals(spec.sha256,recorded.getString("sha256"));assertEquals(spec.encodedBytes,recorded.getInt("bytes"))
            val bytes=app.assets.open(definition.assetPath).use{it.readBytes()}
            assertEquals(spec.encodedBytes,bytes.size)
            assertEquals(spec.sha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)})
            StraightAlphaBorderLoader.validateEncoding(bytes,spec.encoding)
            allRows[definition.assetPath]=recorded
        }
        assertEquals(73,assets.length()+oldAssets.length()+direct.length())
        assertEquals(38,allRows.size)
        assertEquals((allRows.keys+(BwBorderDefinitions.definitions+BwBorderDefinitions.legacyDefinitions).map{it.assetPath}).map{it.removePrefix("fixed135/")}.toSet(),filenames.filter{it.endsWith(".webp")}.toSet())
        assertEquals(44,filenames.count{it.endsWith(".webp")});assertEquals(0,filenames.count{it.endsWith(".png")})
        assertTrue(filenames.containsAll(listOf("runtime-manifest.json","provenance.json","PROVENANCE-AND-NOTICES.md")))
        val notices=app.assets.open("fixed135/PROVENANCE-AND-NOTICES.md").bufferedReader().use{it.readText()}
        assertTrue(notices.contains("LoMit"));assertTrue(notices.contains("creativecommons.org/licenses/by/4.0"))
    }
    @Test fun currentAndLegacyRuntimeBytesAndSnapshotsRemainPinnedWithoutDecoding(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val all=CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions
        assertEquals(45,all.size);assertEquals(27,CandidateBorderDefinitions.legacyDefinitions.size)
        val manifest=JSONObject(app.assets.open("fixed135/runtime-manifest.json").bufferedReader().use{it.readText()})
        val records=listOf("assets","legacy_assets").flatMap{key->val rows=manifest.getJSONArray(key);(0 until rows.length()).map{rows.getJSONObject(it)}}
        for(definition in all){
            val spec=definition.spec;val record=records.single{it.getString("asset_version")==spec.version}
            val bytes=app.assets.open(definition.assetPath).use{it.readBytes()}
            assertEquals(spec.encodedBytes,bytes.size);assertEquals(spec.sha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)})
            assertEquals(spec.sha256,record.getString("sha256"));assertEquals(definition.assetPath,record.getString("asset_path"))
        }
        for(definition in all){
            val spec=definition.spec;val pinned=BorderSnapshot.fixed135(spec.stock,spec.version,1440,960)
            BorderCatalog.verifySnapshot(pinned) // Metadata-only: standard aggregate gate never decodes28 atlases.
        }
        for(definition in CandidateBorderDefinitions.legacyDefinitions){
            val spec=definition.spec
            val newer=CandidateBorderDefinitions.definitions.single{it.spec.stock==spec.stock}
            assertNotEquals("Old jobs never silently rebind",spec.version,newer.spec.version)
            assertEquals(0xffffff,newer.spec.holeMatteSrgb)
            assertTrue(spec.holeMatteSrgb==0 || spec.holeMatteSrgb==0xffffff)
            if(spec.stock in setOf("kodak_portra_160","kodak_portra_400","fujifilm_c200","fujifilm_xtra_400","kodak_vision3_250d","kodak_vision3_200t")){
                assertEquals("Matte-only contracts share exact payload",spec.sha256,newer.spec.sha256)
                assertEquals(definition.assetPath,newer.assetPath)
            }
            if(spec.stock=="kodak_portra_800")for(alias in listOf("kodak_portra_800","kodak_portra_800_push1","kodak_portra_800_push2")){
                BorderCatalog.verifySnapshot(BorderSnapshot.fixed135(alias,spec.version,1440,960))
                assertEquals(newer.spec.version,BorderCatalog.snapshot(alias,96,64).assetVersion)
            }
        }
    }
    @Test fun optInAll45CurrentAndLegacyContractsResolveWithExactMatteAndAliases(){
        assumeTrue("Actual45 atlas decode/admission runs in an explicit fresh asset JVM",System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        val app=ApplicationProvider.getApplicationContext<Application>()
        BorderCatalog.initialize(app.assets){dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val definitions=CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions
        assertEquals(45,definitions.size)
        try{for(definition in definitions){
            BorderCatalog.clearCache()
            val spec=definition.spec;val pinned=BorderSnapshot.fixed135(spec.stock,spec.version,1440,960)
            val heap=Runtime.getRuntime().let{it.maxMemory()-it.totalMemory()+it.freeMemory()}
            println("Actual atlas admission ${spec.stock}/${spec.version}: Java headroom=$heap; production32MiB+16MiB guard unchanged")
            runBlocking{withContext(Dispatchers.IO){
                val raster=BorderCatalog.resolve(pinned);assertEquals(spec.version,raster.version);raster.validate(pinned)
                val rgb=FloatArray(3)
                raster.sample(pinned,pinned.frameOriginX+2.375*pinned.pixelsPerMm,pinned.frameOriginY+3.407*pinned.pixelsPerMm,rgb)
                val expected=floatArrayOf(((spec.holeMatteSrgb ushr 16)and 255)/255f,((spec.holeMatteSrgb ushr 8)and 255)/255f,(spec.holeMatteSrgb and 255)/255f)
                assertArrayEquals("Pinned contract hole matte ${spec.version}",expected,rgb,0.00001f)
                if(spec.stock=="kodak_portra_800")for(alias in listOf("kodak_portra_800","kodak_portra_800_push1","kodak_portra_800_push2")){
                    val aliased=pinned.copy(stock=alias);val resolved=BorderCatalog.resolve(aliased)
                    assertEquals(alias,resolved.stock);assertEquals(spec.version,resolved.version);resolved.validate(aliased)
                    resolved.sample(aliased,aliased.frameOriginX+2.375*aliased.pixelsPerMm,aliased.frameOriginY+3.407*aliased.pixelsPerMm,rgb)
                    assertArrayEquals("Base/push aliases preserve versioned matte",expected,rgb,0.00001f)
                }
            }}
            BorderCatalog.clearCache()
        }}finally{BorderCatalog.clearCache();BorderCatalog.initialize(app.assets)}
    }
    @Test fun optInAll18CurrentAndTwentySevenLegacyPngsMatchRuntimeInEveryRgbaChannel(){
        assumeTrue(System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        val app=ApplicationProvider.getApplicationContext<Application>()
        val currentRoot=File(System.getenv("FILMROOM_BORDER_CORPUS_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/typography-v0114-r2/qa-corpus")
        val roots=listOf(currentRoot,
            File("/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-integration-candidate-v9-positive4-matte10-qa-corpus"),
            File("/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-integration-candidate-v3-qa-corpus"))
        val pngs=roots.flatMap{root->
            val rows=JSONObject(File(root,"manifest.json").readText()).getJSONArray("stocks")
            (0 until rows.length()).map{rows.getJSONObject(it).let{row->row.getString("sha256") to File(root,row.getString("asset"))}}
        }.toMap()
        val manifest=JSONObject(app.assets.open("fixed135/runtime-manifest.json").bufferedReader().use{it.readText()})
        val records=listOf("assets","legacy_assets").flatMap{key->val rows=manifest.getJSONArray(key);(0 until rows.length()).map{rows.getJSONObject(it)}}.associateBy{it.getString("asset_version")}
        for(definition in CandidateBorderDefinitions.definitions+CandidateBorderDefinitions.legacyDefinitions){
            val spec=definition.spec;val row=records.getValue(spec.version).getJSONObject("source_png")
            val png=pngs.getValue(row.getString("sha256"))
            val source=spec.copy(sha256=row.getString("sha256"),encodedBytes=png.length().toInt(),encoding=StraightAlphaBorderLoader.Encoding.PNG)
            val original=StraightAlphaBorderLoader.decodeStraight(source,{png.inputStream()})
            val runtime=StraightAlphaBorderLoader.decodeStraight(spec,{app.assets.open(definition.assetPath)})
            assertArrayEquals("Every RGBA channel: ${spec.stock}/${spec.version}",original,runtime)
        }
    }
}
