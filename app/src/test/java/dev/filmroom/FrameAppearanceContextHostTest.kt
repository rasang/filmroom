package dev.filmroom

import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.model.CompositionTransform
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FrameAppearanceContextHostTest {
    @Test fun generatedKindsExactlyMatchBundledCatalogIncludingPushProfiles() {
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        val bytes=app.assets.open("engine-data/profile_catalog.json").use{it.readBytes()}
        assertEquals(FrameProfileCatalog.catalogSha256,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        val rows=JSONObject(String(bytes)).getJSONArray("profiles")
        val expected=(0 until rows.length()).map{rows.getJSONObject(it)}.filter{it.getString("kind") in setOf("negative","reversal")}
            .associate{it.getString("id") to FrameAppearanceContext.FilmKind.valueOf(it.getString("kind").uppercase())}
        assertEquals(expected,FrameProfileCatalog.kinds)
        assertEquals(20,expected.size)
        assertThrows(IllegalArgumentException::class.java){FrameAppearanceContext.forProfile("unknown_negative","direct",true)}
    }
    @Test fun routesAndHolePreferenceAreIndependentAndRoundTrip() {
        FrameProfileCatalog.kinds.forEach{(profile,kind)->
            val routes=if(kind==FrameAppearanceContext.FilmKind.REVERSAL)listOf("direct")else listOf("print","direct")
            routes.forEach{route->listOf(false,true).forEach{white->
                val c=FrameAppearanceContext.forProfile(profile,route,white)
                assertEquals(c,FrameAppearanceContext.fromJson(c.json()))
                assertEquals(if(kind==FrameAppearanceContext.FilmKind.NEGATIVE && route=="direct")FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION else FrameAppearanceContext.Material.POSITIVE_DISPLAY,c.material)
                assertEquals(if(white || kind==FrameAppearanceContext.FilmKind.REVERSAL)0xffffff else 0,c.holeMatteSrgb)
            }}
        }
        assertThrows(IllegalArgumentException::class.java){FrameAppearanceContext.forProfile("kodak_ektachrome_100","print",true)}
        assertThrows(IllegalArgumentException::class.java){FrameAppearanceContext.forProfile("kodak_portra_160","bogus",true)}
    }
    @Test fun legacySnapshotsStayLegacyAndNewContextSeparatesInteractiveCache() {
        for(aspect in listOf(1.5,2.0/3.0)) {
            val old=BorderSnapshot.fixed135("kodak_portra_160","old-frozen",900,1200,composition=CompositionTransform(targetAspect=aspect))
            assertFalse(JSONObject(old.json()).has("appearance"))
            assertEquals(old,BorderSnapshot.fromJson(old.json()))
            val direct=old.copy(appearance=FrameAppearanceContext.forProfile(old.stock,"direct",false))
            val print=old.copy(appearance=FrameAppearanceContext.forProfile(old.stock,"print",false))
            assertEquals(direct,BorderSnapshot.fromJson(direct.json()))
            assertNotEquals(InteractiveBorderPreview.key(direct),InteractiveBorderPreview.key(print))
            assertNotEquals(InteractiveBorderPreview.key(old),InteractiveBorderPreview.key(direct))
            assertEquals(old.outputWidth,direct.outputWidth);assertEquals(old.outputHeight,direct.outputHeight)
            assertNull(BorderSnapshot.fromJson(old.json()).appearance)
        }
    }
    @Test fun registryRejectsRouteOrMatteMismatchWithoutDecoding() {
        val d=CandidateBorderDefinitions.selectedDefinition("kodak_portra_160",true)
        var decodes=0
        val registry=LazyBorderRegistry(listOf(d),mapOf(d.spec.stock to d.key)){_,_->decodes++;error("Must not decode")}
        val legacy=registry.snapshot(d.spec.stock,900,1200)
        registry.verifySnapshot(legacy)
        registry.verifySnapshot(legacy.copy(appearance=FrameAppearanceContext.forProfile(d.spec.stock,"print",true)))
        assertThrows(IllegalArgumentException::class.java){registry.verifySnapshot(legacy.copy(appearance=FrameAppearanceContext.forProfile(d.spec.stock,"direct",true)))}
        assertThrows(IllegalArgumentException::class.java){registry.verifySnapshot(legacy.copy(appearance=FrameAppearanceContext.forProfile(d.spec.stock,"print",false)))}
        val negative=d.copy(material=FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)
        val negativeRegistry=LazyBorderRegistry(listOf(negative),mapOf(d.spec.stock to d.key)){_,_->decodes++;error("Must not decode")}
        negativeRegistry.verifySnapshot(legacy.copy(appearance=FrameAppearanceContext.forProfile(d.spec.stock,"direct",true)))
        assertThrows(IllegalArgumentException::class.java){negativeRegistry.verifySnapshot(legacy)}
        assertEquals(0,decodes)
    }
    @Test fun newQueueFreezesContextAndLegacyDirectNeverInfersNewColors() {
        val root=java.nio.file.Files.createTempDirectory("route-frame-jobs").toFile()
        try {
            val store=dev.filmroom.export.ExportJobStore(root)
            val d=CandidateBorderDefinitions.selectedDefinition("kodak_portra_160",true)
            val p=dev.filmroom.model.EditorState(filmStock=d.spec.stock,filmBorder=true,outputRoute="print")
            val legacy=BorderSnapshot.fixed135(d.spec.stock,d.spec.version,144,96)
            val current=legacy.copy(appearance=FrameAppearanceContext.forProfile(p.filmStock,p.outputRoute,p.negativeWhiteHoles))
            val job=dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),1,p,144,96,dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.PAUSED,border=current)
            store.directory(job.id).mkdirs();store.update(job)
            val old=job.copy(id=dev.filmroom.export.ExportJobStore.newId(),createdAt=2,parameters=p.copy(outputRoute="direct"),border=legacy)
            store.directory(old.id).mkdirs();store.update(old)
            val reopened=dev.filmroom.export.ExportJobStore(root).jobs.value
            assertEquals(current,reopened.single{it.id==job.id}.border)
            val retained=reopened.single{it.id==old.id}
            assertEquals("direct",retained.parameters.outputRoute);assertEquals(legacy,retained.border);assertNull(retained.border!!.appearance)
            assertThrows(IllegalArgumentException::class.java){job.copy(parameters=p.copy(outputRoute="direct"))}
            assertThrows(IllegalArgumentException::class.java){job.copy(parameters=p.copy(negativeWhiteHoles=false))}
        } finally {root.deleteRecursively()}
    }

}
