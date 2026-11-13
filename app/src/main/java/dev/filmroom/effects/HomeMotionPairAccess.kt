package dev.filmroom.effects

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Home editor counterpart to the export-list paired-video grant flow. */
internal class HomeMotionPairAccess(
    private val activity:ComponentActivity,
    private val currentImage:()->String?,
    private val enabled:()->Boolean,
    private val refresh:()->Unit,
) {
    private var attempted=false
    private var permissionImage:String?=null
    private var pickerImage:String?=null
    private var selecting=false
    val busy get()=permissionImage!=null || pickerImage!=null || selecting
    private fun text(cn:String,en:String)=if(dev.filmroom.i18n.L10n.language(activity).startsWith("zh"))cn else en
    private fun toast(cn:String,en:String)=Toast.makeText(activity,text(cn,en),Toast.LENGTH_LONG).show()
    private val permission=activity.activityResultRegistry.register("home-live-pair-permission",activity,ActivityResultContracts.RequestMultiplePermissions()) {
        val image=permissionImage;permissionImage=null
        if(image!=null && image==currentImage() && enabled()) refresh()
    }
    private val picker=activity.activityResultRegistry.register("home-live-pair-video",activity,ActivityResultContracts.OpenDocument()) { uri ->
        val image=pickerImage;pickerImage=null
        if(uri!=null && image!=null && image==currentImage() && enabled())activity.lifecycleScope.launch {
            selecting=true
            try {
                val matched=withContext(Dispatchers.IO){EffectMotionInput.associate(activity,image,uri){ensureActive()}}
                if(image!=currentImage())return@launch
                if(matched)refresh() else toast("视频与原 Live 照片不匹配或无法读取，请选择原配套视频。","Video does not match this Live photo or cannot be read. Select its original video.")
            } catch(e:CancellationException){throw e}
            catch(_:Exception){toast("无法读取原 Live 视频，请重试。","Could not read the original Live video. Please retry.")}
            finally{selecting=false}
        }
    }
    fun requestIfNeeded(missing:Boolean) {
        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !enabled() || !missing || busy || attempted || EffectMotionPairAccess.hasFullVideoAccess(activity))return
        permissionImage=currentImage() ?: return
        attempted=true
        permission.launch(EffectMotionPairAccess.requiredPermissions())
    }
    fun requireCompanion(missing:Boolean):Boolean {
        if(!missing)return false
        if(!busy)AlertDialog.Builder(activity)
            .setMessage(text("这张 Live 照片的视频是独立文件，请先选择配套视频，避免导出时丢失原片段。","This Live photo has a separate video. Select its original video before exporting."))
            .setNegativeButton(text("取消","Cancel"),null)
            .setPositiveButton(text("选择原 Live 视频","Select original Live video")){_,_->
                pickerImage=currentImage();if(pickerImage!=null)picker.launch(arrayOf("video/mp4"))
            }.show()
        return true
    }
    fun save(out:Bundle){out.putBoolean("home_pair_attempted",attempted);out.putString("home_pair_permission",permissionImage);out.putString("home_pair_picker",pickerImage)}
    fun restore(state:Bundle?){attempted=state?.getBoolean("home_pair_attempted") ?: false;permissionImage=state?.getString("home_pair_permission");pickerImage=state?.getString("home_pair_picker")}
}
