package dev.filmroom

import dev.filmroom.lifecycle.StartupRecoveryGate
import dev.filmroom.engine.ExportDisplayProgress
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.filmroom.engine.FilmEngine
import dev.filmroom.engine.EngineException
import dev.filmroom.io.*
import dev.filmroom.model.*
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

data class EditorUiState(
    val startupReady:Boolean=true, val recoveryFailed:Boolean=false,
    val photo: PhotoSource?=null, val original: Bitmap?=null, val rendered: Bitmap?=null,
    val parameters: EditorState=EditorState(), val renderedParameters: EditorState?=null,
    val renderedDiagnosticContext:PreviewDiagnosticContext?=null,
    val compositionEditing:Boolean=false, val presentation:dev.filmroom.border.BorderSnapshot?=null,
    val compositionSource:Bitmap?=null, val interactiveBorder:Bitmap?=null,
    val cropPresentation:dev.filmroom.crop.CropSnapshot?=null,
    val busy: String?=null, val busyUi:dev.filmroom.i18n.UiMessage?=null, val updating: Boolean=false, val previewRefining:Boolean=false, val message: String?=null, val messageUi:dev.filmroom.i18n.UiMessage?=null, val messageIsDiagnostic:Boolean=false,
    val messageAttempt:Long=0,
    val engineUnavailable: String?=null, val compare: Boolean=false,
    val canUndo: Boolean=false, val canRedo: Boolean=false,
    val exporting:Boolean=false, val cancelling:Boolean=false, val exportSpaceNote:String?=null,
    val exportProgress:ExportDisplayProgress?=null,
) {
    val engineUnavailableDisplay:String? get()=engineUnavailable?.let{dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9765e80ef5)}
    val messageDisplay:String? get()=if(message==null)null else messageUi?.render() ?: if(messageIsDiagnostic)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.queue_original_details)+"\n"+message else message
    val busyDisplay:String? get()=if(busy==null)null else busyUi?.render() ?: busy
    val editable get()=startupReady && photo!=null && engineUnavailable==null && busy==null
    val canExport get()=!compositionEditing && editable && !updating && rendered!=null && renderedParameters==parameters
}

