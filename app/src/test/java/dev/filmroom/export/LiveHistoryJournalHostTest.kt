package dev.filmroom.export

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class LiveHistoryJournalHostTest {
    private val image="content://media/external_primary/images/media/42"
    private val video="content://media/external_primary/video/media/43"
    private val stem="Filmroom_0123456789abcdef0123456789abcdef_MP"
    private fun pair()=ExportHistorySnapshot(UUID.randomUUID().toString(),"fingerprint",ExportJobState.COMPLETED,image,null,"$stem.jpeg",listOf(image,video),listOf("$stem.jpeg","$stem.mp4"))
    @Test fun pairJournalRestoresExactPendingVideoAndAlreadyDeletedImage() {
        val root=Files.createTempDirectory("live-history").toFile()
        try {
            val snapshot=pair()
            val op=ExportHistoryOperation(UUID.randomUUID().toString(),true,listOf(snapshot),status="awaiting-consent",consentUri=video,consentKind="DELETE_REQUEST",deletedUris=listOf(image))
            ExportHistoryJournal(root).write(op)
            val recovered=ExportHistoryJournal(root).read(op.id)!!
            assertEquals(op,recovered)
            assertTrue(recovered.targets.single().validLiveAssets())
            assertEquals(listOf(video),recovered.remaining.single().outputUris.filter{it !in recovered.deletedUris})
            assertEquals("$stem.mp4",recovered.targets.single().outputNameFor(video))
        }finally{root.deleteRecursively()}
    }
    @Test fun mismatchedCompanionAndCoverAreNotEligible() {
        val p=pair()
        assertFalse(p.copy(livePublishedNames=listOf("$stem.jpeg","Filmroom_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_MP.mp4")).validLiveAssets())
        assertFalse(p.copy(mediaUri="content://media/external_primary/images/media/99").validLiveAssets())
        assertFalse(p.copy(livePublishedUris=listOf(image,image)).validLiveAssets())
        assertFalse(p.copy(livePublishedNames=emptyList()).validLiveAssets())
    }
    @Test fun ordinarySnapshotKeepsOriginalSinglePhotoBehavior() {
        val s=ExportHistorySnapshot("x","f",ExportJobState.COMPLETED,image,123,"export.png")
        assertEquals(listOf(image),s.outputUris)
        assertEquals("export.png",s.outputNameFor(image))
        assertTrue(s.livePublishedUris.isEmpty())
    }
}
