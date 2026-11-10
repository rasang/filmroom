package dev.filmroom.diagnostics

import android.os.Build
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class DiagnosticDeviceInfoTest {
    @Test fun capturesOnlyPublicProductAndOsLabels() {
        assertEquals(DiagnosticDeviceInfo.from(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.VERSION.RELEASE), DiagnosticDeviceInfo.capture())
        assertEquals(setOf("manufacturer", "brand", "model", "androidRelease"), DiagnosticDeviceInfo::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }
    @Test fun boundsLabelsAndRemovesControlCharacters() {
        val info=DiagnosticDeviceInfo.from(null, " \n", "P\nD\r\u001b\u202e2405", "x".repeat(10000))
        assertEquals("unknown",info.manufacturer); assertEquals("unknown",info.brand)
        assertEquals("PD2405",info.model); assertEquals(128,info.androidRelease.length)
        assertFalse(info.text().contains('\n'))
    }
    @Test fun exportsDeviceInTextAndEscapedJsonWithApiVersion() {
        val info=DiagnosticDeviceInfo.from("vivo", "iQOO", "PD\"2405\\", "15")
        val meta=DiagnosticMetadata(androidApi=35,device=info)
        val snapshot=DiagnosticSnapshot(5,1000,10,emptyList(),emptyList(),0,false,true)
        val parsed=JSONObject(ChineseDiagnostics.json(snapshot,meta))
        assertEquals("vivo",parsed.getString("deviceManufacturer"));assertEquals("iQOO",parsed.getString("deviceBrand"))
        assertEquals(info.model,parsed.getString("deviceModel"));assertEquals("15",parsed.getString("androidRelease"))
        assertEquals(35,parsed.getInt("androidApi"));assertTrue(meta.text().contains(info.text()))
        val dir=kotlin.io.path.createTempDirectory().toFile()
        try { assertTrue(DiagnosticArchive.write(dir,snapshot,meta).file.readText().contains(info.text())) }
        finally { dir.deleteRecursively() }
    }
}
