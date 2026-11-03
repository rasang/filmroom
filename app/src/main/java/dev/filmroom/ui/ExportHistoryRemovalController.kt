package dev.filmroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.filmroom.export.*
import dev.filmroom.model.*
import kotlinx.coroutines.*

/** UI-owned serial operation; exact durable snapshots survive a pending system confirmation. */
internal class ExportHistoryRemovalController(private val activity:ComponentActivity,private val changed:()->Unit) {
    private var queue:ExportQueue?=null
    private var operation:ExportHistoryOperation?=null
    private var restoredId:String?=null
    private var consentResult:Pair<String,Boolean>?=null
    private var launcher:androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>?=null
    private var launcherId:String?=null
    private var task:Job?=null
    private var chooser:AlertDialog?=null
    private var failures=false
    var singleRemovalId:String?=null;private set
    fun consumedRemovalAnimation(id:String){if(singleRemovalId==id)singleRemovalId=null}
    var busy=false;private set
    private val preferences by lazy{ExportHistoryPreferences(activity)}
    private val media by lazy{ExportMediaDeletionCoordinator(activity.applicationContext)}
    private fun registerConsent(id:String){
        if(launcherId==id)return
        launcher?.unregister();launcherId=id
        launcher=activity.activityResultRegistry.register("export-history-consent-$id",ActivityResultContracts.StartIntentSenderForResult()){result->
            if((operation?.id ?: restoredId)==id){
                consentResult=id to (result.resultCode==Activity.RESULT_OK)
                if(queue!=null)activity.lifecycleScope.launch{task?.join();resumeConsent()}
            }
        }
    }
    private fun toast(key:HistoryText,vararg args:Any){Toast.makeText(activity,key.text(activity,*args),Toast.LENGTH_LONG).show()}
    fun restore(state:Bundle?){restoredId=state?.getString("history-operation");restoredId?.let(::registerConsent);busy=false}
    fun save(state:Bundle){(operation?.takeIf{it.status=="awaiting-consent"}?.id ?: restoredId)?.let{state.putString("history-operation",it)}}
    fun attach(value:ExportQueue){queue=value;if(consentResult!=null)resumeConsent()else{busy=false;changed()}}
    fun close(changingConfiguration:Boolean){
        chooser?.dismiss();chooser=null
        launcher?.unregister();launcher=null;launcherId=null
        // A real system result is needed to resume. Never replay a photo delete on startup.
        if(!changingConfiguration || operation?.status!="awaiting-consent"){
            task?.cancel()
            operation?.let{queue?.store?.releaseHistory(it)}
        }
    }
    fun request(action:ExportHistoryAction,snapshots:List<ExportHistorySnapshot>){
        if(busy || chooser?.isShowing==true)return
        restoredId?.let{abandoned->
            // A lost OS callback is not approval and must not lock out a fresh explicit action.
            restoredId=null;consentResult=null;launcher?.unregister();launcher=null;launcherId=null;busy=true;changed()
            task=activity.lifecycleScope.launch{
                try{withContext(NonCancellable+Dispatchers.IO){queue?.store?.readHistoryOperation(abandoned)?.let{old->queue?.store?.releaseHistory(old);queue?.store?.updateHistoryOperation(old.copy(status="abandoned"))}}}
                catch(_:Exception){toast(HistoryText.INTERRUPTED)}
                finally{busy=false;changed()}
                request(action,snapshots)
            };return
        }
        if(snapshots.isEmpty()){toast(HistoryText.PROTECTED);return}
        val saved=preferences.get(action)
        if(saved!=ExportHistoryMode.ASK){start(action,snapshots,saved==ExportHistoryMode.RECORDS_AND_PHOTOS);return}
        chooser=showHistoryChoice(activity,action,snapshots.any{it.state==ExportJobState.FAILED},snapshots.size){mode,remember->
            if(remember){try{preferences.set(action,mode)}catch(_:Exception){toast(HistoryText.SAVE_FAILED);return@showHistoryChoice}}
            start(action,snapshots,mode==ExportHistoryMode.RECORDS_AND_PHOTOS)
        }.also{dialog->dialog.setOnDismissListener{if(chooser===dialog)chooser=null}}
    }
    private fun start(action:ExportHistoryAction,snapshots:List<ExportHistorySnapshot>,deletePhotos:Boolean){
        singleRemovalId=if(action==ExportHistoryAction.SINGLE)snapshots.singleOrNull()?.id else null
        val q=queue ?: return;busy=true;failures=false;changed()
        task=activity.lifecycleScope.launch {
            try{
                withContext(NonCancellable){operation=q.beginHistoryRemoval(snapshots,deletePhotos)}
                currentCoroutineContext().ensureActive()
                if(operation!!.targets.size!=snapshots.size){failures=true;toast(HistoryText.PROTECTED)}
                process()
            }catch(c:CancellationException){throw c}
            catch(_:Exception){failures=true;toast(HistoryText.FAILED);finish()}
            finally{if(operation?.status!="awaiting-consent" && busy)finish()}
        }
    }
    private suspend fun write(value:ExportHistoryOperation){withContext(Dispatchers.IO){queue!!.store.updateHistoryOperation(value)};operation=value}
    private suspend fun outcome(snapshot:ExportHistorySnapshot,value:String){val op=operation!!;write(op.copy(outcomes=op.outcomes+(snapshot.id to value),status="running",consentUri=null,consentKind=null))}
    private suspend fun process(){
        val q=queue ?: return
        while(true){
            val op=operation ?: return;val snapshot=op.remaining.firstOrNull() ?: break
            if(!q.store.historyMatches(snapshot)){failures=true;toast(HistoryText.PROTECTED);outcome(snapshot,"changed");continue}
            if(!op.deletePhotos){
                if(q.removeHistoryRecord(op,snapshot))outcome(snapshot,"record-removed")else{failures=true;toast(HistoryText.PROTECTED);outcome(snapshot,"changed")}
                continue
            }
            val uri=snapshot.mediaUri
            if(uri==null){
                failures=true;toast(HistoryText.MISSING);outcome(snapshot,"missing-uri");continue
            }
            val result=media.delete(uri,snapshot.exportMillis,snapshot.outputName)
            if(!handle(snapshot,result))return
        }
        finish()
    }
    private suspend fun handle(snapshot:ExportHistorySnapshot,result:ExportMediaDeletionResult):Boolean {
        val q=queue ?: return false;val op=operation ?: return false
        when(result){
            ExportMediaDeletionResult.Deleted->{
                // External success is durable first. Failure here retains the manifest for a safe manual retry.
                write(op.copy(status="photo-deleted:${snapshot.id}",consentUri=null,consentKind=null))
                if(q.removeHistoryRecord(operation!!,snapshot))outcome(snapshot,"photo-and-record-removed")else{failures=true;toast(HistoryText.PROTECTED);outcome(snapshot,"changed")}
            }
            ExportMediaDeletionResult.Missing->{failures=true;toast(HistoryText.MISSING);outcome(snapshot,"missing")}
            is ExportMediaDeletionResult.Failed->{
                failures=true
                toast(when(result.reason){ExportMediaDeletionFailure.CANCELLED->HistoryText.DENIED;ExportMediaDeletionFailure.INVALID_URI,ExportMediaDeletionFailure.UNVERIFIED_IDENTITY->HistoryText.MISSING;else->HistoryText.FAILED})
                outcome(snapshot,result.reason.name)
                if(result.reason==ExportMediaDeletionFailure.CANCELLED){finish();return false}
            }
            is ExportMediaDeletionResult.NeedsConsent->{
                write(op.copy(status="awaiting-consent",consentUri=snapshot.mediaUri,consentKind=result.kind.name))
                registerConsent(op.id);launcher!!.launch(IntentSenderRequest.Builder(result.sender).build());return false
            }
        }
        return true
    }
    private fun resumeConsent(){
        if(task?.isActive==true)return
        val received=consentResult ?: return;val q=queue ?: return;consentResult=null
        if(received.first!=(operation?.id ?: restoredId))return
        val granted=received.second;busy=true;changed()
        task=activity.lifecycleScope.launch {
            try{
                val op=operation ?: restoredId?.let{withContext(Dispatchers.IO){q.store.readHistoryOperation(it)}}
                restoredId=null
                operation=op
                val reserved=op!=null && withContext(NonCancellable){q.restoreHistoryReservation(op)}
                currentCoroutineContext().ensureActive()
                if(!reserved){toast(HistoryText.INTERRUPTED);finish();return@launch}
                checkNotNull(op)
                val snapshot=op.remaining.firstOrNull{it.mediaUri==op.consentUri} ?: error("No matching consent snapshot")
                val kind=ExportMediaDeletionConsentKind.valueOf(checkNotNull(op.consentKind))
                if(handle(snapshot,media.resume(checkNotNull(snapshot.mediaUri),snapshot.exportMillis,snapshot.outputName,kind,granted)))process()
            }catch(c:CancellationException){throw c}
            catch(_:Exception){failures=true;toast(HistoryText.FAILED);finish()}
            finally{if(operation?.status!="awaiting-consent" && busy)finish()}
        }
    }
    private suspend fun finish(){
        val op=operation
        if(op!=null){
            try{withContext(NonCancellable+Dispatchers.IO){queue?.store?.updateHistoryOperation(op.copy(status="finished",consentUri=null,consentKind=null))}}
            catch(_:Exception){failures=true}
            finally{queue?.store?.releaseHistory(op)}
            val removed=op.outcomes.values.count{it=="record-removed" || it=="photo-and-record-removed"}
            if(!failures && removed>0)toast(HistoryText.DONE,removed)
        }
        operation=null;restoredId=null;launcher?.unregister();launcher=null;launcherId=null;busy=false;changed()
    }
}

