package dev.filmroom

import dev.filmroom.border.BorderCatalog
import dev.filmroom.border.BwBorderDefinitions
import dev.filmroom.engine.DetailKey
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

/** Durable contracts only: does not claim to simulate an installed-app upgrade or native resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class BwPersistedRenderContractHostTest {
    @Test fun queueReopenPreservesNonDefaultDevelopmentAndSavedDatabaseChoice() {
        val root=Files.createTempDirectory("bw-queue-contract").toFile()
        try {
            val original=EditorState(filmStock="kodak_doublex",paperStock="kodak_2302",
                filmDevelopmentTime=9f,paperDevelopmentTime=7f,
                neutralCyan=11f,neutralMagenta=72f,neutralYellow=123f)
            val cases=listOf(original,original.copy(neutralFiltersFromDatabase=false),
                original.copy(printIlluminant="D65"),original.copy(printIlluminant="D65",neutralFiltersFromDatabase=false))
            val store=ExportJobStore(root)
            val jobs=cases.mapIndexed { index,p ->
                ExportJob(ExportJobStore.newId(),index.toLong(),p,12,8,ExportFormat.PNG,95,state=ExportJobState.PAUSED).also(store::update)
            }
            val reopened=ExportJobStore(root)
            jobs.forEach { before ->
                val after=requireNotNull(reopened.get(before.id))
                assertEquals(ExportJobState.PAUSED,after.state)
                assertEquals(before.parameters,after.parameters)
                assertEquals(9f,after.parameters.filmDevelopmentTime)
                assertEquals(7f,after.parameters.paperDevelopmentTime)
                assertEquals(before.parameters.effectiveNeutralFiltersFromDatabase,after.parameters.effectiveNeutralFiltersFromDatabase)
                assertEquals(123f,after.parameters.neutralYellow)
            }
            assertTrue(reopened.get(jobs[0].id)!!.parameters.effectiveNeutralFiltersFromDatabase)
            jobs.drop(1).forEach { assertFalse(reopened.get(it.id)!!.parameters.effectiveNeutralFiltersFromDatabase) }
            assertTrue(reopened.get(jobs[2].id)!!.parameters.neutralFiltersFromDatabase)
        } finally { root.deleteRecursively() }
    }

    @Test fun queueReopenKeepsLegacyFrameVersionInsteadOfRebindingToCurrent() {
        BorderCatalog.initialize(RuntimeEnvironment.getApplication().assets)
        val root=Files.createTempDirectory("bw-frame-contract").toFile()
        try {
            val p=EditorState(filmStock="kodak_trix",outputRoute="direct",filmBorder=true)
            val current=BorderCatalog.snapshot(p.frameStock,600,400,600,400,p.composition,true,"direct")
            val old=current.copy(assetVersion=BwBorderDefinitions.legacyDefinitions.single{it.spec.stock=="kodak_trix"}.spec.version)
            assertNotEquals(old.assetVersion,current.assetVersion)
            val saved=listOf(old,current).mapIndexed { index,border ->
                ExportJob(ExportJobStore.newId(),index.toLong(),p,600,400,ExportFormat.PNG,95,state=ExportJobState.PAUSED,border=border)
            }
            val store=ExportJobStore(root);saved.forEach(store::update)
            val reopened=ExportJobStore(root)
            saved.forEach { before ->
                val after=requireNotNull(reopened.get(before.id))
                assertEquals(before.border,after.border)
                BorderCatalog.verifySnapshot(requireNotNull(after.border))
            }
            assertEquals(BwBorderDefinitions.SUPER8_EDGE_VERSION,current.assetVersion)
        } finally { root.deleteRecursively() }
    }

    @Test fun detailIdentitySeparatesDatabaseAndDevelopmentChangesAndDataRevisions() {
        val p=EditorState(filmStock="kodak_doublex",paperStock="kodak_2302",filmDevelopmentTime=9f,paperDevelopmentTime=7f)
        // Uses exactly the parameter serialization supplied by OwnedDetailProvider.open.
        fun key(state:EditorState,data:String="beta3-data")=DetailKey("source","mapping",data,state.json("preview",includePresentation=false),12,8)
        val original=key(p)
        assertEquals(original,key(EditorState.fromJson(p.json())))
        for(changed in listOf(p.copy(filmDevelopmentTime=0f),p.copy(paperDevelopmentTime=0f),
            p.copy(neutralFiltersFromDatabase=false),p.copy(printIlluminant="D65"),p.copy(neutralYellow=123f))) {
            assertNotEquals(original,key(changed))
        }
        assertNotEquals(original,key(p,"beta2-data"))
    }
}
