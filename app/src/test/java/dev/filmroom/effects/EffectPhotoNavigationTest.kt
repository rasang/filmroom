package dev.filmroom.effects

import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test

class EffectPhotoNavigationTest {
    private fun job(id:String,time:Long,state:ExportJobState=ExportJobState.COMPLETED,uri:String?="content://photo/$id")=
        ExportJob(id,time,EditorState(),100,100,ExportFormat.PNG,95,state=state,mediaUri=uri)
    @Test fun newestFirstMatchesExportList(){val j=listOf(job("a",1),job("c",3),job("b",2));assertEquals("b",EffectPhotoNavigation.neighbor(j,"c",1)?.id);assertEquals("c",EffectPhotoNavigation.neighbor(j,"b",-1)?.id)}
    @Test fun edgesNeverWrap(){val j=listOf(job("a",1),job("b",2));assertNull(EffectPhotoNavigation.neighbor(j,"b",-1));assertNull(EffectPhotoNavigation.neighbor(j,"a",1))}
    @Test fun unfinishedAndUnlinkedRecordsExcluded(){val j=listOf(job("a",1),job("b",2,ExportJobState.RUNNING),job("c",3,uri=null),job("d",4));assertEquals("a",EffectPhotoNavigation.neighbor(j,"d",1)?.id)}
    @Test fun missingOriginalStillAllowsRecovery(){val j=listOf(job("a",1),job("b",2));assertNull(j[0].originalUri);assertEquals("a",EffectPhotoNavigation.neighbor(j,"b",1)?.id)}
    @Test fun detachedOrDeletedRecordDoesNotJump(){val j=listOf(job("a",1));assertNull(EffectPhotoNavigation.neighbor(j,null,1));assertNull(EffectPhotoNavigation.neighbor(j,"deleted",1))}
    @Test fun onePhotoAndInvalidDirectionsAreSafe(){val j=listOf(job("a",1));assertNull(EffectPhotoNavigation.neighbor(j,"a",1));assertNull(EffectPhotoNavigation.neighbor(j,"a",0));assertNull(EffectPhotoNavigation.neighbor(j,"a",2))}
    @Test fun horizontalSwipeDirections(){assertEquals(1,EffectPhotoNavigation.swipeDirection(-90f,10f,64f));assertEquals(-1,EffectPhotoNavigation.swipeDirection(90f,10f,64f))}
    @Test fun tapShortDragAndVerticalDoNotNavigate(){assertEquals(0,EffectPhotoNavigation.swipeDirection(0f,0f,64f));assertEquals(0,EffectPhotoNavigation.swipeDirection(63f,0f,64f));assertEquals(0,EffectPhotoNavigation.swipeDirection(90f,100f,64f))}
    @Test fun firstEntryPlaysOnce(){val p=EffectPreviewPlayback(true);assertEquals(0L,p.elapsed(50,50,3000));assertEquals(120L,p.elapsed(170,50,3000));p.rendered(3000,3000);assertTrue(p.stopped);assertEquals(3000L,p.elapsed(0,0,3000))}
    @Test fun swipedPhotoRemainsFinalUntilManualReplay(){val p=EffectPreviewPlayback(false);assertEquals(3000L,p.elapsed(20,20,3000));assertTrue(p.stopped);p.replay();assertEquals(0L,p.elapsed(20,20,3000));assertFalse(p.stopped)}
    @Test fun returningToFirstPhotoIsAlsoStatic(){val first=EffectPreviewPlayback(true);first.rendered(3000,3000);val next=EffectPreviewPlayback(false);val back=EffectPreviewPlayback(false);assertTrue(next.stopped);assertTrue(back.stopped);assertEquals(3000L,back.elapsed(100,100,3000))}
}
