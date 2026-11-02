package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.diagnostics.FilmProfile
import dev.filmroom.io.*
import dev.filmroom.ui.FilmStockArtwork
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Complete18BorderCorpusHostTest {
    @Test fun all18ReadOnlyPngLoaderGeometryAndSharedCompositeSmokeCover20Profiles(){
        assumeTrue("External final-review corpus opt-in",System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        val root=File(System.getenv("FILMROOM_BORDER_CORPUS_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/typography-v0114-r2/qa-corpus")
        val manifest=JSONObject(File(root,"manifest.json").readText());val stocks=manifest.getJSONArray("stocks")
        assertFalse(manifest.optBoolean("production_approved",false));assertEquals(18,stocks.length())
        val profiles=mutableMapOf<String,String>();val physical=mutableSetOf<String>()
        val dir=Files.createTempDirectory("corpus18-smoke").toFile()
        try{
            for(index in 0 until stocks.length()){
                val item=stocks.getJSONObject(index);val stock=item.getString("physical_stock");assertTrue(physical.add(stock))
                val profileList=item.getJSONArray("profiles");for(j in 0 until profileList.length())assertNull(profiles.put(profileList.getString(j),stock))
                val file=File(root,item.getString("asset"));require(file.parentFile.canonicalFile==root.canonicalFile)
                val spec=CandidateBorderDefinitions.definitions.single{it.spec.stock==stock}.spec.copy(sha256=item.getString("sha256"),encodedBytes=file.length().toInt(),encoding=StraightAlphaBorderLoader.Encoding.PNG)
                val raster=StraightAlphaBorderLoader.load(spec,{file.inputStream()})
                for((w,h) in listOf(96 to 64,64 to 96,384 to 384)){
                    val snap=BorderSnapshot.fixed135(stock,spec.version,w,h);raster.validate(snap)
                    if(w==h){assertFalse(snap.portrait);assertEquals(3.0/2,snap.photoWidth.toDouble()/snap.photoHeight,0.0)}
                    val photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff808080.toInt())}
                    val input=File(dir,"source.rgb");val row=ByteBuffer.allocate(w*12).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(w*3){row.putFloat(LinearRgb.srgb8ToLinear[128])};input.outputStream().use{out->repeat(h){out.write(row.array())}}
                    val before=input.readBytes();val output=File(dir,"composed.rgb")
                    val preview=BorderPreview.compose(photo,snap,raster)
                    try{
                        BorderCompositor.composite(input,output,snap,raster,OutputColorSpace.SRGB)
                        assertArrayEquals(before,input.readBytes());assertEquals(snap.outputBytes,output.length())
                        val floats=ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        for(y in 0 until snap.outputHeight)for(x in 0 until snap.outputWidth){val pixel=preview.getPixel(x,y);for(c in 0..2)assertEquals(OutputColorSpace.SRGB.encode8(floats.get()),(pixel ushr(16-c*8))and 255)}
                    }finally{preview.recycle();photo.recycle()}
                }
            }
            assertEquals(18,physical.size);assertEquals(20,profiles.size)
            assertEquals(FilmProfile.entries.map{it.id}.toSet()-setOf("kodak_doublex","kodak_trix"),profiles.keys)
            assertEquals(FilmStockArtwork.physicalStockByProfile.filterKeys{it !in setOf("kodak_doublex","kodak_trix","kodak_2302")},profiles)
            val aliases=manifest.getJSONObject("profile_aliases")
            assertEquals("kodak_portra_800",aliases.getString("kodak_portra_800_push1"));assertEquals("kodak_portra_800",aliases.getString("kodak_portra_800_push2"))
            assertFalse(dev.filmroom.model.EditorState().filmBorder) // User-facing default stays OFF even in authorized candidate registry.
            System.getenv("FILMROOM_BORDER_QA_DIR")?.let{RuntimeBorderVisualQa.render(File(it))}
        }finally{dir.deleteRecursively()}
    }
}
