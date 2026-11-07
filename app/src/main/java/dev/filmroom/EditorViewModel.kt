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
import kotlinx.coroutines.flow.collect
import java.io.File

data class EditorUiState(
    val startupReady:Boolean=true, val recoveryFailed:Boolean=false,
    val photo: PhotoSource?=null, val original: Bitmap?=null, val rendered: Bitmap?=null,
    val parameters: EditorState=EditorState(), val renderedParameters: EditorState?=null,
    val renderedDiagnosticContext:PreviewDiagnosticContext?=null,
    val compositionEditing:Boolean=false, val presentation:dev.filmroom.border.BorderSnapshot?=null,
    val compositionSource:Bitmap?=null, val interactiveBorder:Bitmap?=null,
    val cropPresentation:dev.filmroom.crop.CropSnapshot?=null,
    val imprintPlan:dev.filmroom.imprint.ImprintPlan?=null,
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
    companion object { const val ENGINE_EDGE=dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE; internal const val SCIENCE_DEBOUNCE_MS=64L; internal const val REFINEMENT_SETTLE_MS=50L }
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
    private val preferences=application.getSharedPreferences("local-session",0)
    private var history=EditHistory()
    private val recoveryGate=(application as? FilmroomApplication)?.recoveryGate
    private val mutable=MutableStateFlow(EditorUiState(engineUnavailable=engine.unavailable,startupReady=recoveryGate==null,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_3d05fc7cf7e4),busy=if(recoveryGate!=null)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3d05fc7cf7e4)else null))
    val state=mutable.asStateFlow()
    private var renderJob: Job?=null
    private var operationJob: Job?=null
    private var generation=0L
    private data class RefinementTarget(val photo:PhotoSource?,val science:String)
    private var pendingRefinement:RefinementTarget?=null
    private var scientificGestureAwaitingCommit=false
    private val exportRenderingActive get()=dev.filmroom.engine.ExportWorkAdmissions.active.value
    internal var cancelPreviewEngine:()->Unit = { engine.cancel() }
    private val idlePrewarmer=dev.filmroom.engine.IdlePreviewPrewarmer(application,viewModelScope,
        prepare={parameters,allowed->engine.prewarmFront(parameters,allowed)},cancelNative=engine::cancelPrewarm,
        interactiveIdle={renderJob?.isActive!=true && !mutable.value.exporting && !mutable.value.updating && !mutable.value.previewRefining})
    private var previewForeground=true
    fun setPrewarmForeground(foreground:Boolean) {
        idlePrewarmer.setForeground(foreground)
        val changed=previewForeground!=foreground
        previewForeground=foreground
        if(!foreground && activeRefinementRequest!=null && renderJob?.isActive==true){
            // Optional whole-photo refinement yields when invisible. QUICK may
            // finish, and durable export/detail own separate handles/lifecycles.
            cancelPreviewEngine();renderJob?.cancel()
        } else if(foreground && changed)resumeDeferredRefinement()
    }
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
        // This observation belongs to the ViewModel, not the process-wide export queue.
        // Queue work continues after the editor is destroyed; its collector does not.
        viewModelScope.launch {
            dev.filmroom.engine.ExportWorkAdmissions.active.collect { active ->
                if(active)idlePrewarmer.stop()else resumeDeferredRefinement()
            }
        }
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
            val restoredParameters=runCatching { EditorState.fromJson(preferences.getString("parameters","{}")!!).forLiveSessionRestore() }.getOrDefault(EditorState())
            idlePrewarmer.begin(restoredParameters)
            try {
                val p=repository.reopen(key,preferences.getString("name",dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e))!!,ENGINE_EDGE,preferences.getString("rawSource",null))
                idlePrewarmer.end()
                history=EditHistory(restoredParameters)
                mutable.value=mutable.value.copy(photo=p.source,original=p.preview,parameters=history.current,busyUi=null,busy=null,messageUi=null,messageIsDiagnostic=false,message=null)
                scheduleRender()
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { preferences.edit().clear().apply(); mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_063189730acc),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_063189730acc)) }
            finally { idlePrewarmer.end();if(mutable.value.busy==restoreLabel) mutable.value=mutable.value.copy(busyUi=null,busy=null) }
        }
        }
    }
    fun retryRecovery(){if(mutable.value.recoveryFailed)recoveryGate?.retry()}
    fun importPhoto(uri: Uri, origin: dev.filmroom.diagnostics.ImportOrigin = dev.filmroom.diagnostics.ImportOrigin.UNKNOWN) {
        compositionGestureActive=false
        if(!mutable.value.startupReady||mutable.value.busy!=null) return
        detailInvalidator?.invoke()
        // Capture the editing preference once. A failed/cancelled import never adopts this state.
        val previousParameters=mutable.value.parameters
        // A fresh photo with reset enabled also starts without a border. Toolbar preset restore
        // intentionally keeps its existing presentation policy; do not change processingPreset.
        val importParameters=(if(EditorPreferences(getApplication()).resetOnImport)processingPreset(previousParameters).copy(filmBorder=false) else previousParameters).let{it.copy(imprint=it.imprint.forNewPhoto())}
            .copy(composition=CompositionTransform(),crop=CropSettings())
        operationJob=viewModelScope.launch {
            mutable.value=mutable.value.copy(busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_1663134e88db),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1663134e88db),messageUi=null,messageIsDiagnostic=false,message=null,compare=false,messageAttempt=mutable.value.messageAttempt+1)
            var loaded: LoadedPhoto?=null
            var adopted=false
            idlePrewarmer.begin(importParameters)
            try {
                OperationTrace.run(Operation.IMPORT,SafeInfo(importDiagnostic=dev.filmroom.diagnostics.ImportDiagnosticInfo(origin))) { trace ->
                val newPhoto=repository.import(uri,ENGINE_EDGE,origin) { info -> trace.mark(Stage.IMPORT_INPUT,SafeInfo(importDiagnostic=info)) };loaded=newPhoto
                idlePrewarmer.end()
                trace.mark(Stage.DECODE_HEADER,SafeInfo(width=newPhoto.source.originalWidth,height=newPhoto.source.originalHeight,metadataIssues=newPhoto.source.photoMetadata.issues,metadataPhase=dev.filmroom.diagnostics.MetadataIssuePhase.READ_VALIDATION,captureTimeBasis=newPhoto.source.photoMetadata.diagnosticDateBasis))
                pendingScientificRender=null;pendingRefinement=null;scientificGestureAwaitingCommit=false
                cancelPreviewEngine();renderJob?.cancelAndJoin();clearProcessedCache()
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
            finally { idlePrewarmer.end();if(!adopted) loaded?.let { it.preview.recycle(); repository.discard(it.source) };resumeDeferredRefinement() }
        }
    }
    private var compositionBaseline:EditorState?=null
    fun beginComposition() {
        val s=mutable.value
        if(!s.editable || s.compositionEditing)return
        // A retained old frame can still be on screen while ON/OFF presentation catches up.
        // Never route an unframed crop's arbitrary aspect into the new fixed-135 transform.
        if(s.renderedParameters?.filmBorder!=s.parameters.filmBorder ||
            s.renderedParameters?.filmBorderStage!=s.parameters.filmBorderStage ||
            s.renderedParameters?.effectiveMediumFormat!=s.parameters.effectiveMediumFormat ||
            s.renderedParameters?.effectiveMediumPortrait!=s.parameters.effectiveMediumPortrait)return
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
            val aspect=dev.filmroom.border.BorderCatalog.frameAspect(s.parameters.frameStock,portrait,s.parameters.effectiveMediumFormat)
            s.parameters.copy(mediumPortrait=s.parameters.effectiveMediumFormat!=null && portrait,composition=s.parameters.composition.copy(targetAspect=aspect).clamped(p.originalWidth,p.originalHeight))
        }else s.parameters.copy(crop=if(mode==CropMode.ORIGINAL)CropSettings() else s.parameters.crop.copy(mode=mode))
        compositionGestureActive=false
        mutable.value=s.copy(parameters=next,compare=false)
        schedulePresentationRender()
    }
    /** Format selection belongs to the same cancellable draft as crop and orientation. */
    fun chooseCompositionFormat(format:dev.filmroom.border.MediumFormat?) {
        val s=mutable.value;val photo=s.photo ?: return
        if(!s.editable || !s.compositionEditing || !s.parameters.filmBorder ||
            !dev.filmroom.border.MediumFormatStocks.available(s.parameters.frameStock))return
        val parameters=s.parameters
        if(parameters.effectiveMediumFormat==format)return
        val portrait=if(parameters.effectiveMediumFormat!=null)parameters.effectiveMediumPortrait
            else parameters.composition.targetAspect?.let{it<1.0} ?: (photo.originalHeight>photo.originalWidth)
        val selected=parameters.selectMediumFormat(format,portrait)
        val next=selected.copy(composition=selected.composition.clamped(photo.originalWidth,photo.originalHeight))
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
                processedScience==previewScience(s.parameters) && !sameScienceAwaitingResult()){
                renderJob?.cancel();activePresentationRequest=null;generation++;cancelledPresentationWork=true
            }
        }
        mutable.value=s.copy(parameters=next,compare=false,messageUi=null,messageIsDiagnostic=false,message=null,
            previewRefining=if(cancelledPresentationWork)false else s.previewRefining,
            updating=if(cancelledPresentationWork)false else s.updating)

    }
    fun parameters(next: EditorState, commit: Boolean=true) {
        val inputStarted=android.os.SystemClock.elapsedRealtime()
        if(!mutable.value.editable) return
        compositionGestureActive=false
        val scienceChanged=previewScience(mutable.value.parameters)!=previewScience(next)
        val refineOnCommit=commit && (scienceChanged || (scientificGestureAwaitingCommit && mutable.value.parameters==next))
        if(scienceChanged && !commit)scientificGestureAwaitingCommit=true
        if(commit)scientificGestureAwaitingCommit=false
        if(scienceChanged)detailInvalidator?.invoke()
        if(commit && !mutable.value.compositionEditing) history.update(next)
        mutable.value=mutable.value.copy(parameters=next,compositionEditing=mutable.value.compositionEditing,compare=false,canUndo=history.canUndo,canRedo=history.canRedo,messageUi=null,messageIsDiagnostic=false,message=null)
        if(commit) saveSession()
        // Confirming an already exact draft changes history, not pixels or scientific work.
        if(!scienceChanged && !refineOnCommit && mutable.value.renderedParameters==next && mutable.value.rendered!=null &&
            processedBitmap!=null && processedBitmap===mutable.value.compositionSource &&
            !mutable.value.updating && !mutable.value.previewRefining && renderJob?.isActive!=true)return
        // A crop commit during an in-flight scientific render must not restart that render.
        // Its publication adopts the latest crop and catches up from the resulting photo cache.
        if(!scienceChanged && sameScienceAwaitingResult()){
            if(refineOnCommit)pendingRefinement=RefinementTarget(mutable.value.photo,previewScience(next))
            pendingPresentationSettled=true;return
        }
        // Exact final presentation is scheduled only for committed crop edits.
        if(scienceChanged || commit) scheduleRender(settled=commit,requestRefinement=refineOnCommit,inputStarted=inputStarted)
        else if(processedPhoto===mutable.value.photo && processedScience==previewScience(next))scheduleRender(settled=false,presentationOnly=true,requestRefinement=false,inputStarted=inputStarted)
    }
    fun undo() { if(!mutable.value.editable)return;if(mutable.value.compositionEditing){cancelComposition();return};history.undo();applyHistory() }
    fun redo() { if(!mutable.value.editable)return;if(mutable.value.compositionEditing){cancelComposition();return};history.redo();applyHistory() }
    private fun applyHistory() { compositionGestureActive=false;val scienceChanged=previewScience(mutable.value.parameters)!=previewScience(history.current);if(scienceChanged)detailInvalidator?.invoke();mutable.value=mutable.value.copy(parameters=history.current,compositionEditing=false,canUndo=history.canUndo,canRedo=history.canRedo,compare=false);saveSession();scheduleRender(requestRefinement=scienceChanged) }
    /** Confirmation is bound to the exact photo and parameter state that the user reviewed. */
    fun restorePreset(expectedPhotoKey:String,expectedParameters:EditorState):Boolean {
        val current=mutable.value
        if(!current.editable || current.compositionEditing || current.photo?.cacheKey!=expectedPhotoKey || current.parameters!=expectedParameters)return false
        val next=processingPreset(expectedParameters)
        if(next==current.parameters)return false
        parameters(next)
        return true
    }
    /** Named user presets replace every editing parameter in one undo step, never photo data. */
    fun applySavedPreset(expectedPhotoKey:String,expectedParameters:EditorState,next:EditorState):Boolean {
        val current=mutable.value
        if(!current.editable || current.compositionEditing || current.photo?.cacheKey!=expectedPhotoKey || current.parameters!=expectedParameters)return false
        parameters(next,true)
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
        idlePrewarmer.end()
        compositionGestureActive=false
        detailInvalidator?.invoke()
        intakeCancellation?.cancel()
        if(mutable.value.exporting)mutable.value=mutable.value.copy(cancelling=true,busyUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_c12aefec4f8a),busy=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c12aefec4f8a))
        pendingScientificRender=null;pendingRefinement=null;scientificGestureAwaitingCommit=false
        cancelPreviewEngine();renderJob?.cancel();operationJob?.cancel()
        if(mutable.value.busy==null)mutable.value=mutable.value.copy(updating=false,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_6f25582e0d79),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6f25582e0d79))
    }
    // Leaving the editor must never cancel durable queue work (or an in-flight capture).
    fun processingScreenLeft() {setPrewarmForeground(false)}
    fun cancelExportForSharing() = Unit
    private fun saveSession() {
        val s=mutable.value;val p=s.photo ?: return
        preferences.edit().putString("source",p.cacheKey).putString("rawSource",p.rawCacheKey).putString("name",p.name).putString("parameters",(compositionBaseline ?: s.parameters).json()).apply()
    }
    /** In pre-imaging mode geometry, artwork and imprints are scientific input, not overlays. */
    private fun previewScience(parameters:EditorState)=parameters.json(includePresentation=parameters.filmBorder && parameters.filmBorderStage==1)
    private fun clearProcessedCache(){
        // Published bitmaps are never recycled or pooled here: PhotoView / a pending StateFlow
        // delivery can still own them. Dropping our bounded cache reference lets Android reclaim
        // them only after every view is done. Never put originals in a scratch-buffer pool.
        processedPhoto=null;processedScience=null;processedBitmap=null;processedDetailed=false;processedBorder=null
        processedDiagnosticContext=null;interactiveBorderKey=null;interactiveBorderCache=null
    }
    private fun resumeDeferredRefinement(){
        val pending=pendingRefinement ?: return
        val current=mutable.value
        if(current.photo!==pending.photo || previewScience(current.parameters)!=pending.science){pendingRefinement=null;return}
        if(!previewForeground || exportRenderingActive || compositionGestureActive || current.busy!=null || renderJob?.isCompleted==false)return
        scheduleRender(settled=true,presentationOnly=true,requestRefinement=false)
    }
    internal var renderPreview:suspend (Bitmap,EditorState,PhotoSource?,Boolean)->Bitmap = { source,params,photo,settled -> engine.render(source,params,"preview",photo,settled) }
    internal var borderProvider:dev.filmroom.border.BorderProvider=dev.filmroom.border.BorderCatalog
    internal var detailPreviewEdge:()->Int = { engine.previewEdge(true) }
    private var processedPhoto:PhotoSource?=null
    private var processedScience:String?=null
    private var processedBitmap:Bitmap?=null
    private var processedDetailed=false
    private var processedBorder:dev.filmroom.border.BorderSnapshot?=null
    private var interactiveBorderKey:String?=null
    private var interactiveBorderCache:Bitmap?=null
    internal var exactBorderCompositionCalls=0; private set
    internal var interactiveBorderBuildCalls=0; private set
    private var renderingScience:String?=null
    private var renderingPhoto:PhotoSource?=null
    private var scientificRequest:Long?=null
    // Main-thread marker for the optional native refinement, not composition,
    // zoom-detail or queue export. A new scientific edit can preempt only this.
    private var activeRefinementRequest:Long?=null
    private fun sameScienceAwaitingResult():Boolean {
        val s=mutable.value
        return renderJob?.isActive==true && renderingPhoto===s.photo &&
            renderingScience==previewScience(s.parameters) &&
            (scientificRequest==generation || processedPhoto!==s.photo || processedScience!=renderingScience)
    }
    private fun schedulePresentationRender(){
        if(sameScienceAwaitingResult()){pendingPresentationSettled=true;return}
        scheduleRender(settled=true,requestRefinement=false)
    }
    private data class PendingScientificRender(val photo:PhotoSource?,val settled:Boolean,val presentationOnly:Boolean,val requestRefinement:Boolean,val inputStarted:Long)
    // Main-thread, single-slot mailbox. It never owns a bitmap or a native handle.
    private var pendingScientificRender:PendingScientificRender?=null
    private var previewSchedulerClosed=false
    private var activePresentationRequest:Long?=null
    private var pendingPresentationSettled=false
    private fun scheduleRender(settled:Boolean=true,presentationOnly:Boolean=false,requestRefinement:Boolean=settled,inputStarted:Long=android.os.SystemClock.elapsedRealtime(),skipScienceDebounce:Boolean=false) {
        idlePrewarmer.stop()
        if(previewSchedulerClosed || !mutable.value.startupReady)return
        val s=mutable.value;val source=s.original ?: return
        if(s.engineUnavailable!=null)return
        var params=s.parameters
        val scienceKey=previewScience(params)
        val cachedPresentation=processedBitmap?.isRecycled==false && processedPhoto===s.photo && processedScience==scienceKey
        if(pendingRefinement?.let{it.photo!==s.photo || it.science!=scienceKey}==true)pendingRefinement=null
        if(params.filmBorder && params.filmBorderStage==1)pendingRefinement=null
        else if(settled && (requestRefinement || !cachedPresentation))pendingRefinement=RefinementTarget(s.photo,scienceKey)
        // Do not restart the initial deadline or cancel useful native work on every slider
        // event. Invalidate publication immediately, retaining only the latest request.
        // The running coroutine drains this slot after its current stage has released ownership.
        if(renderJob?.isCompleted==false && renderingPhoto===s.photo && activePresentationRequest==null){
            pendingScientificRender=PendingScientificRender(s.photo,settled,presentationOnly,requestRefinement,inputStarted)
            generation++
            mutable.value=s.copy(updating=true,previewRefining=true)
            if(activeRefinementRequest!=null && renderingScience!=scienceKey && renderJob?.isActive==true){
                // Keep the last512 frame and the existing single-slot mailbox.
                // Its completion callback starts the latest QUICK only after
                // cancelled refinement has actually drained/released ownership.
                cancelPreviewEngine();renderJob?.cancel()
            }
            return
        }
        pendingScientificRender=null
        // A bounded compositor must not be cancelled by every pointer event: on slower devices
        // that would starve all live frames. Keep one in-flight frame and coalesce to latest state.
        if(cachedPresentation && activePresentationRequest==generation && renderJob?.isActive==true){
            pendingPresentationSettled=pendingPresentationSettled || settled
            return
        }
        // Pure presentation never retires a science session. The existing full-photo result is
        // sufficient even while an export owns native resources.
        if(!cachedPresentation)cancelPreviewEngine()
        renderJob?.cancel()
        val requestStarted=inputStarted
        val requestTiming=PreviewTiming(requestStarted).also{it.mark(PreviewTimingPoint.SCHEDULED)}
        val request=++generation
        if(diagnosticSourceToken==0L || diagnosticPhoto?.get()!==s.photo){diagnosticPhoto=s.photo?.let{java.lang.ref.WeakReference(it)};diagnosticSourceToken=PreviewDiagnosticContext.nextSourceToken()}
        val diagnosticToken=diagnosticSourceToken
        val diagnosticFingerprint=PreviewDiagnosticContext.fingerprint(params)
        renderingScience=previewScience(params);renderingPhoto=s.photo
        activePresentationRequest=if(cachedPresentation)request else null
        pendingPresentationSettled=false
        // A new operation attempt is a new message event, even if it fails with the same text.
        mutable.value=s.copy(updating=!cachedPresentation,previewRefining=!cachedPresentation,messageUi=null,messageIsDiagnostic=false,message=null,messageAttempt=s.messageAttempt+1)
        var enteredScience=false
        // A cancelled prior-photo job may still be draining. Its marker must
        // never classify this replacement QUICK as optional refinement.
        activeRefinementRequest=null
        renderJob=viewModelScope.launch {
            try {
                delay(if(cachedPresentation || presentationOnly)16 else if(skipScienceDebounce)0 else SCIENCE_DEBOUNCE_MS)
                requestTiming.mark(PreviewTimingPoint.DEBOUNCE_END)
                if(request!=generation || mutable.value.photo!==s.photo)return@launch
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
                    diagnostic.timing=if(!detail && existing==null)requestTiming else PreviewTiming(requestStarted).also{it.mark(PreviewTimingPoint.DEBOUNCE_END)}
                    if(existing!=null)diagnostic.timing?.cached()
                    var disposition=PreviewDisposition.FAILED
                    var nativeCompleted=false
                    var failureStage=PreviewFailureStage.RENDER_REQUEST
                    var failureInfo:SafeInfo?=null
                    try {
                    val existingDetailed=existing!=null && existing===processedBitmap && processedDetailed
                    val presentationContext=dev.filmroom.engine.PreviewPresentationContext()
                    val filmResult=existing ?: run {
                        enteredScience=true
                        scientificRequest=request
                        var returned:Bitmap?=null
                        if(detail)activeRefinementRequest=request
                        try {withContext(diagnostic+presentationContext){renderPreview(source,params,s.photo,detail).also{returned=it;nativeCompleted=true}}}
                        catch(t:Throwable){returned?.takeUnless{it.isRecycled}?.recycle();if(scientificRequest==request)scientificRequest=null;throw t}
                        finally {if(activeRefinementRequest==request)activeRefinementRequest=null}
                    }
                    diagnostic.timing?.mark(PreviewTimingPoint.COMPOSITION_START)
                    failureStage=PreviewFailureStage.COMPOSITION
                    if(!detail)responsivePhotoEdge=maxOf(filmResult.width,filmResult.height)
                    var adopted=false
                    var rendered:Bitmap?=null
                    var newInteractive:Bitmap?=null
                    var layer:Bitmap?=null
                    var layerKey:String?=null
                    var imprintPlan:dev.filmroom.imprint.ImprintPlan?=null
                    try {
                        ensureActive()
                        if(request!=generation || mutable.value.photo!==s.photo){
                            disposition=if(mutable.value.photo!==s.photo)PreviewDisposition.STALE_SOURCE else PreviewDisposition.STALE_GENERATION
                            return
                        }
                        val newest=mutable.value.parameters
                        if(mutable.value.photo===s.photo && previewScience(newest)==science)params=newest
                        val photo=checkNotNull(s.photo)
                        val preImaging=params.filmBorder && params.filmBorderStage==1
                        val border=if(preImaging)checkNotNull(if(existing!=null)processedBorder else presentationContext.border){"Missing pre-imaging presentation metadata"}
                        else if(params.filmBorder)borderProvider.snapshot(dev.filmroom.border.BorderCatalog.frameStock(params.filmStock,params.paperStock,params.outputRoute),filmResult.width,filmResult.height,
                            photo.originalWidth,photo.originalHeight,params.composition,params.negativeWhiteHoles,params.outputRoute,params.effectiveMediumFormat,params.effectiveMediumPortrait) else null
                        if(preImaging)check(filmResult.width==border!!.outputWidth && filmResult.height==border.outputHeight){"Invalid pre-imaging frame dimensions"}
                        val crop=if(border==null)dev.filmroom.crop.CropSnapshot.create(filmResult.width,filmResult.height,photo.originalWidth,photo.originalHeight,params.crop)else null
                        val coroutine=currentCoroutineContext()
                        rendered=if(preImaging)filmResult else if(border!=null)dev.filmroom.border.BorderPreview.owned {
                            val raster=borderProvider.resolve(border){coroutine.ensureActive()}
                            layerKey=dev.filmroom.border.InteractiveBorderPreview.key(border)+"/imprint-layout-"+if(params.imprint.enabled)params.imprint.layoutVersion else 0
                            layer=interactiveBorderCache?.takeIf{interactiveBorderKey==layerKey && !it.isRecycled}
                            val bytes=(if(params.imprint.enabled)dev.filmroom.imprint.ImprintPlan.MAX_WORKING_BYTES else 0L)+dev.filmroom.border.BorderCompositor.workingBytes(border,raster)+border.outputWidth.toLong()*border.outputHeight*(if(layer==null)8L else 4L)
                            dev.filmroom.border.BorderMemoryAdmission.withBudget(bytes,borderMemorySnapshot) {
                                if(layer==null){
                                    // The exact pass fills this overlay too, avoiding a second full
                                    // atlas traversal on white-hole/stock/geometry changes.
                                    newInteractive=Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
                                    layer=newInteractive;interactiveBorderBuildCalls++
                                }
                                imprintPlan=dev.filmroom.imprint.ImprintPlan.create(params.imprint,photo.photoMetadata,border.outputWidth,border.outputHeight,border)
                                exactBorderCompositionCalls++
                                if(newInteractive!=null)dev.filmroom.border.BorderPreview.composeWithOverlay(filmResult,border,raster,imprintPlan,checkNotNull(newInteractive)){coroutine.ensureActive()}
                                else dev.filmroom.border.BorderPreview.compose(filmResult,border,raster,imprintPlan){coroutine.ensureActive()}
                            }
                        } else if(crop!=null && (!crop.isIdentity || params.imprint.enabled))dev.filmroom.border.BorderPreview.owned {
                            val bytes=(if(params.imprint.enabled)dev.filmroom.imprint.ImprintPlan.MAX_WORKING_BYTES else 0L)+dev.filmroom.crop.CropCompositor.workingBytes(crop)+crop.outputWidth.toLong()*crop.outputHeight*4L
                            dev.filmroom.border.BorderMemoryAdmission.withBudget(bytes,borderMemorySnapshot){
                                imprintPlan=dev.filmroom.imprint.ImprintPlan.create(params.imprint,photo.photoMetadata,crop.outputWidth,crop.outputHeight)
                                dev.filmroom.crop.CropPreview.compose(filmResult,crop,imprintPlan){coroutine.ensureActive()}
                            }
                        } else filmResult
                        ensureActive()
                        failureStage=PreviewFailureStage.ADOPTION
                        if(request==generation && mutable.value.photo===s.photo) {
                            processedBitmap=filmResult;processedPhoto=s.photo;processedScience=science;processedBorder=if(preImaging)border else null
                            if(existing==null)processedDiagnosticContext=diagnostic
                            processedDetailed=if(existing!=null)existingDetailed else detail
                            disposition=PreviewDisposition.CACHE_ONLY
                            adopted=true // Keep scientifically valid cache even if this presentation became obsolete.
                            if(mutable.value.parameters==params && !compositionGestureActive){
                                interactiveBorderKey=layerKey;interactiveBorderCache=layer
                                mutable.value=mutable.value.copy(rendered=rendered,renderedParameters=params,renderedDiagnosticContext=diagnostic,
                                    compositionSource=filmResult,presentation=border,cropPresentation=crop,imprintPlan=imprintPlan,interactiveBorder=layer,updating=false)
                                disposition=PreviewDisposition.ADOPTED
                                diagnostic.timing?.adopted(diagnostic)
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
                    catch(t:Throwable){failureInfo=OperationTrace.failureInfo(t,0,failureStage).copy(durationMs=null);throw t}
                    finally {
                        // A diagnostic failure must never change ownership, rendering or cancellation.
                        runCatching{previewAdoptionSink(PreviewAdoptionRecord(diagnostic,disposition,nativeCompleted,failureInfo=failureInfo))}
                        if(disposition!=PreviewDisposition.ADOPTED)diagnostic.timing?.finish(diagnostic,
                            when(disposition){PreviewDisposition.CANCELLED->PreviewTimingTerminal.CANCELLED;PreviewDisposition.STALE_SOURCE->PreviewTimingTerminal.STALE_SOURCE;PreviewDisposition.STALE_GENERATION->PreviewTimingTerminal.STALE_GENERATION;PreviewDisposition.CACHE_ONLY->PreviewTimingTerminal.CACHE_ONLY;else->PreviewTimingTerminal.FAILED},disposition)
                    }
                }
                var refineRequested=pendingRefinement?.let{it.photo===s.photo && it.science==science}==true
                if(cached!=null){
                    while(true){
                        ensureActive()
                        val latest=mutable.value.parameters
                        if(mutable.value.photo!==s.photo || previewScience(latest)!=science)return@launch
                        params=latest
                        publish(false,cached)
                        refineRequested=refineRequested || pendingRefinement?.let{it.photo===s.photo && it.science==science}==true
                        pendingPresentationSettled=false
                        if(compositionGestureActive || mutable.value.parameters==params)break
                    }
                }else {
                    publish(false)
                    if(request!=generation || mutable.value.photo!==s.photo)return@launch
                    while(!compositionGestureActive && mutable.value.parameters!=params){
                        ensureActive();val latest=mutable.value.parameters
                        if(mutable.value.photo!==s.photo || previewScience(latest)!=science)break
                        params=latest;publish(false,checkNotNull(processedBitmap))
                    }
                }
                refineRequested=refineRequested || pendingRefinement?.let{it.photo===s.photo && it.science==science}==true
                pendingPresentationSettled=false
                if(activePresentationRequest==request)activePresentationRequest=null
                // Keep the responsive frame/export available while refining. Never enlarge
                // a rendered bitmap: render again from the bounded source / linear RAW spool.
                if(refineRequested && !cachedDetailed && !compositionGestureActive) {
                    // A scientific request keeps one pending final refinement. Presentation edits
                    // do not create new requests. During export QUICK stays usable and edits remain
                    // light; completion/cancel/failure resumes only the latest photo+science.
                    if(!previewForeground || exportRenderingActive)return@launch
                    delay(REFINEMENT_SETTLE_MS)
                    if(!previewForeground || exportRenderingActive || request!=generation || mutable.value.photo!==s.photo)return@launch
                    try {
                        val target=minOf(detailPreviewEdge(),maxOf(source.width,source.height))
                        if(target>responsivePhotoEdge)publish(true)
                        if(pendingRefinement?.let{it.photo===s.photo && it.science==science}==true)pendingRefinement=null
                    } catch(c:CancellationException) {throw c}
                    catch(_:OutOfMemoryError) { if(pendingRefinement?.let{it.photo===s.photo && it.science==science}==true)pendingRefinement=null; /* Keep the valid quick frame; explicit retry can refine. */ }
                    catch(_:Exception) { if(pendingRefinement?.let{it.photo===s.photo && it.science==science}==true)pendingRefinement=null; /* Do not turn unrelated presentation edits into repeated failing science. */ }
                }
                if(cachedDetailed && pendingRefinement?.let{it.photo===s.photo && it.science==science}==true)pendingRefinement=null
                // A crop may change while the final scientific refinement was in flight.
                // Catch up from that completed cache, not by re-entering the scientific engine.
                while(!compositionGestureActive && mutable.value.parameters!=params){
                    ensureActive();val latest=mutable.value.parameters
                    if(mutable.value.photo!==s.photo || previewScience(latest)!=science)break
                    params=latest;publish(false,checkNotNull(processedBitmap))
                }
            } catch(c: CancellationException) { throw c }
            catch(e: OutOfMemoryError) { if(request==generation)pendingRefinement=null;if(request==generation) mutable.value=mutable.value.copy(updating=false,messageUi=dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_acd49b3daba5),message=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_acd49b3daba5)) }
            catch(e: Exception) { if(request==generation)pendingRefinement=null;if(request==generation) mutable.value=mutable.value.copy(updating=false,messageUi=(e as? EngineException)?.uiMessage ?: (e as? PhotoException)?.uiMessage ?: if(e !is EngineException && e !is PhotoException)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_bc37a5375a5d)else null,messageIsDiagnostic=e is PhotoException && e.uiMessage==null,message=(e as? EngineException)?.message ?: (e as? PhotoException)?.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bc37a5375a5d)) }
            finally {
                // Captures cancellation before publish/native START, otherwise finish is idempotent.
                if(!currentCoroutineContext().isActive){
                    val cancelledContext=PreviewDiagnosticContext(diagnosticToken,request,diagnosticFingerprint,PreviewFrameKind.QUICK)
                    requestTiming.finish(cancelledContext,PreviewTimingTerminal.CANCELLED,PreviewDisposition.CANCELLED)
                }
                if(request!=generation && currentCoroutineContext().isActive)requestTiming.finish(
                    PreviewDiagnosticContext(diagnosticToken,request,diagnosticFingerprint,PreviewFrameKind.QUICK),
                    if(mutable.value.photo!==s.photo)PreviewTimingTerminal.STALE_SOURCE else PreviewTimingTerminal.STALE_GENERATION,
                    if(mutable.value.photo!==s.photo)PreviewDisposition.STALE_SOURCE else PreviewDisposition.STALE_GENERATION)
                if(activePresentationRequest==request)activePresentationRequest=null
                if(request==generation) mutable.value=mutable.value.copy(previewRefining=false,updating=if(!currentCoroutineContext().isActive)false else mutable.value.updating)
            }
        }
        val completedJob=renderJob
        completedJob?.invokeOnCompletion { cause ->
            if(cause is CancellationException)requestTiming.finish(
                PreviewDiagnosticContext(diagnosticToken,request,diagnosticFingerprint,PreviewFrameKind.QUICK),
                PreviewTimingTerminal.CANCELLED,PreviewDisposition.CANCELLED)
            // Completion can run on a worker during cancellation. Drain on Main, and never
            // let an old job steal a replacement job's mailbox or resurrect a cancelled editor.
            viewModelScope.launch {
                if(renderJob!==completedJob || previewSchedulerClosed)return@launch
                renderJob=null
                val pending=pendingScientificRender
                if(pending==null){resumeDeferredRefinement();return@launch}
                pendingScientificRender=null
                if(mutable.value.photo!==pending.photo)return@launch
                scheduleRender(pending.settled,pending.presentationOnly,pending.requestRefinement,pending.inputStarted,
                    skipScienceDebounce=!enteredScience)
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
            finally {intake?.close();if(intakeCancellation===cancellation)intakeCancellation=null;mutable.value=mutable.value.copy(busyUi=null,busy=null,messageUi=resultUi,messageIsDiagnostic=resultDiagnostic,message=result,exporting=false,cancelling=false,exportProgress=null);resumeDeferredRefinement()}
        }
    }
    override fun onCleared() { idlePrewarmer.close();previewSchedulerClosed=true;pendingScientificRender=null;pendingRefinement=null;generation++;renderJob?.cancel();engine.close();clearProcessedCache();mutable.value.photo?.clearLinearPreview();super.onCleared() }
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
