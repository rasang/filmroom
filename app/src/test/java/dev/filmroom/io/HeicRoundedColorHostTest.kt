package dev.filmroom.io

import android.app.Application
import android.graphics.ColorSpace
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkLimiter
import dev.filmroom.engine.NativeWorkUnavailable

/** Native ColorSpace math, not an assertion that host/OEM HEVC ImageDecoder decoded a file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicRoundedColorHostTest {
    @Test fun originalSizedAdmissionKeepsSystemHeadroomAndNeverAllocatesAFullFloatImage()=runBlocking {
        // Dimensions/encoded byte count are numeric cases; no user image or metadata bytes.
        val info=HeicContainer.Info(3064,4520,0,13849280,14155776)
        val estimate=HeicMemoryPolicy.estimatedBytes(info,4913688)
        assertEquals(525007384L,estimate)
        assertEquals(166191360L,LinearRgb.byteCount(info.displayWidth,info.displayHeight))
        val threshold=128L*1024*1024
        val requiredAvailable=threshold+64L*1024*1024+2*(estimate+16L*1024*1024)
        val limiter=NativeWorkLimiter();var entered=false
        try{limiter.run(estimate,{NativeMemorySnapshot(requiredAvailable-1,threshold,false)}){entered=true};fail("Below-headroom request accepted")}
        catch(_:NativeWorkUnavailable){}
        assertFalse(entered)
        limiter.run(estimate,{NativeMemorySnapshot(requiredAvailable,threshold,false)}){entered=true}
        assertTrue(entered)
        try{limiter.run(estimate,{NativeMemorySnapshot(requiredAvailable*2,threshold,true)}){fail("Low-memory flag ignored")};fail()}
        catch(_:NativeWorkUnavailable){}
    }
    private fun root():File = System.getProperty("filmroom.heic.referenceDir")?.let(::File)
        ?: listOf(File("src/androidTest/assets/heic"),File("app/src/androidTest/assets/heic")).first{it.isDirectory}
    private fun profile()=HeicIccProfile.inspectProfile(File(root(),"original-rounded-p3.icc").readBytes())
    private fun space(p:HeicIccProfile.Profile):ColorSpace.Rgb {
        val t=p.transfer
        return ColorSpace.Rgb("Validated rounded SDR matrix/TRC",p.toXyzD50.values().map{it.toFloat()}.toFloatArray(),
            ColorSpace.Rgb.TransferParameters(t.a,t.b,t.c,t.d,t.e,t.f,t.g))
    }
    @Test fun actualDescriptorMatchesButStandardP3SrgbAndWrongTransferFailClosed() {
        val p=profile();HeicColorPolicy.requireSource(space(p),p)
        val wrongMatrix=p.toXyzD50.values().also{it[0]+=.01}
        for(source in listOf(null,ColorSpace.get(ColorSpace.Named.SRGB),ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
            ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB),space(p.copy(toXyzD50=HeicIccProfile.Matrix(wrongMatrix))),
            space(p.copy(transfer=p.transfer.copy(d=.04045))))){
            try{HeicColorPolicy.requireSource(source,p);fail("Source fallback/mismatch must reject before target conversion")}
            catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
        }
        try{HeicColorPolicy.requireSource(space(p),p.identity);fail("Enum-only contract cannot accept rounded TRC")}
        catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
    }
    @Test fun platformConnectorMatchesIndependentLittleCmsAcrossThresholdAndGamut() {
        val p=profile();val source=space(p);HeicColorPolicy.requireSource(source,p)
        val connector=ColorSpace.connect(source,ColorSpace.get(ColorSpace.Named.SRGB),ColorSpace.RenderIntent.RELATIVE)
        val reference=JSONObject(File(root(),"rounded-p3-srgb-reference.json").readText())
        val samples=reference.getJSONArray("samples")
        assertTrue(samples.length()>=384)
        repeat(samples.length()){i->
            val sample=samples.getJSONObject(i);val input=sample.getJSONArray("source_encoded")
            val expected=sample.getJSONArray("srgb_encoded")
            val actual=connector.transform(FloatArray(3){input.getDouble(it).toFloat()})
            repeat(3){c->assertEquals("LCMS sample $i channel $c",expected.getDouble(c),actual[c].toDouble(),reference.getDouble("float_tolerance"))}
        }
    }
}