internal fun showHistoryChoice(activity:ComponentActivity,action:ExportHistoryAction,hasFailures:Boolean,count:Int=1,onConfirm:(ExportHistoryMode,Boolean)->Unit):AlertDialog {
    fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    fun label(key:HistoryText,size:Float)=TextView(activity).apply{text=key.text(activity);textSize=size;setTextColor(FilmroomPalette.muted);setPadding(0,dp(6),0,dp(6))}
    val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
    body.addView(label(if(count>1)HistoryText.PHOTO_MANY else HistoryText.PHOTO_ONE,15f))
    body.addView(label(if(action==ExportHistoryAction.CLEAR)HistoryText.CLEAR_NOTE else HistoryText.SINGLE_NOTE,14f))
    val group=RadioGroup(activity);val only=View.generateViewId();val both=View.generateViewId()
    listOf(only to (if(action==ExportHistoryAction.CLEAR)HistoryText.ONLY_CLEAR else HistoryText.ONLY),both to HistoryText.BOTH).forEach{(key,copy)->group.addView(RadioButton(activity).apply{id=key;text=copy.text(activity);textSize=15f;minHeight=dp(48);setTextColor(FilmroomPalette.ink);buttonTintList=ColorStateList.valueOf(FilmroomPalette.accent)})}
    group.check(only);body.addView(group)
    if(hasFailures)body.addView(label(HistoryText.RETRY_WARNING,12f))
    val remember=CheckBox(activity).apply{text=HistoryText.REMEMBER.text(activity);textSize=14f;minHeight=dp(48);setTextColor(FilmroomPalette.ink);buttonTintList=ColorStateList.valueOf(FilmroomPalette.accent);tag="history-remember"}
    val hint=label(HistoryText.HINT,12f).apply{visibility=View.GONE;tag="history-remember-hint"}
    remember.setOnCheckedChangeListener{_,checked->hint.visibility=if(checked)View.VISIBLE else View.GONE};body.addView(remember);body.addView(hint)
    val scroll=ScrollView(activity).apply{addView(body)}
    return AlertDialog.Builder(activity).setTitle((if(action==ExportHistoryAction.SINGLE)HistoryText.SINGLE_TITLE else if(count==1)HistoryText.CLEAR_ONE_TITLE else HistoryText.CLEAR_TITLE).text(activity)).setView(scroll)
        .setNegativeButton(HistoryText.CANCEL.text(activity),null)
        .setPositiveButton((if(action==ExportHistoryAction.CLEAR)HistoryText.CLEAR else HistoryText.DELETE).text(activity)){_,_->onConfirm(if(group.checkedRadioButtonId==both)ExportHistoryMode.RECORDS_AND_PHOTOS else ExportHistoryMode.RECORDS_ONLY,remember.isChecked)}
        .show().also{it.window?.setBackgroundDrawable(GradientDrawable().apply{setColor(FilmroomPalette.surface);cornerRadius=dp(12).toFloat()})}
}
