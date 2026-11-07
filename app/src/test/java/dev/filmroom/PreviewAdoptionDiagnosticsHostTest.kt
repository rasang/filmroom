package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.diagnostics.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreviewAdoptionDiagnosticsHostTest {
    private data class Seen(val record:PreviewAdoptionRecord,val displayed:Bitmap?,val parameters:EditorState?)
    private class Fixture(val vm:EditorViewModel,val state:MutableStateFlow<EditorUiState>) {
        val seen=java.util.concurrent.CopyOnWriteArrayList<Seen>()
        val produced=java.util.concurrent.CopyOnWriteArrayList<Bitmap>()
        fun bitmap(w:Int=96,h:Int=64)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{produced+=it}
        suspend fun close(){
            vm.cancel()
            (EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(vm) as? Job)?.cancelAndJoin()
            vm.viewModelScope.cancel()
            (produced+listOfNotNull(state.value.original,state.value.rendered,state.value.compositionSource)).distinct().forEach{if(!it.isRecycled)it.recycle()}
        }
    }
    private fun TestScope.waitFor(predicate:()->Boolean){
        repeat(1000){runCurrent();if(predicate())return;advanceTimeBy(10);Thread.sleep(2)}
        error("Timed out waiting for preview fixture")
    }
    @Suppress("UNCHECKED_CAST") private fun TestScope.fixture():Fixture {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val vm=EditorViewModel(app)
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        waitFor{(operation.get(vm) as? Job)?.isCompleted==true}
        val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val f=Fixture(vm,state)
        state.value=EditorUiState(photo=PhotoSource(File("private-source-A.nef"),"PRIVATE_NAME_A","image/x-nikon-nef",7360,4912,0),original=f.bitmap())
        vm.detailPreviewEdge={96}
        vm.previewAdoptionSink={r->f.seen+=Seen(r,state.value.rendered,state.value.renderedParameters)}
        vm.renderPreview={_,_,_,_->f.bitmap()}
        return f
    }
    @Test fun quickAndRefinedRecordsFollowActualStateAdoption()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f;h.vm.detailPreviewEdge={96}
            h.vm.renderPreview={_,_,_,detail->h.bitmap(if(detail)96 else 48,if(detail)64 else 32)}
            val parameters=EditorState(filmGamma=1.46f)
            h.vm.parameters(parameters);waitFor{h.seen.size==2 && !h.state.value.previewRefining}
            assertEquals(2,h.seen.size)
            assertEquals(listOf(PreviewFrameKind.QUICK,PreviewFrameKind.REFINED),h.seen.map{it.record.context.kind})
            h.seen.forEach {assertEquals(PreviewDisposition.ADOPTED,it.record.disposition);assertTrue(it.record.nativeCompleted);assertEquals(parameters,it.parameters);assertNotNull(it.displayed)}
            assertEquals(listOf(48,96),h.seen.map{it.displayed!!.width})
            assertEquals(1,h.seen.map{it.record.context.generation}.distinct().size)
            assertEquals(1,h.seen.map{it.record.context.sourceToken}.distinct().size)
            val refined=h.seen.last().record.context
            h.vm.retry();waitFor{h.seen.size==3 && !h.state.value.previewRefining}
            val cached=h.seen.last().record.context
            assertEquals(PreviewFrameKind.CACHED,cached.kind)
            assertEquals(PreviewFrameKind.REFINED,cached.originKind)
            assertEquals(refined.generation,cached.originGeneration)
            assertNotEquals(refined.generation,cached.generation)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun completedObsoleteOldGammaIsNotReportedAsAdoptedAfterReset()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var entered=false;var obsolete:Bitmap?=null
            h.vm.renderPreview={_,p,_,_->
                if(p.filmGamma!=1f){entered=true;withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}
                else h.bitmap()
            }
            h.vm.parameters(EditorState(filmGamma=1.46f),commit=false);advanceTimeBy(121);runCurrent();assertTrue(entered)
            h.vm.reset();runCurrent()
            // The latest reset waits for the single in-flight native call to release ownership.
            assertTrue(h.seen.none{it.record.disposition==PreviewDisposition.ADOPTED})
            gate.complete(Unit)
            waitFor{h.state.value.renderedParameters?.filmGamma==1f && h.seen.size==2}
            assertEquals(1f,h.state.value.renderedParameters!!.filmGamma)
            assertNotSame(obsolete,h.state.value.rendered);assertTrue(obsolete!!.isRecycled)
            val old=h.seen.single{it.record.context.scienceFingerprint==PreviewDiagnosticContext.fingerprint(EditorState(filmGamma=1.46f))}
            assertEquals(PreviewDisposition.STALE_GENERATION,old.record.disposition)
            assertTrue("Fake native completed, but its UI adoption did not",old.record.nativeCompleted)
            assertEquals(1,h.seen.count{it.record.disposition==PreviewDisposition.ADOPTED})
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }
    @Test fun replacementPhotoWithSameScienceGetsNewTokenAndFreshOutput()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f;var calls=0
            h.vm.renderPreview={_,_,_,_->calls++;h.bitmap()}
            h.vm.retry();waitFor{h.seen.size==1 && !h.state.value.previewRefining}
            val old=h.state.value.rendered;val first=h.seen.single().record
            // Isolate the post-import identity boundary; repository import itself is
            // separately exercised by PresetResetSettingsHostTest.
            h.state.value=EditorUiState(photo=PhotoSource(File("private-source-B.nef"),"PRIVATE_NAME_B","image/x-nikon-nef",7360,4912,0),original=h.bitmap())
            h.vm.retry();waitFor{h.seen.size==2 && !h.state.value.previewRefining}
            val second=h.seen.last().record
            assertEquals(2,calls);assertNotSame(old,h.state.value.rendered)
            assertNotEquals(first.context.sourceToken,second.context.sourceToken)
            assertEquals(first.context.scienceFingerprint,second.context.scienceFingerprint)
            assertEquals(PreviewDisposition.ADOPTED,second.disposition)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun cachedPresentationIsNotMisreportedAsNewNativeCompletion()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f;var calls=0
            h.vm.renderPreview={_,_,_,_->calls++;h.bitmap()}
            h.vm.retry();waitFor{h.seen.size==1 && !h.state.value.previewRefining}
            val original=h.state.value.rendered
            h.vm.retry();waitFor{h.seen.size==2 && !h.state.value.previewRefining}
            assertEquals(1,calls);assertEquals(2,h.seen.size);assertSame(original,h.state.value.rendered)
            val cached=h.seen.last().record
            assertEquals(PreviewFrameKind.CACHED,cached.context.kind)
            assertEquals(PreviewDisposition.ADOPTED,cached.disposition);assertFalse(cached.nativeCompleted)
            val first=h.seen.first().record.context
            assertEquals(first.generation,cached.context.originGeneration)
            assertNotEquals(first.generation,cached.context.generation)
            assertEquals(PreviewFrameKind.QUICK,cached.context.originKind)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    private suspend fun TestScope.importAfterBwPrefix(resetOnImport:Boolean) {
        val h=fixture();val gate=CompletableDeferred<Unit>();val app=RuntimeEnvironment.getApplication()
        val preferences=dev.filmroom.model.EditorPreferences(app)
        val previousReset=preferences.resetOnImport
        var imported:PhotoSource?=null
        try {
            preferences.resetOnImport=resetOnImport
            org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
            val prefix=listOf(
                EditorState(filmStock="kodak_doublex",paperStock="kodak_2302"),
                EditorState(filmStock="kodak_trix",paperStock="kodak_2383"),
                EditorState(filmStock="kodak_doublex",paperStock="kodak_2302",filmGamma=1.46f,brightness=.5f))
            for(p in prefix+prefix){
                val before=h.seen.size;h.vm.parameters(p,commit=false)
                waitFor{h.seen.size>before && !h.state.value.previewRefining}
                assertEquals(p,h.state.value.renderedParameters)
            }
            val oldPhoto=checkNotNull(h.state.value.photo)
            val oldToken=h.seen.last().record.context.sourceToken
            val pending=prefix.last().copy(brightness=1.5f)
            var entered=false;var blockOnce=true;var obsolete:Bitmap?=null
            h.vm.renderPreview={_,_,_,_->
                if(blockOnce){blockOnce=false;entered=true;withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}
                else h.bitmap()
            }
            h.vm.parameters(pending,commit=false);advanceTimeBy(121);runCurrent();assertTrue(entered)
            val oldJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(h.vm) as Job
            val image=h.bitmap(72,48).apply{eraseColor(android.graphics.Color.GREEN)}
            val input=File.createTempFile("synthetic-prefix-", ".png",app.cacheDir)
            image.outputStream(input)
            val uri=android.net.Uri.parse("content://preview-prefix-test/new-image.png")
            org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,input.inputStream())
            val beforeImport=h.seen.size
            h.vm.importPhoto(uri)
            val importOperation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            waitFor{
                if((importOperation.get(h.vm) as? Job)?.isCompleted==true && !oldJob.isCancelled)
                    error("Synthetic PNG import ended before draining old render: ${h.state.value.message}")
                oldJob.isCancelled
            }
            assertSame("New source cannot be adopted before old noncooperative work drains",oldPhoto,h.state.value.photo)
            assertEquals(beforeImport,h.seen.size)
            gate.complete(Unit)
            waitFor{h.state.value.photo!==oldPhoto && h.state.value.busy==null}
            imported=h.state.value.photo
            assertTrue(obsolete!!.isRecycled)
            val params=h.state.value.parameters
            assertEquals(pending.filmStock,params.filmStock)
            assertEquals(if(resetOnImport)1f else 1.46f,params.filmGamma)
            assertEquals(if(resetOnImport)0f else 1.5f,params.brightness)
            if(!resetOnImport)assertEquals(pending.paperStock,params.paperStock)
            else {
                val entries=org.json.JSONObject(app.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
                val entry=(0 until entries.length()).map{entries.getJSONObject(it)}.single{it.getString("id")==pending.filmStock}
                assertEquals(entry.getString("defaultPaper"),params.paperStock)
                assertEquals(entry.getString("defaultRoute"),params.outputRoute)
            }
            assertEquals(dev.filmroom.model.CropSettings(),params.crop)
            assertEquals(dev.filmroom.model.CompositionTransform(),params.composition)
            // Host fixture has a fake renderPreview, not a native engine. Real
            // repository import may restore its unavailable flag; clear only
            // that fixture flag and retry after validating the true import state.
            if(h.state.value.engineUnavailable!=null){h.state.value=h.state.value.copy(engineUnavailable=null);h.vm.retry()}
            waitFor{h.state.value.rendered!=null && h.state.value.renderedParameters==params && !h.state.value.previewRefining}
            val fresh=h.seen.last{it.record.disposition==PreviewDisposition.ADOPTED}.record
            assertNotEquals(oldToken,fresh.context.sourceToken)
            assertEquals(PreviewDiagnosticContext.fingerprint(params),fresh.context.scienceFingerprint)
            val tail=h.seen.drop(beforeImport)
            assertTrue(tail.any{it.record.disposition==PreviewDisposition.CANCELLED && it.record.context.sourceToken==oldToken})
            assertTrue(tail.filter{it.record.disposition==PreviewDisposition.ADOPTED}.all{it.record.context.sourceToken!=oldToken})
            input.delete()
        } finally {
            gate.complete(Unit);h.close();preferences.resetOnImport=previousReset
            imported?.let{dev.filmroom.io.PhotoRepository(app).discard(it)}
            app.getSharedPreferences("local-session",0).edit().clear().commit()
        }
    }
    private fun Bitmap.outputStream(target:File){target.outputStream().use{assertTrue(compress(Bitmap.CompressFormat.PNG,100,it))}}
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun realPngImportAfterBwPrefixKeepsScienceWhenResetDisabled()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try{importAfterBwPrefix(false)}finally{Dispatchers.resetMain()}
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun realPngImportAfterBwPrefixResetsScienceWhenResetEnabled()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try{importAfterBwPrefix(true)}finally{Dispatchers.resetMain()}
    }
    @Test fun modelAdoptionAndExplicitViewBindingAreDistinctEvents()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.retry()
            waitFor{h.seen.size==1 && !h.state.value.previewRefining}
            val adopted=h.seen.single().record
            assertEquals(PreviewDisposition.ADOPTED,adopted.disposition)
            val boundContext=checkNotNull(h.state.value.renderedDiagnosticContext)
            assertEquals(adopted.context.sourceToken,boundContext.sourceToken)
            assertEquals(adopted.context.generation,boundContext.generation)
            // Simulates only the callback contract. Actual MainActivity code must
            // call this *after* assigning PhotoView.bitmap; no scanout claim here.
            h.vm.previewBound(boundContext)
            waitFor{h.seen.size==2}
            val bound=h.seen.last().record
            assertEquals(PreviewDisposition.BOUND,bound.disposition)
            assertSame(boundContext,bound.context)
            assertFalse(bound.nativeCompleted)
            assertFalse(bound.safeInfo().fields().containsKey("previewNativeCompleted"))
            assertEquals(1,h.seen.count{it.record.disposition==PreviewDisposition.ADOPTED})
            assertSame(h.state.value.rendered,h.seen.first().displayed)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun diagnosticSinkFailureCannotUndoAnAdoptedFrame()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.previewAdoptionSink={throw IllegalStateException("synthetic diagnostic writer failure")}
            h.vm.retry()
            waitFor{h.state.value.rendered!=null && !h.state.value.previewRefining}
            assertTrue(h.state.value.canExport);assertNull(h.state.value.message)
            assertFalse(h.state.value.rendered!!.isRecycled)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun renderCallbackFailureKeepsSafeStageAndReasonWithoutInventedDuration()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            val secret="content://private/source.jpg GPS=22.3 token"
            h.vm.renderPreview={_,_,_,_->throw java.io.IOException(secret)}
            h.vm.retry()
            waitFor{h.seen.size==1 && !h.state.value.updating}
            val record=h.seen.single().record
            assertEquals(PreviewDisposition.FAILED,record.disposition)
            assertFalse(record.nativeCompleted)
            val info=record.safeInfo()
            assertEquals(PreviewFailureStage.RENDER_REQUEST,info.previewFailureStage)
            assertEquals(PreviewExceptionType.IO_EXCEPTION,info.previewExceptionType)
            assertEquals(PreviewFailureReason.IO_FAILED,info.previewFailureReason)
            assertNull(info.durationMs)
            assertFalse(info.fields().toString().contains(secret))
            assertEquals(info,SafeInfo.parse(info.fields()))
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun diagnosticsContainOnlyTypedCorrelationNotSourceOrPixelIdentity(){
        val context=PreviewDiagnosticContext(7,9,PreviewDiagnosticContext.fingerprint(EditorState(filmGamma=1.46f)),PreviewFrameKind.QUICK)
        val fields=PreviewAdoptionRecord(context,PreviewDisposition.ADOPTED,true).safeInfo().fields()
        assertEquals(setOf("previewSourceToken","previewGeneration","previewOriginGeneration","previewScienceFingerprint","previewKind","previewOriginKind","previewDisposition","previewNativeCompleted","previewObservedElapsedMs"),fields.keys)
        assertEquals(fields,SafeInfo.parse(fields).fields())
        assertTrue(fields.getValue("previewSourceToken").toLong()>0)
        assertTrue(fields.getValue("previewScienceFingerprint").toLong() in 0..0x001fffffffffffffL)
        assertTrue(fields.getValue("previewObservedElapsedMs").toLong()>=0)
        assertFalse(fields.toString().contains("PRIVATE_NAME"));assertFalse(fields.toString().contains(".nef"));assertFalse(fields.toString().contains("content://"))
        assertTrue(fields.keys.none{it.contains("pixel",true)||it.contains("uri",true)||it.contains("path",true)||it.contains("file",true)})
        assertNotEquals(context.scienceFingerprint,PreviewDiagnosticContext.fingerprint(EditorState(filmGamma=1f)))
    }
    @Test fun presentationDiagnosticsAreRateBoundedAndReportSuppressedCount(){
        val gate=PreviewDiagnosticRateGate()
        repeat(8){assertEquals(0L,gate.accept(100))}
        repeat(100){assertNull(gate.accept(999))}
        assertEquals(100L,gate.accept(1000))
        repeat(7){assertEquals(0L,gate.accept(1999))}
        assertNull(gate.accept(1999))
        assertEquals(1L,gate.accept(2000))
        val hostile=mapOf("previewSourceToken" to "content://private/photo.nef", "previewGeneration" to "-1", "previewScienceFingerprint" to "9223372036854775808", "previewKind" to "PRIVATE_NAME", "previewDisposition" to "EXIF_GPS", "previewNativeCompleted" to "secret", "previewSuppressed" to "1000001")
        assertTrue(SafeInfo.parse(hostile).fields().isEmpty())
    }
}