class EditorViewModel(application: Application): AndroidViewModel(application) {
    companion object { const val ENGINE_EDGE=dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE }
    // Enabled only in this isolated integration candidate; not merged or published.
    internal var localDetailEnabled=true
    internal var detailInvalidator:(()->Unit)?=null
    internal var detailProviderFactory:()->dev.filmroom.roi.EditorDetailProvider = {
        dev.filmroom.roi.RealEditorDetailProvider(dev.filmroom.engine.OwnedDetailProvider(application))
    }
    internal var borderMemorySnapshot:()->dev.filmroom.engine.NativeMemorySnapshot={dev.filmroom.engine.NativeWorkReservations.snapshot(application)}
    private val repository=PhotoRepository(application).also {
        dev.filmroom.border.BorderCatalog.initialize(application.assets){dev.filmroom.engine.NativeWorkReservations.snapshot(application)}
    }
    val availableExportFormats get()=repository.availableExportFormats
    private val engine=FilmEngine(application)
    internal var cancelPreviewEngine:()->Unit = { engine.cancel() }
    private var previewSessionHealthy=true
    private val renderingPreferences=dev.filmroom.engine.OptimizationPreferences(application)
    private var gpuRenderingChoice=BuildConfig.PERF_BUILD && renderingPreferences.snapshot().gpuRenderingEnabled
    private var renderingChoiceRefresh:Job?=null
    private val preferences=application.getSharedPreferences("local-session",0)
    private var history=EditHistory()
    private val recoveryGate=(application as? FilmroomApplication)?.recoveryGate
    private val mutable=MutableStateFlow(EditorUiState(engineUnavailable=engine.unavailable,startupReady=recoveryGate==null,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_3d05fc7cf7e4),busy=if(recoveryGate!=null)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3d05fc7cf7e4)else null))
    val state=mutable.asStateFlow()
    private var renderJob: Job?=null
    private var operationJob: Job?=null
    private var generation=0L
    private var diagnosticPhoto:java.lang.ref.WeakReference<PhotoSource>?=null
    private var diagnosticSourceToken=0L
    private var processedDiagnosticContext:PreviewDiagnosticContext?=null
    internal var previewAdoptionSink:(PreviewAdoptionRecord)->Unit = { PreviewAdoptionDiagnostics.record(it) }
    /** Called only after PhotoView.bitmap has actually been assigned; not proof of display scanout. */
    internal fun previewBound(context:PreviewDiagnosticContext){
        runCatching{previewAdoptionSink(PreviewAdoptionRecord(context,PreviewDisposition.BOUND,false))}
    }
    private var intakeCancellation:PendingExportCancellation?=null
    init {
        viewModelScope.launch {
            recoveryGate?.state?.first { recovery ->
                when(recovery){
                    StartupRecoveryGate.State.Running->mutable.value=mutable.value.copy(startupReady=false,recoveryFailed=false,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_3d05fc7cf7e4),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3d05fc7cf7e4),messageUi=null,messageIsDiagnostic=false,message=null)
                    is StartupRecoveryGate.State.Failed->mutable.value=mutable.value.copy(startupReady=false,recoveryFailed=true,busyUi=null,busy=null,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5ec95121dc94, recovery.report.failed),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5ec95121dc94, recovery.report.failed))
                    is StartupRecoveryGate.State.Ready->mutable.value=mutable.value.copy(startupReady=false,recoveryFailed=false,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b3622bcacaaf),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b3622bcacaaf),messageUi=null,messageIsDiagnostic=false,message=null)
                }
                recovery is StartupRecoveryGate.State.Ready
            }
            val report=repository.cleanupPendingExports()
            if(report.unresolved>0)mutable.value=mutable.value.copy(messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_cd6fac59a752),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_cd6fac59a752))
        val key=if(EditorPreferences(application).restoreLastPhoto)preferences.getString("source",null)else null
        operationJob=currentCoroutineContext()[Job]
        mutable.value=mutable.value.copy(startupReady=true,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b3622bcacaaf),busy=if(key!=null)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b3622bcacaaf)else null)
        if(key!=null) {
            val restoreLabel=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b3622bcacaaf)
            mutable.value=mutable.value.copy(busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_b3622bcacaaf),busy=restoreLabel)
            try {
                val p=repository.reopen(key,preferences.getString("name",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e))!!,ENGINE_EDGE,preferences.getString("rawSource",null))
                history=EditHistory(runCatching { EditorState.fromJson(preferences.getString("parameters","{}")!!) }.getOrDefault(EditorState()))
                mutable.value=mutable.value.copy(photo=p.source,original=p.preview,parameters=history.current,busyUi=null,busy=null,messageUi=null,messageIsDiagnostic=false,message=null)
                scheduleRender()
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { preferences.edit().clear().apply(); mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_063189730acc),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_063189730acc)) }
            finally { if(mutable.value.busy==restoreLabel) mutable.value=mutable.value.copy(busyUi=null,busy=null) }
        }
        }
    }
    fun retryRecovery(){if(mutable.value.recoveryFailed)recoveryGate?.retry()}
    fun importPhoto(uri: Uri) {
        compositionGestureActive=false
        if(!mutable.value.startupReady||mutable.value.busy!=null) return
        detailInvalidator?.invoke()
        // Capture the editing preference once. A failed/cancelled import never adopts this state.
        val previousParameters=mutable.value.parameters
        // A fresh photo with reset enabled also starts without a border. Toolbar preset restore
        // intentionally keeps its existing presentation policy; do not change processingPreset.
        val importParameters=(if(EditorPreferences(getApplication()).resetOnImport)processingPreset(previousParameters).copy(filmBorder=false) else previousParameters)
            .copy(composition=CompositionTransform(),crop=CropSettings())
        operationJob=viewModelScope.launch {
            mutable.value=mutable.value.copy(busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1663134e88db),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1663134e88db),messageUi=null,messageIsDiagnostic=false,message=null,compare=false,messageAttempt=mutable.value.messageAttempt+1)
            var loaded: LoadedPhoto?=null
            var adopted=false
            try {
                OperationTrace.run(Operation.IMPORT) { trace ->
                val newPhoto=repository.import(uri,ENGINE_EDGE) { stage, format ->
                    trace.mark(Stage.IMPORT_PROGRESS, SafeInfo(codecStage=stage,inputFormatHint=format))
                };loaded=newPhoto
                trace.mark(Stage.DECODE_HEADER,SafeInfo(width=newPhoto.source.originalWidth,height=newPhoto.source.originalHeight,metadataIssues=newPhoto.source.photoMetadata.issues,metadataPhase=dev.filmroom.diagnostics.MetadataIssuePhase.READ_VALIDATION,captureTimeBasis=newPhoto.source.photoMetadata.diagnosticDateBasis))
                pendingScientificPreview=null;previewSessionHealthy=false
                cancelPreviewEngine();renderJob?.cancelAndJoin();engine.clearPreviewReuse()
                val old=mutable.value.photo
                generation++;compositionBaseline=null;history=EditHistory(importParameters)
                mutable.value=EditorUiState(photo=newPhoto.source,original=newPhoto.preview,parameters=history.current,engineUnavailable=engine.unavailable,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_6dfdd1fe6561),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6dfdd1fe6561),messageUi=null,messageIsDiagnostic=false,message=null,messageAttempt=mutable.value.messageAttempt)
                adopted=true;trace.commitState()
                saveSession();old?.let { repository.discard(it) }
                }
                mutable.value=mutable.value.copy(busyUi=null,busy=null)
                scheduleRender()
            } catch(c: CancellationException) { mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=dev.filmroom.i18n.UiMessage.of(if(adopted)dev.filmroom.R.string.text_2416623f6d39 else dev.filmroom.R.string.text_d630bd89f6dd),message=if(adopted)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2416623f6d39)else dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d630bd89f6dd));throw c }
            catch(e: OutOfMemoryError) { mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_3052d04d78c2),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3052d04d78c2)) }
            catch(e: Exception) { mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=(e as? PhotoException)?.uiMessage ?: if(e !is PhotoException)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_c976e3b99689)else null,messageIsDiagnostic=e is PhotoException && e.uiMessage==null,message=(e as? PhotoException)?.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c976e3b99689)) }
            finally { if(!adopted) loaded?.let { it.preview.recycle(); repository.discard(it.source) } }
        }
    }
    private var compositionBaseline:EditorState?=null
    fun beginComposition() {
        val s=mutable.value
        if(!s.editable || s.compositionEditing)return
        // A retained old frame can still be on screen while ON/OFF presentation catches up.
        // Never route an unframed crop's arbitrary aspect into the new fixed-135 transform.
        if(s.renderedParameters?.filmBorder!=s.parameters.filmBorder)return
        if(if(s.parameters.filmBorder)s.presentation==null else s.cropPresentation==null)return
        compositionBaseline=s.parameters
        mutable.value=s.copy(compositionEditing=true,compare=false)
    }
    fun confirmComposition() {
        if(!mutable.value.compositionEditing)return
        compositionGestureActive=false
        compositionBaseline=null
        mutable.value=mutable.value.copy(compositionEditing=false)
        parameters(mutable.value.parameters,true)
    }
    fun cancelComposition() {
        val before=compositionBaseline ?: return
        compositionGestureActive=false;compositionBaseline=null
        mutable.value=mutable.value.copy(parameters=before,compositionEditing=false,compare=false)
        schedulePresentationRender()
    }
    fun setCompositionEditing(editing:Boolean) { if(editing)beginComposition() else confirmComposition() }
    fun chooseCompositionMode(mode:CropMode) {
        val s=mutable.value;val p=s.photo ?: return
        if(!s.compositionEditing)return
        val next=if(s.parameters.filmBorder){
            val portrait=when(mode){CropMode.RATIO_3_2->false;CropMode.RATIO_2_3->true;else->return}
            val aspect=dev.filmroom.border.BorderCatalog.frameAspect(s.parameters.frameStock,portrait)
            s.parameters.copy(composition=s.parameters.composition.copy(targetAspect=aspect).clamped(p.originalWidth,p.originalHeight))
        }else s.parameters.copy(crop=if(mode==CropMode.ORIGINAL)CropSettings() else s.parameters.crop.copy(mode=mode))
        compositionGestureActive=false
        mutable.value=s.copy(parameters=next,compare=false)
        schedulePresentationRender()
    }
    private var compositionGestureActive=false
    fun composition(transform:CompositionTransform,commit:Boolean=true) {
        val s=mutable.value; val photo=s.photo ?: return
        if(!s.editable)return
        val adjusted=transform.clamped(photo.originalWidth,photo.originalHeight)
        val next=if(s.parameters.filmBorder)s.parameters.copy(composition=adjusted)
            else s.parameters.copy(crop=s.parameters.crop.copy(transform=adjusted))
        if(commit){
            compositionGestureActive=false
            if(s.compositionEditing){mutable.value=s.copy(parameters=next,compare=false);schedulePresentationRender()}
            else parameters(next,true)
            return
        }
        if(!s.editable)return
        var cancelledPresentationWork=false
        // Pointer events only publish cheap edit coordinates. PhotoView draws the processed
        // source immediately under its immutable overlay; no full-frame allocation/composition.
        if(!compositionGestureActive){
            compositionGestureActive=true
            if(processedPhoto===photo && processedBitmap===s.compositionSource &&
                processedScience==previewScience(s.parameters) && !sameScienceAwaitingResult() &&
                !(gpuRenderingChoice && scientificRequest!=null && scientificRequest==activeRenderRequest)){
                renderJob?.cancel();activePresentationRequest=null;generation++;cancelledPresentationWork=true
            }
        }
        mutable.value=s.copy(parameters=next,compare=false,messageUi=null,messageIsDiagnostic=false,message=null,
            previewRefining=if(cancelledPresentationWork)false else s.previewRefining,
            updating=if(cancelledPresentationWork)false else s.updating)

    }
    fun parameters(next: EditorState, commit: Boolean=true) {
        if(!mutable.value.editable) return
        compositionGestureActive=false
        val scienceChanged=previewScience(mutable.value.parameters)!=previewScience(next)
        if(scienceChanged)detailInvalidator?.invoke()
        if(commit && !mutable.value.compositionEditing) history.update(next)
        mutable.value=mutable.value.copy(parameters=next,compositionEditing=mutable.value.compositionEditing,compare=false,canUndo=history.canUndo,canRedo=history.canRedo,messageUi=null,messageIsDiagnostic=false,message=null)
        if(commit) saveSession()
        // Confirming an already exact draft changes history, not pixels or scientific work.
        if(!scienceChanged && mutable.value.renderedParameters==next && mutable.value.rendered!=null &&
            processedBitmap!=null && processedBitmap===mutable.value.compositionSource &&
            !mutable.value.updating && !mutable.value.previewRefining && renderJob?.isActive!=true)return
        // A crop commit during an in-flight scientific render must not restart that render.
        // Its publication adopts the latest crop and catches up from the resulting photo cache.
        if(!scienceChanged && commit && sameScienceAwaitingResult()){
            pendingPresentationSettled=true;return
        }
        // Exact final presentation is scheduled only for committed crop edits.
        if(scienceChanged || commit) scheduleRender(settled=commit)
        else if(processedPhoto===mutable.value.photo && processedScience==previewScience(next))scheduleRender(settled=false,presentationOnly=true)
    }
    fun undo() { if(!mutable.value.editable)return;if(mutable.value.compositionEditing){cancelComposition();return};history.undo();applyHistory() }
    fun redo() { if(!mutable.value.editable)return;if(mutable.value.compositionEditing){cancelComposition();return};history.redo();applyHistory() }
    private fun applyHistory() { compositionGestureActive=false;if(previewScience(mutable.value.parameters)!=previewScience(history.current))detailInvalidator?.invoke();mutable.value=mutable.value.copy(parameters=history.current,compositionEditing=false,canUndo=history.canUndo,canRedo=history.canRedo,compare=false);saveSession();scheduleRender() }
    /** Confirmation is bound to the exact photo and parameter state that the user reviewed. */
    fun restorePreset(expectedPhotoKey:String,expectedParameters:EditorState):Boolean {
        val current=mutable.value
        if(!current.editable || current.compositionEditing || current.photo?.cacheKey!=expectedPhotoKey || current.parameters!=expectedParameters)return false
        val next=processingPreset(expectedParameters)
        if(next==current.parameters)return false
        parameters(next)
        return true
    }
    private fun processingPreset(parameters:EditorState):EditorState {
        val profiles=org.json.JSONObject(getApplication<Application>().assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val profile=(0 until profiles.length()).map{profiles.getJSONObject(it)}.firstOrNull{it.getString("id")==parameters.filmStock}
            ?: return parameters.processingDefaults(EditorState().paperStock,EditorState().outputRoute)
        return parameters.processingDefaults(profile.getString("defaultPaper"),profile.getString("defaultRoute"))
    }
    fun reset() { val s=mutable.value;s.photo?.let{restorePreset(it.cacheKey,s.parameters)} }

    fun compare() { if(mutable.value.rendered!=null) mutable.value=mutable.value.copy(compare=!mutable.value.compare) }
    fun clearMessage() { if(mutable.value.recoveryFailed)return; mutable.value=mutable.value.copy(messageUi=null,messageIsDiagnostic=false,message=null) }
    fun cancel() {
        compositionGestureActive=false
        detailInvalidator?.invoke()
        intakeCancellation?.cancel()
        if(mutable.value.exporting)mutable.value=mutable.value.copy(cancelling=true,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_c12aefec4f8a),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c12aefec4f8a))
        pendingScientificPreview=null;previewSessionHealthy=false
        cancelPreviewEngine();renderJob?.cancel();operationJob?.cancel()
        if(mutable.value.busy==null)mutable.value=mutable.value.copy(updating=false,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_6f25582e0d79),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6f25582e0d79))
    }
    // Leaving the editor must never cancel durable queue work (or an in-flight capture).
    fun processingScreenLeft() = Unit
    fun cancelExportForSharing() = Unit
    private fun saveSession() {
        val s=mutable.value;val p=s.photo ?: return
        preferences.edit().putString("source",p.cacheKey).putString("rawSource",p.rawCacheKey).putString("name",p.name).putString("parameters",(compositionBaseline ?: s.parameters).json()).apply()
    }
    /** Execution identity is private cache metadata, never a change to photo parameters. */
    private fun previewScience(parameters:EditorState):String = parameters.json(includePresentation=false)+
        if(BuildConfig.PERF_BUILD) "|gpu-rendering:$gpuRenderingChoice:guarded64-resident-finish-v2" else ""
    /** Settings may return to an otherwise unchanged photo. Old CPU/GPU frames must not be reused. */
    internal fun refreshGpuRenderingChoice():Boolean {
        if(!BuildConfig.PERF_BUILD)return false
        val nextChoice=renderingPreferences.snapshot().gpuRenderingEnabled
        if(nextChoice==gpuRenderingChoice)return false
        val current=mutable.value
        if(!current.startupReady || current.busy!=null || current.exporting){
            if(renderingChoiceRefresh?.isActive!=true)renderingChoiceRefresh=viewModelScope.launch {
                state.first{it.startupReady && it.busy==null && !it.exporting}
                renderingChoiceRefresh=null
                refreshGpuRenderingChoice()
            }
            return false
        }
        gpuRenderingChoice=nextChoice
        generation++
        pendingScientificPreview=null;previewSessionHealthy=false
        renderJob?.cancel();cancelPreviewEngine();engine.clearPreviewReuse()
        processedScience=null;renderingScience=null;processedDetailed=false
        activePresentationRequest=null;pendingPresentationSettled=false
        detailInvalidator?.invoke()
        // The old bitmap stays owned by the view until replacement. It is not an eligible result.
        mutable.value=current.copy(renderedParameters=null,renderedDiagnosticContext=null,updating=false,previewRefining=false)
        scheduleRender()
        return true
    }
    internal var renderPreview:suspend (Bitmap,EditorState,PhotoSource?,Boolean)->Bitmap = { source,params,photo,settled -> engine.render(source,params,"preview",photo,settled) }
    internal var borderProvider:dev.filmroom.border.BorderProvider=dev.filmroom.border.BorderCatalog
    internal var detailPreviewEdge:()->Int = { engine.previewEdge(true) }
    private var processedPhoto:PhotoSource?=null
    private var processedScience:String?=null
    private var processedBitmap:Bitmap?=null
    private var processedDetailed=false
    private var interactiveBorderKey:String?=null
    private var interactiveBorderCache:Bitmap?=null
    internal var exactBorderCompositionCalls=0; private set
    internal var interactiveBorderBuildCalls=0; private set
    private var renderingScience:String?=null
    private var renderingPhoto:PhotoSource?=null
    private var scientificRequest:Long?=null
    private var activeRenderRequest:Long?=null
    /** Main-thread slot: native work owns its frame until it seals; only the latest edit waits. */
    private data class PendingScientificPreview(val owner:Long,val photo:PhotoSource?,val parameters:EditorState,val settled:Boolean,val presentationOnly:Boolean)
    private var pendingScientificPreview:PendingScientificPreview?=null
    private fun sameScienceAwaitingResult():Boolean {
        // A superseded frame may match again after undo/drag-back, but can no longer publish.
        if(pendingScientificPreview!=null)return false
        val s=mutable.value
        return renderJob?.isActive==true && renderingPhoto===s.photo &&
            renderingScience==previewScience(s.parameters) &&
            (scientificRequest==generation || processedPhoto!==s.photo || processedScience!=renderingScience)
    }
    private fun schedulePresentationRender(){
        if(sameScienceAwaitingResult()){pendingPresentationSettled=true;return}
        scheduleRender(settled=true)
    }
    private var activePresentationRequest:Long?=null
    private var pendingPresentationSettled=false
    private fun scheduleRender(settled:Boolean=true,presentationOnly:Boolean=false) {
        if(!mutable.value.startupReady)return
        val s=mutable.value;val source=s.original ?: return
        if(s.engineUnavailable!=null)return
        var params=s.parameters
        val cachedPresentation=processedBitmap?.isRecycled==false && processedPhoto===s.photo && processedScience==previewScience(params)
        val reuseGpuSession=gpuRenderingChoice && previewSessionHealthy && renderingPhoto===s.photo
        val inFlight=scientificRequest
        if(reuseGpuSession && inFlight!=null && inFlight==activeRenderRequest && renderJob?.isActive==true){
            pendingScientificPreview=PendingScientificPreview(inFlight,s.photo,params,settled,presentationOnly)
            generation++ // Invalidate publication immediately, without retiring the native epoch.
            mutable.value=s.copy(updating=true,previewRefining=true,messageUi=null,messageIsDiagnostic=false,message=null)
            return
        }
        // A bounded compositor must not be cancelled by every pointer event: on slower devices
        // that would starve all live frames. Keep one in-flight frame and coalesce to latest state.
        if(cachedPresentation && activePresentationRequest==generation && renderJob?.isActive==true){
            pendingPresentationSettled=pendingPresentationSettled || settled
            return
        }
        pendingScientificPreview=null
        // Cancelling a debounce/refinement delay is harmless, but engine.cancel retires GPU caches.
        // Only same-photo healthy GPU scheduling can reuse them; hard lifecycle cancels stay above.
        if(!reuseGpuSession)cancelPreviewEngine()
        renderJob?.cancel()
        previewSessionHealthy=true
        val request=++generation
        activeRenderRequest=request
        if(diagnosticSourceToken==0L || diagnosticPhoto?.get()!==s.photo){diagnosticPhoto=s.photo?.let{java.lang.ref.WeakReference(it)};diagnosticSourceToken=PreviewDiagnosticContext.nextSourceToken()}
        val diagnosticToken=diagnosticSourceToken
        val diagnosticFingerprint=PreviewDiagnosticContext.fingerprint(params)
        renderingScience=previewScience(params);renderingPhoto=s.photo
        activePresentationRequest=if(cachedPresentation)request else null
        pendingPresentationSettled=false
        // A new operation attempt is a new message event, even if it fails with the same text.
        mutable.value=s.copy(updating=!cachedPresentation,previewRefining=!cachedPresentation,messageUi=null,messageIsDiagnostic=false,message=null,messageAttempt=s.messageAttempt+1)
        renderJob=viewModelScope.launch {
            try {
                delay(if(presentationOnly)16 else 120)
                var responsivePhotoEdge=0
                val science=previewScience(params)
                val cached=processedBitmap?.takeIf { !it.isRecycled && processedPhoto===s.photo && processedScience==science }
                if(presentationOnly && cached==null)return@launch
                val cachedDetailed=cached!=null && processedDetailed
                suspend fun publish(detail:Boolean,existing:Bitmap?=null) {
                    val diagnostic=PreviewDiagnosticContext(diagnosticToken,request,diagnosticFingerprint,
                        if(existing!=null)PreviewFrameKind.CACHED else if(detail)PreviewFrameKind.REFINED else PreviewFrameKind.QUICK,
                        if(existing!=null)processedDiagnosticContext?.originGeneration ?: request else request,
                        if(existing!=null)processedDiagnosticContext?.originKind ?: PreviewFrameKind.CACHED else if(detail)PreviewFrameKind.REFINED else PreviewFrameKind.QUICK)
                    var disposition=PreviewDisposition.FAILED
                    var nativeCompleted=false
                    try {
                    val existingDetailed=existing!=null && existing===processedBitmap && processedDetailed
                    val filmResult=existing ?: run {
                        scientificRequest=request
                        var returned:Bitmap?=null
                        try {withContext(diagnostic){renderPreview(source,params,s.photo,detail).also{returned=it;nativeCompleted=true}}}
                        catch(t:Throwable){returned?.takeUnless{it.isRecycled}?.recycle();if(scientificRequest==request)scientificRequest=null;throw t}
                    }
                    if(!detail)responsivePhotoEdge=maxOf(filmResult.width,filmResult.height)
                    var adopted=false
                    var rendered:Bitmap?=null
                    var newInteractive:Bitmap?=null
                    var layer:Bitmap?=null
                    var layerKey:String?=null
                    try {
                        ensureActive()
                        if(request!=generation || mutable.value.photo!==s.photo){
                            disposition=if(mutable.value.photo!==s.photo)PreviewDisposition.STALE_SOURCE else PreviewDisposition.STALE_GENERATION
                            return
                        }
                        val newest=mutable.value.parameters
                        if(mutable.value.photo===s.photo && previewScience(newest)==science)params=newest
                        val photo=checkNotNull(s.photo)
                        val border=if(params.filmBorder)borderProvider.snapshot(dev.filmroom.border.BorderCatalog.frameStock(params.filmStock,params.paperStock,params.outputRoute),filmResult.width,filmResult.height,
                            photo.originalWidth,photo.originalHeight,params.composition,params.negativeWhiteHoles,params.outputRoute) else null
                        val crop=if(border==null)dev.filmroom.crop.CropSnapshot.create(filmResult.width,filmResult.height,photo.originalWidth,photo.originalHeight,params.crop)else null
                        val coroutine=currentCoroutineContext()
                        rendered=if(border!=null)dev.filmroom.border.BorderPreview.owned {
                            val raster=borderProvider.resolve(border){coroutine.ensureActive()}
                            layerKey=dev.filmroom.border.InteractiveBorderPreview.key(border)
                            layer=interactiveBorderCache?.takeIf{interactiveBorderKey==layerKey && !it.isRecycled}
                            val bytes=dev.filmroom.border.BorderCompositor.workingBytes(border,raster)+border.outputWidth.toLong()*border.outputHeight*(if(layer==null)8L else 4L)
                            dev.filmroom.border.BorderMemoryAdmission.withBudget(bytes,borderMemorySnapshot) {
                                if(layer==null){
                                    newInteractive=dev.filmroom.border.InteractiveBorderPreview.create(border,raster){coroutine.ensureActive()}
                                    layer=newInteractive;interactiveBorderBuildCalls++
                                }
                                exactBorderCompositionCalls++
                                dev.filmroom.border.BorderPreview.compose(filmResult,border,raster){coroutine.ensureActive()}
                            }
                        } else if(crop!=null && !crop.isIdentity)dev.filmroom.border.BorderPreview.owned {
                            val bytes=dev.filmroom.crop.CropCompositor.workingBytes(crop)+crop.outputWidth.toLong()*crop.outputHeight*4L
                            dev.filmroom.border.BorderMemoryAdmission.withBudget(bytes,borderMemorySnapshot){
                                dev.filmroom.crop.CropPreview.compose(filmResult,crop){coroutine.ensureActive()}
                            }
                        } else filmResult
                        ensureActive()
                        if(request==generation && mutable.value.photo===s.photo) {
                            processedBitmap=filmResult;processedPhoto=s.photo;processedScience=science
                            if(existing==null)processedDiagnosticContext=diagnostic
                            processedDetailed=if(existing!=null)existingDetailed else detail
                            disposition=PreviewDisposition.CACHE_ONLY
                            adopted=true // Keep scientifically valid cache even if this presentation became obsolete.
                            if(mutable.value.parameters==params && !compositionGestureActive){
                                interactiveBorderKey=layerKey;interactiveBorderCache=layer
                                mutable.value=mutable.value.copy(rendered=rendered,renderedParameters=params,renderedDiagnosticContext=diagnostic,
                                    compositionSource=filmResult,presentation=border,cropPresentation=crop,interactiveBorder=layer,updating=false)
                                disposition=PreviewDisposition.ADOPTED
                            }else{
                                // The live view owns the gesture until UP. Never replace it with a late
                                // source/ratio/crop, and never rerun science merely to discard an old frame.
                                if(rendered!==filmResult)rendered?.recycle()
                                newInteractive?.takeUnless{it.isRecycled}?.recycle()
                            }
                        } else disposition=if(mutable.value.photo!==s.photo)PreviewDisposition.STALE_SOURCE else PreviewDisposition.STALE_GENERATION
                    } finally {
                        // Ownership spans post-engine composition until the completed science result
                        // is retained in the processed cache, not merely until native render returns.
                        if(existing==null && scientificRequest==request)scientificRequest=null
                        if(!adopted){newInteractive?.takeUnless{it.isRecycled}?.recycle();if(rendered!==filmResult)rendered?.recycle();if(existing==null)filmResult.recycle()}
                    }
                    } catch(c:CancellationException){disposition=PreviewDisposition.CANCELLED;throw c}
                    finally {
                        // A diagnostic failure must never change ownership, rendering or cancellation.
                        runCatching{previewAdoptionSink(PreviewAdoptionRecord(diagnostic,disposition,nativeCompleted))}
                    }
                }
                var refineRequested=settled
                if(cached!=null){
                    while(true){
                        ensureActive()
                        val latest=mutable.value.parameters
                        if(mutable.value.photo!==s.photo || previewScience(latest)!=science)return@launch
                        params=latest
                        publish(false,cached)
                        refineRequested=refineRequested || pendingPresentationSettled
                        pendingPresentationSettled=false
                        if(compositionGestureActive || mutable.value.parameters==params)break
                    }
                }else {
                    publish(false)
                    if(request!=generation)return@launch
                    while(!compositionGestureActive && mutable.value.parameters!=params){
                        ensureActive();val latest=mutable.value.parameters
                        if(mutable.value.photo!==s.photo || previewScience(latest)!=science)break
                        params=latest;publish(false,checkNotNull(processedBitmap))
                    }
                }
                if(request!=generation)return@launch
                refineRequested=refineRequested || pendingPresentationSettled
                pendingPresentationSettled=false
                if(activePresentationRequest==request)activePresentationRequest=null
                // Keep the responsive frame/export available while refining. Never enlarge
                // a rendered bitmap: render again from the bounded source / linear RAW spool.
                if(refineRequested && !cachedDetailed && !compositionGestureActive) {
                    delay(350)
                    try {
                        val target=minOf(detailPreviewEdge(),maxOf(source.width,source.height))
                        if(target>responsivePhotoEdge)publish(true)
                    } catch(c:CancellationException) {throw c}
                    catch(_:OutOfMemoryError) { if(activeRenderRequest==request)previewSessionHealthy=false; /* Keep the valid quick frame. */ }
                    catch(_:Exception) { if(activeRenderRequest==request)previewSessionHealthy=false; /* Memory pressure/detail failure must not lose it. */ }
                }
                if(request!=generation)return@launch
                // A crop may change while the final scientific refinement was in flight.
                // Catch up from that completed cache, not by re-entering the scientific engine.
                while(!compositionGestureActive && mutable.value.parameters!=params){
                    ensureActive();val latest=mutable.value.parameters
                    if(mutable.value.photo!==s.photo || previewScience(latest)!=science)break
                    params=latest;publish(false,checkNotNull(processedBitmap))
                }
            } catch(c: CancellationException) { if(activeRenderRequest==request)previewSessionHealthy=false;throw c }
            catch(e: OutOfMemoryError) { if(activeRenderRequest==request)previewSessionHealthy=false;if(request==generation) mutable.value=mutable.value.copy(updating=false,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_acd49b3daba5),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_acd49b3daba5)) }
            catch(e: Exception) { if(activeRenderRequest==request)previewSessionHealthy=false;if(request==generation) mutable.value=mutable.value.copy(updating=false,messageUi=(e as? EngineException)?.uiMessage ?: (e as? PhotoException)?.uiMessage ?: if(e !is EngineException && e !is PhotoException)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_bc37a5375a5d)else null,messageIsDiagnostic=e is PhotoException && e.uiMessage==null,message=(e as? EngineException)?.message ?: (e as? PhotoException)?.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bc37a5375a5d)) }
            finally {
                if(activePresentationRequest==request)activePresentationRequest=null
                if(request==generation) mutable.value=mutable.value.copy(previewRefining=false,updating=if(!currentCoroutineContext().isActive)false else mutable.value.updating)
                val pending=pendingScientificPreview
                if(pending?.owner==request){
                    pendingScientificPreview=null
                    if(currentCoroutineContext().isActive && mutable.value.photo===pending.photo &&
                        previewScience(mutable.value.parameters)==previewScience(pending.parameters)){
                        // The old job no longer owns native work. Start exactly one latest request.
                        renderJob=null
                        scheduleRender(pending.settled,pending.presentationOnly)
                    }
                }
            }
        }
    }
    fun retry() { if(mutable.value.busy==null)scheduleRender() }
    fun export(format:ExportFormat,maxEdge:Int?=null,quality:Int=95,outputColorSpace:String="sRGB")=startExport(format,maxEdge,quality,outputColorSpace)
    fun exportOriginal(format:ExportFormat,quality:Int=95,outputColorSpace:String="sRGB")=startExport(format,null,quality,outputColorSpace)
    fun exportOriginalPng()=exportOriginal(ExportFormat.PNG)
    // Capturing is the only editor-owned part. A queued job never belongs to this ViewModel.
    internal var beginExportIntake:()->java.io.Closeable = {dev.filmroom.export.ExportQueue.beginIntake(getApplication<Application>())}
    internal var submitExport: suspend (PhotoSource,EditorState,ExportFormat,Int?,Int,Bitmap?,((()->Unit)->Unit),()->Unit)->Unit = { source,params,format,edge,quality,thumbnail,onAccepted,onDurable ->
        val queue=withContext(Dispatchers.IO){dev.filmroom.export.ExportQueue.get(getApplication<Application>())}
        queue.enqueue(source,params,format,edge,quality,thumbnail,
            onAccepted={job->onAccepted{queue.requestCancel(job.id)}},onEnqueued={onDurable()})
    }
    private fun startExport(format:ExportFormat,maxEdge:Int?,quality:Int,outputColorSpace:String) {
        val snapshot=mutable.value
        if(!snapshot.canExport || format !in availableExportFormats)return
        if(outputColorSpace !in setOf("sRGB","Display P3","Adobe RGB (1998)"))return
        val exportParameters=snapshot.parameters.copy(outputColorSpace=outputColorSpace,
            outputGamut=snapshot.parameters.outputGamut&&outputColorSpace=="sRGB")
        detailInvalidator?.invoke()
        // Lock source lifetime synchronously, including repeated taps before launch dispatch.
        mutable.value=snapshot.copy(busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_be0d9af082d8),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_be0d9af082d8),messageUi=null,messageIsDiagnostic=false,message=null,exporting=true,cancelling=false,
            exportProgress=ExportDisplayProgress(1),messageAttempt=snapshot.messageAttempt+1)
        val cancellation=PendingExportCancellation().also{intakeCancellation=it}
        operationJob=viewModelScope.launch {
            var result:String?=null
            var resultUi:dev.filmroom.i18n.UiMessage?=null
            var resultDiagnostic=false
            var intake:java.io.Closeable?=null
            val durable=java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                // Start the service during the foreground user action, before slow IO or decoding.
                intake=beginExportIntake()
                val generation=(intake as? dev.filmroom.export.ExportQueue.IntakeLease)?.generation ?: dev.filmroom.export.ExportQueue.currentGeneration()
                withContext(dev.filmroom.export.ExportIntakeGeneration(generation)){
                    submitExport(checkNotNull(snapshot.photo),exportParameters,format,maxEdge,quality,snapshot.original,cancellation::accepted){durable.set(true)}
                }
                resultUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_bb8990972dce);result=resultUi.render()
            } catch(c:CancellationException) {
                resultUi=dev.filmroom.i18n.UiMessage.of(when{cancellation.requested->dev.filmroom.R.string.text_afb4ebeb2c42;cancellation.wasAccepted||durable.get()->dev.filmroom.R.string.text_f9957f03a610;else->dev.filmroom.R.string.text_9f9e68d602d6})
                result=resultUi.render();throw c
            }
            catch(e:OutOfMemoryError){resultUi=dev.filmroom.i18n.UiMessage.of(if(durable.get())dev.filmroom.R.string.text_834295cc897b else dev.filmroom.R.string.text_aeb3f82f0b35);result=resultUi.render()}
            catch(e:Exception){
                resultUi=if(durable.get())dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_420e35e0302a) else (e as? EngineException)?.uiMessage ?: (e as? PhotoException)?.uiMessage ?: if(e !is PhotoException)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_11ee5fdc4a0b)else null
                resultDiagnostic=resultUi==null && e is PhotoException
                result=resultUi?.render() ?: (e as? PhotoException)?.message
            }
            finally {intake?.close();if(intakeCancellation===cancellation)intakeCancellation=null;mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=resultUi,messageIsDiagnostic=resultDiagnostic,message=result,exporting=false,cancelling=false,exportProgress=null)}
        }
    }
    override fun onCleared() { pendingScientificPreview=null;previewSessionHealthy=false;engine.close();mutable.value.photo?.clearLinearPreview();super.onCleared() }
}

