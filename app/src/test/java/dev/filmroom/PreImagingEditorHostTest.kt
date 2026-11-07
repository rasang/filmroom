package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.engine.PreviewPresentationContext
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Robolectric's host FD decoder is unsupported. Bridge only File-source creation to
 * its existing ByteBuffer decoder, using the same real encoded bytes. This is not
 * Android-device decoder validation; repository reopening and migration stay real. */
@org.robolectric.annotation.Implements(android.graphics.ImageDecoder::class,minSdk=28)
class RestoreFileSourceImageDecoderShadow:org.robolectric.shadows.ShadowImageDecoder() {
    companion object {
        @JvmStatic
        @org.robolectric.annotation.Implementation
        fun createSource(file:File):android.graphics.ImageDecoder.Source =
            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(file.readBytes()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreImagingEditorHostTest {
    private fun TestScope.await(diagnostics:()->String={""},check:()->Boolean) {
        repeat(1000){advanceUntilIdle();if(check())return;Thread.sleep(5)}
        error("Preview did not settle: ${diagnostics()}")
    }
    @Test fun deferredPhotoViewPreservesCommitAcrossCoordinateRebind() {
        val view=dev.filmroom.ui.PhotoView(RuntimeEnvironment.getApplication())
        val source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        val border=BorderSnapshot.fixed135("kodak_portra_400","fixture",96,64)
        val changed=CompositionTransform(.55,.45,1.8)
        val events=mutableListOf<Pair<CompositionTransform,Boolean>>()
        val callback:(CompositionTransform,Boolean)->Unit={value,commit->events.add(value to commit)}
        try {
            view.bindComposition(border,source,border.composition,true,null,callback)
            dev.filmroom.ui.PhotoView::class.java.getDeclaredMethod("editComposition",CompositionTransform::class.java)
                .apply{isAccessible=true}.invoke(view,changed)
            // A delayed bind of the old coordinates must not erase the local gesture.
            view.bindComposition(border,source,border.composition,true,null,callback)
            assertTrue(view.isCompositionEditing);assertFalse(view.isLiveComposition)
            view.finishCompositionGesture()
            assertEquals(listOf(changed to false,changed to true),events)
        } finally {view.close();source.recycle()}
    }
    @Suppress("UNCHECKED_CAST")
    @Config(shadows=[RestoreFileSourceImageDecoderShadow::class])
    @Test fun restoresLegacyEnabledSessionIntoPreImagingWithoutRewritingSavedState()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java))
            .setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply{
                availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
            })
        val preferences=dev.filmroom.model.EditorPreferences(app)
        val oldRestore=preferences.restoreLastPhoto
        val session=app.getSharedPreferences("local-session",0)
        val sourceFile=File(app.cacheDir,"photo-sources/source-PreImagingRestore.image")
        sourceFile.parentFile!!.mkdirs()
        // Real encoded PNG, independent of the legacy Android Bitmap.compress shadow.
        sourceFile.writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAIAAABqVuVZAAAAnElEQVR4nO3QQRHAIADAMEDZpCAF6VOw65M9EgW9zmefwbd1O+DvDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFB4AaONAaB+OOyhAAAAAElFTkSuQmCC"))
        val legacy=EditorState(filmBorder=true,filmBorderStage=0)
        // Model an actual pre-feature session with no stage key at all.
        val persisted=org.json.JSONObject(legacy.json()).apply{remove("filmBorderStage")}.toString()
        val presetFile=File(app.filesDir,"saved-presets.json")
        val presetStore=dev.filmroom.presets.SavedPresetStore(presetFile)
        val preset=presetStore.save("Legacy restore fixture",legacy)
        val presetBytes=presetFile.readBytes()
        val queueRoot=File(app.filesDir,"export-jobs")
        val store=dev.filmroom.export.ExportJobStore(queueRoot)
        val frozenBorder=BorderSnapshot.fixed135(legacy.frameStock,"fixture",96,64)
        val queued=dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),1,legacy,96,64,
            dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.PAUSED,border=frozenBorder)
        store.directory(queued.id).mkdirs();store.update(queued)
        val manifest=File(store.directory(queued.id),"manifest.json")
        val queueBytes=manifest.readBytes()
        session.edit().clear().putString("source",sourceFile.name).putString("name","restore.png")
            .putString("parameters",persisted).commit()
        preferences.restoreLastPhoto=true
        var model:EditorViewModel?=null
        try {
            // Exercise the real decoder before VM's user-facing restore handler can
            // replace a concrete decoder error with the generic expired-photo message.
            val probe=try {dev.filmroom.io.PhotoRepository(app).reopen(sourceFile.name,"restore.png",EditorViewModel.ENGINE_EDGE)}
                catch(t:Throwable){throw AssertionError("Real PNG reopen failed before ViewModel restore; bytes=${sourceFile.length()}",t)}
            try {assertEquals(96,probe.source.originalWidth);assertEquals(64,probe.source.originalHeight)}
            finally {probe.preview.recycle()}
            val restored=EditorViewModel(app);model=restored
            // Host lacks Android JNI. Inject only the existing preview hook and unavailable
            // status before the scheduled initializer runs; reopen and migration remain real.
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}
                .get(restored) as MutableStateFlow<EditorUiState>
            state.value=state.value.copy(engineUnavailable=null)
            var calls=0
            restored.renderPreview={bitmap,params,photo,settled->
                calls++;assertTrue(params.preImagingBorder);assertFalse(settled)
                assertEquals(sourceFile.name,photo!!.cacheKey)
                val border=BorderSnapshot.fixed135(params.frameStock,"fixture",bitmap.width,bitmap.height,
                    photo.originalWidth,photo.originalHeight,params.composition)
                currentCoroutineContext()[PreviewPresentationContext]!!.border=border
                Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
            }
            await(diagnostics={
                val ui=state.value
                "photo=${ui.photo?.cacheKey}; parameters=${ui.parameters}; startup=${ui.startupReady}; busy=${ui.busy}; message=${ui.message}; calls=$calls; engine=${ui.engineUnavailable}; updating=${ui.updating}; refining=${ui.previewRefining}; sourceBytes=${sourceFile.length()}; memory=${dev.filmroom.engine.NativeWorkReservations.snapshot(app)}"
            }){state.value.canExport && !state.value.previewRefining}
            assertEquals(1,calls)
            assertEquals(legacy.copy(filmBorderStage=1),state.value.parameters)
            assertEquals(state.value.parameters,state.value.renderedParameters)
            assertEquals(sourceFile.name,state.value.photo!!.cacheKey)
            // Restore/render alone does not call saveSession; later user edits may save live1.
            assertEquals(persisted,session.getString("parameters",null))
            assertArrayEquals(presetBytes,presetFile.readBytes())
            assertEquals(legacy,presetStore.list().single{it.id==preset.id}.parameters())
            assertArrayEquals(queueBytes,manifest.readBytes())
            assertEquals(legacy,dev.filmroom.export.ExportJobStore(queueRoot).get(queued.id)!!.parameters)
        } finally {
            model?.cancel();model?.viewModelScope?.cancel()
            preferences.restoreLastPhoto=oldRestore;session.edit().clear().commit()
            sourceFile.delete();File(sourceFile.path+".imprint-time").delete()
            presetStore.delete(preset.id);store.directory(queued.id).deleteRecursively()
            Dispatchers.resetMain()
        }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun preImagingPublishesWholeFrameRefinementWithoutLegacyOverlay()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val model=EditorViewModel(app)
        try {
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(model) as? Job)?.isCompleted==true}
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
            state.value=EditorUiState(photo=PhotoSource(File("unused"),"whole-frame","image/jpeg",6000,4000,0),original=Bitmap.createBitmap(768,512,Bitmap.Config.ARGB_8888))
            model.detailPreviewEdge={768}
            val calls=mutableListOf<Boolean>()
            model.renderPreview={_,params,photo,settled->
                calls+=settled
                // Native contract mocked only at existing bridge: dimensions/context
                // retain the exact full frame, not a post-imaging rail overlay.
                val border=BorderSnapshot.fixed135(params.frameStock,"fixture",if(settled)1200 else 400,if(settled)800 else 267,photo!!.originalWidth,photo.originalHeight,params.composition)
                currentCoroutineContext()[PreviewPresentationContext]!!.border=border
                Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
            }
            model.parameters(EditorState(filmBorder=true,filmBorderStage=1))
            await{state.value.canExport && !state.value.previewRefining}
            assertEquals(listOf(false,true),calls)
            assertTrue(maxOf(state.value.rendered!!.width,state.value.rendered!!.height)>768)
            assertEquals(state.value.presentation!!.outputWidth,state.value.rendered!!.width)
            assertSame(state.value.rendered,state.value.compositionSource)
            assertEquals(0,model.exactBorderCompositionCalls)
        }finally{model.cancel();model.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun preImagingKeepsFinalFrameDuringMovesAndRendersGeometryOnCommit()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val model=EditorViewModel(app)
        try {
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(model) as? Job)?.isCompleted==true}
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
            val source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            state.value=EditorUiState(photo=PhotoSource(File("unused"),"pre-imaging-fixture","image/jpeg",96,64,0),original=source)
            var calls=0
            var blockNext:CompletableDeferred<Unit>?=null
            model.renderPreview={_,params,_,settled->
                calls++
                blockNext?.also{blockNext=null}?.await()
                assertFalse("Unsupported refinement must not run",settled)
                val border=BorderSnapshot.fixed135("kodak_portra_400","fixture",96,64,96,64,params.composition)
                currentCoroutineContext()[PreviewPresentationContext]!!.border=border
                Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
            }
            model.parameters(EditorState(filmBorder=true,filmBorderStage=1))
            await{state.value.canExport && !state.value.previewRefining}
            assertEquals(1,calls);assertEquals(0,model.exactBorderCompositionCalls)
            assertNull(state.value.interactiveBorder);assertSame(state.value.rendered,state.value.compositionSource)
            assertNotSame(source,state.value.compositionSource)
            val before=state.value.rendered
            model.beginComposition()
            val transform=CompositionTransform(.55,.45,1.8)
            repeat(25){model.composition(transform,false);advanceUntilIdle()}
            assertEquals(1,calls);assertSame(before,state.value.rendered)
            model.composition(transform,true)
            await{state.value.renderedParameters?.composition==transform && !state.value.previewRefining}
            assertEquals(2,calls);assertEquals(0,model.exactBorderCompositionCalls)
            assertNotSame(before,state.value.rendered)
            model.confirmComposition();await{state.value.canExport}
            model.parameters(state.value.parameters.copy(negativeWhiteHoles=!state.value.parameters.negativeWhiteHoles))
            await{state.value.canExport && !state.value.previewRefining}
            assertEquals(3,calls)
            assertNull(state.value.interactiveBorder)
            // A previous UP can finish while a newer drag is active. Keep its pixels
            // undisplayed, and do not unlock export until the latest exact UP completes.
            val stable=state.value.rendered
            model.beginComposition()
            val gate=CompletableDeferred<Unit>();blockNext=gate
            model.composition(CompositionTransform(.5,.5,2.0),true)
            await{calls==4}
            val latest=CompositionTransform(.6,.4,2.5)
            model.composition(latest,false)
            gate.complete(Unit)
            val render=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}
            await{(render.get(model) as? Job)?.isCompleted!=false}
            assertSame(stable,state.value.rendered);assertFalse(state.value.canExport)
            model.composition(latest,true)
            await{state.value.renderedParameters?.composition==latest && !state.value.previewRefining}
            assertEquals(5,calls);assertNotSame(stable,state.value.rendered)
            assertSame(state.value.rendered,state.value.compositionSource)
            assertEquals(latest,state.value.presentation!!.composition)
            model.confirmComposition();await{state.value.canExport}
        } finally {model.cancel();model.viewModelScope.cancel();Dispatchers.resetMain()}
    }
    @Suppress("UNCHECKED_CAST")
    @Config(shadows=[RestoreFileSourceImageDecoderShadow::class])
    @Test fun restoresNoFilmSessionByOnlyDisablingFrameWithoutTouchingFrozenJobs()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java))
            .setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply{
                availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
            })
        val preferences=dev.filmroom.model.EditorPreferences(app)
        val oldRestore=preferences.restoreLastPhoto
        val session=app.getSharedPreferences("local-session",0)
        val sourceFile=File(app.cacheDir,"photo-sources/source-PreImagingRestore.image")
        sourceFile.parentFile!!.mkdirs()
        // Real encoded PNG, independent of the legacy Android Bitmap.compress shadow.
        sourceFile.writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABACAIAAABqVuVZAAAAnElEQVR4nO3QQRHAIADAMEDZpCAF6VOw65M9EgW9zmefwbd1O+DvDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFAwKBgUDAoGBYOCQcGgYFB4AaONAaB+OOyhAAAAAElFTkSuQmCC"))
        val legacy=EditorState(filmStock="none",paperStock="none",filmBorder=true,filmBorderStage=0,
            brightness=.75f,negativeWhiteHoles=true,composition=CompositionTransform(.55,.45,1.3))
        // Model an actual pre-feature session with no stage key at all.
        val persisted=org.json.JSONObject(legacy.json()).apply{remove("filmBorderStage")}.toString()
        val presetFile=File(app.filesDir,"saved-presets.json")
        val presetStore=dev.filmroom.presets.SavedPresetStore(presetFile)
        val preset=presetStore.save("Legacy restore fixture",legacy)
        val presetBytes=presetFile.readBytes()
        val queueRoot=File(app.filesDir,"export-jobs")
        val store=dev.filmroom.export.ExportJobStore(queueRoot)
        val frozenParameters=EditorState(filmBorder=true,filmBorderStage=0)
        val frozenBorder=BorderSnapshot.fixed135(frozenParameters.frameStock,"fixture",96,64)
        val queued=dev.filmroom.export.ExportJob(dev.filmroom.export.ExportJobStore.newId(),1,frozenParameters,96,64,
            dev.filmroom.io.ExportFormat.PNG,95,state=dev.filmroom.export.ExportJobState.PAUSED,border=frozenBorder)
        store.directory(queued.id).mkdirs();store.update(queued)
        val manifest=File(store.directory(queued.id),"manifest.json")
        val queueBytes=manifest.readBytes()
        session.edit().clear().putString("source",sourceFile.name).putString("name","restore.png")
            .putString("parameters",persisted).commit()
        preferences.restoreLastPhoto=true
        var model:EditorViewModel?=null
        try {
            // Exercise the real decoder before VM's user-facing restore handler can
            // replace a concrete decoder error with the generic expired-photo message.
            val probe=try {dev.filmroom.io.PhotoRepository(app).reopen(sourceFile.name,"restore.png",EditorViewModel.ENGINE_EDGE)}
                catch(t:Throwable){throw AssertionError("Real PNG reopen failed before ViewModel restore; bytes=${sourceFile.length()}",t)}
            try {assertEquals(96,probe.source.originalWidth);assertEquals(64,probe.source.originalHeight)}
            finally {probe.preview.recycle()}
            val restored=EditorViewModel(app);model=restored
            // Host lacks Android JNI. Inject only the existing preview hook and unavailable
            // status before the scheduled initializer runs; reopen and migration remain real.
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}
                .get(restored) as MutableStateFlow<EditorUiState>
            state.value=state.value.copy(engineUnavailable=null)
            var calls=0
            restored.renderPreview={bitmap,params,photo,settled->
                calls++;assertFalse(params.preImagingBorder);assertFalse(params.filmBorder);assertFalse(settled)
                assertEquals(sourceFile.name,photo!!.cacheKey)
                assertEquals(legacy.copy(filmBorder=false),params)
                bitmap.copy(Bitmap.Config.ARGB_8888,false)
            }
            await(diagnostics={
                val ui=state.value
                "photo=${ui.photo?.cacheKey}; parameters=${ui.parameters}; startup=${ui.startupReady}; busy=${ui.busy}; message=${ui.message}; calls=$calls; engine=${ui.engineUnavailable}; updating=${ui.updating}; refining=${ui.previewRefining}; sourceBytes=${sourceFile.length()}; memory=${dev.filmroom.engine.NativeWorkReservations.snapshot(app)}"
            }){state.value.canExport && !state.value.previewRefining}
            assertEquals(1,calls)
            assertEquals(legacy.copy(filmBorder=false),state.value.parameters)
            assertEquals(state.value.parameters,state.value.renderedParameters)
            assertEquals(sourceFile.name,state.value.photo!!.cacheKey)
            // Restore/render alone does not call saveSession; later user edits may save live1.
            assertEquals(persisted,session.getString("parameters",null))
            assertArrayEquals(presetBytes,presetFile.readBytes())
            assertEquals(legacy,presetStore.list().single{it.id==preset.id}.parameters())
            assertArrayEquals(queueBytes,manifest.readBytes())
            assertEquals(frozenParameters,dev.filmroom.export.ExportJobStore(queueRoot).get(queued.id)!!.parameters)
            assertEquals(dev.filmroom.export.ExportJobState.PAUSED,store.get(queued.id)!!.state)
        } finally {
            model?.cancel();model?.viewModelScope?.cancel()
            preferences.restoreLastPhoto=oldRestore;session.edit().clear().commit()
            sourceFile.delete();File(sourceFile.path+".imprint-time").delete()
            presetStore.delete(preset.id);store.directory(queued.id).deleteRecursively()
            Dispatchers.resetMain()
        }
    }
    @Test fun noFilmLiveRepairOnlyClearsFrameForEitherStage() {
        for(stage in 0..1) {
            val persisted=EditorState(filmStock="none",paperStock="none",filmBorder=true,filmBorderStage=stage,
                negativeWhiteHoles=true,brightness=.75f,composition=CompositionTransform(.55,.45,1.3))
            assertEquals(persisted,EditorState.fromJson(persisted.json()))
            assertEquals(persisted.copy(filmBorder=false),EditorState.fromJson(persisted.json()).forLiveSessionRestore())
            val unframed=persisted.copy(filmBorder=false)
            assertEquals(unframed,unframed.forLiveSessionRestore())
        }
    }
}
