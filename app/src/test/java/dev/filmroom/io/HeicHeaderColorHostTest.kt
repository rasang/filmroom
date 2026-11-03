package dev.filmroom.io

import android.app.Application
import android.graphics.ColorSpace
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicHeaderColorHostTest {
    private fun profile()=HeicIccProfile.inspectProfile(File("src/androidTest/assets/heic/original-rounded-p3.icc").readBytes())
    private fun space(p:HeicIccProfile.Profile):ColorSpace.Rgb {
        val t=p.transfer
        return ColorSpace.Rgb("Synthetic actual descriptor",p.toXyzD50.values().map{it.toFloat()}.toFloatArray(),
            ColorSpace.Rgb.TransferParameters(t.a,t.b,t.c,t.d,t.e,t.f,t.g))
    }
    private fun rejects(block:()->Unit){try{block();fail("Must reject")}catch(_:PhotoException){}}
    @Test fun androidMatchCanonicalizesOnlyHeaderWhileActualDescriptorRemainsUnchanged() {
        val p=profile();val before=p.transfer;val t=p.transfer
        val canonical=ColorSpace.match(p.toXyzD50.values().map{it.toFloat()}.toFloatArray(),
            ColorSpace.Rgb.TransferParameters(t.a,t.b,t.c,t.d,t.e,t.f,t.g))
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3),canonical)
        rejects{HeicColorPolicy.requireSource(canonical,p)} // encoded-source comparison stays strict
        HeicColorPolicy.requireHeaderColor(canonical,p)
        HeicColorPolicy.requireHeaderColor(space(p),p)
        assertEquals(before,p.transfer)
        assertNotEquals(p.identity.expectedTransfer,p.transfer)
    }
    @Test fun wrongPrimariesUnknownTransferAndUnnamedLookalikeRemainRejected() {
        val p=profile();val named=ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        for(source in listOf(null,ColorSpace.get(ColorSpace.Named.SRGB),ColorSpace.get(ColorSpace.Named.LINEAR_SRGB),
            ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB),space(p.copy(transfer=p.identity.expectedTransfer))))
            rejects{HeicColorPolicy.requireHeaderColor(source,p)}
        for(invalid in listOf(p.copy(transfer=p.transfer.copy(d=p.transfer.d+1.0/65536)),
            p.copy(identity=HeicIccProfile.ColorIdentity.SRGB),
            p.copy(toXyzD50=HeicIccProfile.Matrix(p.toXyzD50.values().also{it[0]+=.01}))))
            rejects{HeicColorPolicy.requireHeaderColor(named,invalid)}
    }
    @Test fun ordinaryStandardProfilesKeepOriginalContract() {
        for(id in HeicIccProfile.ColorIdentity.entries){
            val p=HeicIccProfile.Profile.standard(id)
            HeicColorPolicy.requireHeaderColor(space(p),p)
            rejects{HeicColorPolicy.requireHeaderColor(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB),p)}
        }
    }
    @Test fun actualCurveConversionIsNotReplacedByNamedCurveOrAppliedTwice() {
        val p=profile();HeicColorPolicy.requireHeaderColor(ColorSpace.get(ColorSpace.Named.DISPLAY_P3),p)
        val dst=ColorSpace.get(ColorSpace.Named.SRGB)
        val actual=ColorSpace.connect(space(p),dst,ColorSpace.RenderIntent.RELATIVE)
        val named=ColorSpace.connect(ColorSpace.get(ColorSpace.Named.DISPLAY_P3),dst,ColorSpace.RenderIntent.RELATIVE)
        val color=floatArrayOf(.5f,.5f,.5f)
        val a=actual.transform(color.copyOf());val b=named.transform(color.copyOf())
        assertTrue(a.indices.any{kotlin.math.abs(a[it]-b[it])>0.00001f})
        val once=actual.transform(floatArrayOf(.2f,.7f,.3f));val twice=actual.transform(once.copyOf())
        assertTrue(once.indices.any{kotlin.math.abs(once[it]-twice[it])>.01f})
    }
}