/** Count only actual cleanup targets; absence is successful, failed deletion never advances done. */
internal suspend fun cleanupOwnedExportFiles(input:File,borrowed:Boolean,output:File?,onProgress:(Long,Long)->Unit):Boolean = withContext(NonCancellable) {
    val owned=buildList { if(!borrowed)add(input);output?.let(::add) }.distinct()
    val total=owned.size.toLong()
    var done=0L
    onProgress(done,total)
    for(file in owned){
        val removed=withContext(Dispatchers.IO){runCatching { !file.exists() || file.delete() || !file.exists() }.getOrDefault(false)}
        if(removed)done++
        onProgress(done,total)
    }
    done==total
}

/** Explicit cancel is different from leaving/destroying the Activity while acceptance finishes. */
internal class PendingExportCancellation {
    private val cancelRequested=java.util.concurrent.atomic.AtomicBoolean(false)
    private val acceptedFlag=java.util.concurrent.atomic.AtomicBoolean(false)
    private val action=java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    val requested get()=cancelRequested.get()
    val wasAccepted get()=acceptedFlag.get()
    fun accepted(cancel:()->Unit){acceptedFlag.set(true);action.set(cancel);if(cancelRequested.get())action.getAndSet(null)?.invoke()}
    fun cancel(){cancelRequested.set(true);action.getAndSet(null)?.invoke()}
}
