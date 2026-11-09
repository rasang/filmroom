package dev.filmroom.effects

import android.graphics.Bitmap
import android.os.Looper
import dev.filmroom.export.ExportJob
import dev.filmroom.model.EditorState
import dev.filmroom.io.ExportFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectEntryHandoffTest {
    private fun row(id:String="a")=ExportJob(id,1,EditorState(),20,30,ExportFormat.PNG,95,originalUri="content://media/source",mediaUri="content://media/result")
    private fun photo(record:ExportJob=row()):PreparedEffectPhoto {
        val b=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        return PreparedEffectPhoto(record,b,b,null,EffectCameraReadout(),null)
    }
    @Test fun consumesOnlyOnceAndTransfersBitmapOwnership(){
        val p=photo();val token=EffectEntryHandoff.put(p)
        assertSame(p,EffectEntryHandoff.take(token,p.record));assertNull(EffectEntryHandoff.take(token,p.record))
        assertFalse(p.input.isRecycled);p.close();assertTrue(p.input.isRecycled)
    }
    @Test fun staleUriDiscardsRatherThanShowingWrongPhoto(){
        val p=photo();val token=EffectEntryHandoff.put(p)
        assertNull(EffectEntryHandoff.take(token,p.record.copy(originalUri="content://media/new")))
        assertTrue(p.input.isRecycled)
    }
    @Test fun changedPresentationOrDimensionsRejectsStalePreparedContent(){
        listOf<(ExportJob)->ExportJob>(
            {it.copy(parameters=it.parameters.copy(filmStock="different"))},
            {it.copy(width=21)},
            {it.copy(state=dev.filmroom.export.ExportJobState.FAILED)}
        ).forEach { change ->
            val p=photo();val token=EffectEntryHandoff.put(p)
            assertNull(EffectEntryHandoff.take(token,change(p.record)))
            assertTrue(p.input.isRecycled)
        }
    }
    @Test fun replacementRecyclesPreviousAndStaleCancellationDoesNotDiscardNew(){
        val old=photo();val a=EffectEntryHandoff.put(old);val p=photo(row("b"));val b=EffectEntryHandoff.put(p)
        assertTrue(old.input.isRecycled);EffectEntryHandoff.discard(a)
        assertSame(p,EffectEntryHandoff.take(b,p.record));p.close()
    }
    @Test fun expiryReleasesUnconsumedPhoto(){
        val p=photo();val token=EffectEntryHandoff.put(p)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertTrue(p.input.isRecycled);assertNull(EffectEntryHandoff.take(token,p.record))
    }
    @Test fun processDeathOrUnknownTokenFallsBackWithoutWrongPhoto(){
        val p=photo();val token=EffectEntryHandoff.put(p)
        assertNull(EffectEntryHandoff.take("different",p.record));assertFalse(p.input.isRecycled)
        EffectEntryHandoff.discard(token);assertTrue(p.input.isRecycled)
    }
}
