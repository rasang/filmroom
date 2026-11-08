package dev.filmroom.effects

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.filmroom.ui.FilmroomPalette as Palette
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Only URI references cross activities. Preview and video use the same native GL renderer. */
class PhotoEffectActivity : ComponentActivity() {
    companion object {
        const val RESULT_URI="effect_result_uri"
        const val SOURCE_URI="effect_source_uri"
        const val JOB_ID="effect_job_id"
        const val KIND="effect_kind"
        internal const val SOURCE_PICKER_KEY="effect-source-photo"
        internal const val RESULT_PICKER_KEY="effect-result-photo"
    }
    private lateinit var kind:EffectKind
    private lateinit var stage:FrameLayout
    private lateinit var status:TextView
    private lateinit var exportButton:Button
    private lateinit var replayButton:Button
    private lateinit var livePhotoButton:Button
    private lateinit var qualityButton:Button
    private var selectedQuality=EffectExportQuality.ORIGINAL
    @Volatile private var selectedSlow=false
    private var preview:EffectPreview?=null
    private var source:Bitmap?=null
    private var result:Bitmap?=null
    private var resultUri:String?=null
    private var sourceUri:String?=null
    private var jobId:String?=null
    private var recordSourceUri:String?=null
    private var recordResultUri:String?=null
    private var sourceTemporary=false
    private var resultTemporary=false
    private var selectionJob:Job?=null
    private var working=false
    private var livePreflight:EffectLivePhotoExporter.Preflight?=null
    private var liveExport=false
    private var liveChecking=false
    private val cancelled=AtomicBoolean(false)
    private var loadJob:Job?=null
    // Distinct stable registry keys survive picker-triggered Activity/process recreation.
    // Routing must not depend on an unsaved boolean in the previous Activity instance.
    private val sourcePicker=activityResultRegistry.register(SOURCE_PICKER_KEY,this,ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)selectPhoto(true,uri)
    }
    private val resultPicker=activityResultRegistry.register(RESULT_PICKER_KEY,this,ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)selectPhoto(false,uri)
    }
    private fun zh()=dev.filmroom.i18n.L10n.language(this).startsWith("zh")
    private fun t(cn:String,en:String):String {
        val language=dev.filmroom.i18n.L10n.language(this)
        if(language.startsWith("zh"))return cn
        val labels=mapOf(
            "Quality: " to ("画質：" to "화질: "), "Original" to ("元の解像度" to "원본"),
            "Original (default)" to ("元の解像度（標準）" to "원본 (기본값)"), "Export quality" to ("書き出し画質" to "내보내기 화질"),
            "Back" to ("戻る" to "뒤로"), "Light table" to ("ライトテーブル" to "라이트 테이블"),
            "Viewfinder light leak" to ("ファインダーの光漏れ" to "뷰파인더 빛샘"),
            "Replay" to ("再生" to "다시 재생"), "Export video" to ("動画を書き出す" to "동영상 내보내기"),
            "Export Live Photo" to ("Live Photoを書き出す" to "Live Photo 내보내기"),
            "Cancel export" to ("書き出しを中止" to "내보내기 취소"),
            "Loading photo…" to ("写真を読み込み中…" to "사진 불러오는 중…"),
            "Video saved to gallery" to ("動画を保存しました" to "동영상을 갤러리에 저장했습니다"),
            "Export cancelled" to ("書き出しを中止しました" to "내보내기를 취소했습니다"),
            "Video export failed. Try again" to ("動画を書き出せませんでした。再試行してください" to "동영상을 내보내지 못했습니다. 다시 시도하세요"),
            "Cancel" to ("キャンセル" to "취소"), "Done" to ("完了" to "완료"),
            "View video" to ("動画を見る" to "동영상 보기"), "Select photo" to ("写真を選択" to "사진 선택"),
            "Select again" to ("選び直す" to "다시 선택"),
            "Keep exporting" to ("書き出しを続ける" to "계속 내보내기"), "Leave" to ("戻る" to "나가기"),
            "Cancel export and return?" to ("書き出しを中止して戻りますか？" to "내보내기를 취소하고 돌아갈까요?")
        )
        val liveLabels=mapOf(
            "Unable to check Live Photo support. Try again" to ("Live Photoの対応状況を確認できませんでした。再試行してください" to "Live Photo 지원 여부를 확인하지 못했습니다. 다시 시도하세요"),
            "No Live Photo format is currently available for this gallery. Export a video instead" to ("このギャラリーで使えるLive Photo形式がありません。動画を書き出してください" to "현재 이 갤러리에서 사용할 수 있는 Live Photo 형식이 없습니다. 동영상으로 내보내세요"),
            "Live Photo playback has not been confirmed for this gallery. Try exporting? This choice will be remembered." to ("このギャラリーでのLive Photo再生は未確認です。書き出して試しますか？" to "이 갤러리에서 Live Photo 재생은 확인되지 않았습니다. 내보내기를 시도할까요?"),
            "Try export" to ("書き出して試す" to "내보내기 시도"),
            "Live Photo saved. Check playback in your gallery" to ("Live Photoを保存しました。ギャラリーで再生を確認してください" to "Live Photo를 저장했습니다. 갤러리에서 재생을 확인하세요"),
            "View" to ("表示" to "보기"),
            "Open your gallery to view it" to ("ギャラリーを開いて確認してください" to "갤러리를 열어 확인하세요"),
            "Live Photo export failed. Retry or export a video" to ("Live Photoを書き出せませんでした。再試行してください" to "Live Photo 내보내기에 실패했습니다. 다시 시도하세요"),
            "Original photo unavailable. Select it again." to ("元の写真にアクセスできません。選び直してください。" to "원본 사진에 접근할 수 없습니다. 다시 선택하세요."),
            "Exported photo unavailable. Select it again." to ("書き出した写真にアクセスできません。選び直してください。" to "내보낸 사진에 접근할 수 없습니다. 다시 선택하세요."),
            "This device could not display the effect" to ("この端末では効果を表示できません" to "이 기기에서 효과를 표시할 수 없습니다"),
            "No video player available" to ("動画を再生するアプリがありません" to "동영상 재생 앱이 없습니다")
        )
        val label=labels[en] ?: liveLabels[en]
        if(label!=null){if(language.startsWith("ja"))return label.first;if(language.startsWith("ko"))return label.second}
        if(en.startsWith("Exporting "))return when{language.startsWith("ja")->"書き出し中 "+en.removePrefix("Exporting ");language.startsWith("ko")->"내보내는 중 "+en.removePrefix("Exporting ");else->en}
        return en
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(dev.filmroom.i18n.L10n.wrap(base))}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        kind=runCatching{EffectKind.valueOf(intent.getStringExtra(KIND).orEmpty())}.getOrDefault(EffectKind.LIGHT_TABLE)
        resultUri=state?.getString(RESULT_URI) ?: intent.getStringExtra(RESULT_URI)
        sourceUri=state?.getString(SOURCE_URI) ?: intent.getStringExtra(SOURCE_URI)
        jobId=state?.getString(JOB_ID) ?: intent.getStringExtra(JOB_ID)
        recordSourceUri=if(state?.containsKey("record_source_uri")==true)state.getString("record_source_uri")else sourceUri
        recordResultUri=if(state?.containsKey("record_result_uri")==true)state.getString("record_result_uri")else resultUri
        sourceTemporary=state?.getBoolean("source_temporary") ?: false
        resultTemporary=state?.getBoolean("result_temporary") ?: false
        selectedSlow=state?.getBoolean("effect_slow") ?: false
        selectedQuality=runCatching{EffectExportQuality.valueOf(state?.getString("effect_quality").orEmpty())}.getOrDefault(EffectExportQuality.ORIGINAL)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Palette.background);setPadding(dp(16),dp(12),dp(16),dp(20))}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(EffectControls.button(this,"", "back"){leave()}.apply{contentDescription=t("返回","Back");setPadding(dp(13),dp(13),dp(13),dp(13))},LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(TextView(this).apply{text=if(kind==EffectKind.LIGHT_TABLE)t("观片台","Light table")else t("取景器漏光","Viewfinder light leak");textSize=20f;setTextColor(Palette.ink)},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
        root.addView(header)
        stage=object:FrameLayout(this@PhotoEffectActivity){override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);fitPreview()}};root.addView(stage,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(12);bottomMargin=dp(12)})
        status=TextView(this).apply{setTextColor(Palette.muted);textSize=14f;gravity=Gravity.CENTER;visibility=android.view.View.GONE;setPadding(0,dp(8),0,dp(12))};root.addView(status)
        qualityButton=EffectControls.button(this,qualityLabel(),"video",false){chooseQuality()}
        root.addView(qualityButton,LinearLayout.LayoutParams(-2,-2).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(8)})
        val actions=EffectActionBar(this)
        replayButton=EffectControls.button(this,t("重播","Replay"),"replay"){preview?.replay()}.apply{isEnabled=false}
        exportButton=EffectControls.button(this,t("导出视频","Export video"),"video",false){if(working)cancelled.set(true)else export()}.apply{isEnabled=false}
        livePhotoButton=EffectControls.button(this,t("导出 Live Photo","Export Live Photo"),"live",true){exportLivePhoto()}.apply{isEnabled=false}
        actions.addView(replayButton);actions.addView(exportButton);actions.addView(livePhotoButton)
        root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)})
        setContentView(root)
        root.setOnApplyWindowInsetsListener{v,insets-> val bars=insets.systemWindowInsetTop;v.setPadding(dp(16)+insets.systemWindowInsetLeft,bars+dp(12),dp(16)+insets.systemWindowInsetRight,insets.systemWindowInsetBottom+dp(20));insets}
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){leave()}})
        refreshLiveSupport()
        load(refreshLinks=true)
    }
    private fun qualityLabel()=t("清晰度：","Quality: ")+when(selectedQuality){
        EffectExportQuality.ORIGINAL->t("原图","Original")
        EffectExportQuality.P1920->t("长边 1920","Long edge 1920")
        EffectExportQuality.P1080->t("长边 1080","Long edge 1080")
    }
    private fun chooseQuality(){
        if(working||liveChecking)return
        val options=EffectExportQuality.values()
        AlertDialog.Builder(this).setTitle(t("导出清晰度","Export quality"))
            .setSingleChoiceItems(arrayOf(t("原图（默认）","Original (default)"),t("长边 1920","Long edge 1920"),t("长边 1080","Long edge 1080")),options.indexOf(selectedQuality)){dialog,index->
                selectedQuality=options[index];qualityButton.text=qualityLabel();dialog.dismiss()
            }.setNegativeButton(t("取消","Cancel"),null).show()
    }
    private fun resolutionMessage(report:EffectExportReport?):String {
        if(report==null)return ""
        val pixels="${report.actual.width} × ${report.actual.height}"
        return "\n"+if(report.reduced)t("实际导出：$pixels（受设备能力或内存限制，已降低清晰度）","Exported at $pixels (reduced by device or memory limits)")
            else t("实际导出：$pixels","Exported at $pixels")
    }
    private fun button(label:String,action:()->Unit)=Button(this).apply{text=label;isAllCaps=false;textSize=14f;minWidth=dp(64);setPadding(dp(20),0,dp(20),0);setTextColor(Palette.ink);setOnClickListener{action()}}
    private fun decode(uri:String):Bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,Uri.parse(uri))){decoder,info,_->
        val scale=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }
    private fun selectPhoto(original:Boolean,uri:Uri){
        selectionJob?.cancel();loadJob?.cancel()
        val expected=if(original)recordSourceUri else recordResultUri
        status.visibility=android.view.View.VISIBLE;status.text=t("正在保留照片访问权限…","Remembering photo access…")
        selectionJob=lifecycleScope.launch {
            val readable=withContext(Dispatchers.IO){runCatching{decode(uri.toString()).recycle();true}.getOrDefault(false)}
            if(!readable){status.visibility=android.view.View.VISIBLE;status.text=t("无法读取所选照片，请重新选择。","Cannot read the selected photo. Select it again.");missing(original);return@launch}
            val saved=withContext(Dispatchers.IO){
                runCatching{EffectPhotoAccess.remember(this@PhotoEffectActivity,dev.filmroom.export.ExportQueue.get(this@PhotoEffectActivity).store,jobId,original,expected,uri)}
                    .getOrDefault(EffectPhotoAccess.Status.SAVE_FAILED)
            }
            if(saved==EffectPhotoAccess.Status.INVALID_RESULT){
                AlertDialog.Builder(this@PhotoEffectActivity).setMessage(t("所选照片与这条导出记录不匹配，请选择对应的导出照片。","This photo does not match the export record. Select its exported photo.")).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("重新选择","Select again")){_,_->resultPicker.launch(arrayOf("image/*"))}.show();return@launch
            }
            if(saved==EffectPhotoAccess.Status.SAVED){if(original)recordSourceUri=uri.toString()else recordResultUri=uri.toString()}
            if(original){sourceUri=uri.toString();sourceTemporary=saved!=EffectPhotoAccess.Status.SAVED}
            else {resultUri=uri.toString();resultTemporary=saved!=EffectPhotoAccess.Status.SAVED}
            if(saved!=EffectPhotoAccess.Status.SAVED) {
                val warning=when(saved){
                    EffectPhotoAccess.Status.TEMPORARY_PERMISSION->t("未能保留权限，照片仅本次可用，重开可能需再选。","Photo available for this session only: persistent access could not be retained. You may need to select it again.")
                    else->t("记录更新失败，照片仅本次可用，重开可能需再选。","Photo available for this session only: the record could not be updated. You may need to select it again.")
                }
                Toast.makeText(this@PhotoEffectActivity,warning,Toast.LENGTH_LONG).show()
            }
            load()
        }
    }
    private fun load(refreshLinks:Boolean=false){
        loadJob?.cancel();replayButton.isEnabled=false;livePhotoButton.isEnabled=false;exportButton.isEnabled=false;status.visibility=android.view.View.VISIBLE;status.text=t("加载照片…","Loading photo…")
        loadJob=lifecycleScope.launch{
            if(refreshLinks && jobId!=null) {
                val record=withContext(Dispatchers.IO){runCatching{dev.filmroom.export.ExportQueue.get(this@PhotoEffectActivity).store.get(jobId!!)}.getOrNull()}
                if(record!=null){recordSourceUri=record.originalUri;recordResultUri=record.mediaUri}
                val links=EffectPhotoAccess.restoredLinks(record,sourceUri,resultUri,sourceTemporary,resultTemporary)
                sourceUri=links.source;resultUri=links.result
            }
            val output=withContext(Dispatchers.IO){runCatching{decode(requireNotNull(resultUri))}.getOrNull()}
            if(output==null){missing(false);return@launch}
            val input=if(kind==EffectKind.OPTICAL_LEAK)withContext(Dispatchers.IO){runCatching{decode(requireNotNull(sourceUri))}.getOrNull()}else output
            if(input==null){output.recycle();missing(true);return@launch}
            result=output;source=input
            preview?.onPause();stage.removeAllViews()
            preview=EffectPreview(input,output).also{stage.addView(it,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))};fitPreview();if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))preview?.onPause()
            replayButton.isEnabled=true;livePhotoButton.isEnabled=true;exportButton.isEnabled=true;status.text="";status.visibility=android.view.View.GONE
        }
    }
    private fun fitPreview(){
        val view=preview ?: return
        if(stage.width<=0 || stage.height<=0)return
        val photoAspect=source?.let{it.width.toFloat()/it.height} ?: 1f
        val aspect=EffectGeometry.sceneAspect(kind,photoAspect)
        val width=minOf(stage.width.toFloat(),stage.height*aspect)
        view.layoutParams=FrameLayout.LayoutParams(width.toInt().coerceAtLeast(1),(width/aspect).toInt().coerceAtLeast(1),Gravity.CENTER)
    }
    private fun missing(original:Boolean){
        preview?.onPause();stage.removeAllViews();preview=null;source=null;result=null
        replayButton.isEnabled=false;livePhotoButton.isEnabled=false;exportButton.isEnabled=false
        status.visibility=android.view.View.VISIBLE;status.text=if(original)t("无法访问原图，请重新选择原图。","Original photo unavailable. Select it again.")else t("无法访问导出照片，请重新选择照片。","Exported photo unavailable. Select it again.")
        AlertDialog.Builder(this).setMessage(status.text).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("选择照片","Select photo")){_,_->if(original)sourcePicker.launch(arrayOf("image/*"))else resultPicker.launch(arrayOf("image/*"))}.show()
    }
    private fun refreshLiveSupport(){
        lifecycleScope.launch {
            livePreflight=runCatching{EffectLivePhotoExporter.preflight(this@PhotoEffectActivity)}.getOrNull()
            if(isDestroyed)return@launch
            val unavailable=livePreflight?.unsupported==true
            EffectControls.setPrimary(livePhotoButton,"live",!unavailable)
            EffectControls.setPrimary(exportButton,"video",unavailable)
        }
    }
    private fun exportLivePhoto(){
        if(working){if(liveExport)cancelled.set(true);return}
        if(liveChecking)return
        if(source==null||result==null)return
        liveChecking=true
        lifecycleScope.launch {
            val check=runCatching{EffectLivePhotoExporter.preflight(this@PhotoEffectActivity)}.getOrNull()
            livePreflight=check
            liveChecking=false
            if(isDestroyed)return@launch
            EffectControls.setPrimary(livePhotoButton,"live",check?.unsupported!=true)
            EffectControls.setPrimary(exportButton,"video",check?.unsupported==true)
            if(check==null){showLiveMessage(t("无法检查 Live Photo 支持情况，请重试","Unable to check Live Photo support. Try again"));return@launch}
            if(LivePhotoExportNotice(applicationContext).consumeFirstNotice()) {
                Toast.makeText(this@PhotoEffectActivity,t("部分设备可能不支持。可导出视频，尝试相册的转实况功能。","Playback may vary. Try video export and gallery conversion if available."),Toast.LENGTH_LONG).show()
            }
            if(check.selection.selectedProfile==null){showLiveMessage(t("暂无可生成的 Live Photo 格式，请导出视频","No Live Photo format can be generated. Export a video instead"));return@launch}
            startLiveExport(check)

        }
    }
    private fun showLiveMessage(message:String){AlertDialog.Builder(this).setMessage(message).setPositiveButton(t("完成","Done"),null).show()}
    private fun startLiveExport(check:EffectLivePhotoExporter.Preflight){
        if(working)return
        qualityButton.isEnabled=false;working=true;liveExport=true;cancelled.set(false);replayButton.isEnabled=false;exportButton.isEnabled=false
        livePhotoButton.text=t("取消导出","Cancel export")
        status.visibility=android.view.View.VISIBLE;status.text=t("导出中 0%","Exporting 0%")
        val quality=selectedQuality
        lifecycleScope.launch {
            val images=EffectExportImages()
            var report:EffectExportReport?=null
            try{
                withContext(Dispatchers.IO){images.load(contentResolver,kind,sourceUri,requireNotNull(resultUri),quality,live=true){cancelled.get()||!isActive}}
                val saved=EffectLivePhotoExporter.export(applicationContext,kind,images.source,images.result,check,slow=selectedSlow,cancelled={cancelled.get()},onProgress={p->runOnUiThread{if(!isDestroyed){status.text=t("导出中 ${(p*100).toInt()}%","Exporting ${(p*100).toInt()}%")}}},quality=quality,requestedSize=images.requested,onResolution={report=it})
                status.text=t("Live Photo 已保存，请在相册确认播放效果","Live Photo saved. Check playback in your gallery")+resolutionMessage(report)
                AlertDialog.Builder(this@PhotoEffectActivity).setMessage(status.text).setNegativeButton(t("完成","Done"),null).setPositiveButton(t("查看","View")){_,_->saved.publishedUris.firstOrNull()?.let{uri->runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{saved.selection?.environment?.galleryPackage?.let{setPackage(it)}})}.onFailure{showLiveMessage(t("请打开系统相册查看","Open your gallery to view it"))}}}.show()
            }catch(e:CancellationException){if(!isActive)throw e;status.text=t("已取消导出","Export cancelled")}
            catch(e:OutOfMemoryError){status.text=t("内存不足，请选择较低清晰度后重试","Not enough memory. Choose a lower quality and retry")}
            catch(e:Exception){status.text=if(cancelled.get())t("已取消导出","Export cancelled")else t("Live Photo 导出失败，请重试或导出视频","Live Photo export failed. Retry or export a video")}
            finally{images.close();working=false;liveExport=false;qualityButton.isEnabled=true;if(!isDestroyed){livePhotoButton.text=t("导出 Live Photo","Export Live Photo");replayButton.isEnabled=true;exportButton.isEnabled=true}}
        }
    }
    private fun export(){
        if(working||liveChecking)return
        if(source==null||result==null)return
        val slow=selectedSlow
        qualityButton.isEnabled=false;working=true;liveExport=false;cancelled.set(false);exportButton.text=t("取消导出","Cancel export");replayButton.isEnabled=false;livePhotoButton.isEnabled=false
        val quality=selectedQuality
        lifecycleScope.launch{
            val images=EffectExportImages()
            var report:EffectExportReport?=null
            try{
                val uri=withContext(Dispatchers.IO){
                    images.load(contentResolver,kind,sourceUri,requireNotNull(resultUri),quality){cancelled.get()||!isActive}
                    EffectVideoExporter.export(applicationContext,kind,images.source,images.result,{p->runOnUiThread{if(!isDestroyed){val percent=(p*100).toInt();status.visibility=android.view.View.VISIBLE;status.text=t("导出中 $percent%","Exporting $percent%")}}}, {cancelled.get()||!isActive},slow=slow,quality=quality,requestedSize=images.requested,onResolution={report=it})}
                status.visibility=android.view.View.VISIBLE;status.text=t("视频已保存到相册","Video saved to gallery")+resolutionMessage(report)
                AlertDialog.Builder(this@PhotoEffectActivity).setMessage(status.text).setNegativeButton(t("完成","Done"),null).setPositiveButton(t("查看视频","View video")){_,_->runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{Toast.makeText(this@PhotoEffectActivity,t("没有可播放视频的应用","No video player available"),Toast.LENGTH_SHORT).show()}}.show()
            }catch(e:CancellationException){if(!isActive)throw e;status.visibility=android.view.View.VISIBLE;status.text=t("已取消导出","Export cancelled")}
            catch(e:OutOfMemoryError){status.visibility=android.view.View.VISIBLE;status.text=t("内存不足，请选择较低清晰度后重试","Not enough memory. Choose a lower quality and retry")}
            catch(e:Exception){status.visibility=android.view.View.VISIBLE;status.text=if(cancelled.get())t("已取消导出","Export cancelled")else t("视频导出失败，请重试","Video export failed. Try again")}
            finally{images.close();working=false;qualityButton.isEnabled=true;if(!isDestroyed){exportButton.text=t("导出视频","Export video");replayButton.isEnabled=true;livePhotoButton.isEnabled=true}}
        }
    }
    private fun leave(){if(!working)finish()else AlertDialog.Builder(this).setMessage(t("取消导出并返回？","Cancel export and return?")).setNegativeButton(t("继续导出","Keep exporting"),null).setPositiveButton(t("返回","Leave")){_,_->cancelled.set(true);finish()}.show()}
    override fun onSaveInstanceState(out:Bundle){out.putString("record_source_uri",recordSourceUri);out.putString("record_result_uri",recordResultUri);out.putBoolean("source_temporary",sourceTemporary);out.putBoolean("result_temporary",resultTemporary);out.putString(JOB_ID,jobId);out.putBoolean("effect_slow",selectedSlow);out.putString("effect_quality",selectedQuality.name);out.putString(RESULT_URI,resultUri);out.putString(SOURCE_URI,sourceUri);super.onSaveInstanceState(out)}
    override fun onResume(){super.onResume();preview?.onResume()}
    override fun onPause(){preview?.onPause();super.onPause()}
    override fun onDestroy(){cancelled.set(true);super.onDestroy()}
    private inner class EffectPreview(private val a:Bitmap,private val b:Bitmap):GLSurfaceView(this@PhotoEffectActivity),GLSurfaceView.Renderer{
        private var renderer=EffectGlRenderer(kind,a,b)
        private var start=0L;private var widthPx=1;private var heightPx=1;private var failed=false;private var stopped=false;private var playingSlow=selectedSlow;private var phase=""
        init{setEGLContextClientVersion(2);setRenderer(this);renderMode=RENDERMODE_CONTINUOUSLY}
        override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){runCatching{renderer=EffectGlRenderer(kind,a,b);failed=false;renderer.initialize();start=SystemClock.elapsedRealtime();playingSlow=selectedSlow;stopped=false;renderMode=RENDERMODE_CONTINUOUSLY}.onFailure{renderError()}}
        override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int){widthPx=width;heightPx=height}
        override fun onDrawFrame(gl:GL10?){if(failed)return;runCatching{
            val elapsed=if(stopped)0L else SystemClock.elapsedRealtime()-start
            val duration=EffectTimeline.durationMs(kind,playingSlow)
            renderer.draw(widthPx,heightPx,elapsed.coerceIn(0,duration),playingSlow)
            if(stopped||elapsed>=duration)renderMode=RENDERMODE_WHEN_DIRTY
        }.onFailure{renderError()}}
        fun replay(){val slow=selectedSlow;queueEvent{playingSlow=slow;stopped=false;phase="";start=SystemClock.elapsedRealtime();renderMode=RENDERMODE_CONTINUOUSLY};requestRender()}
        fun reset(){queueEvent{stopped=true;phase=""};requestRender()}
        private fun renderError(){if(failed)return;failed=true;renderMode=RENDERMODE_WHEN_DIRTY;runOnUiThread{status.visibility=android.view.View.VISIBLE;status.text=t("当前设备无法显示此效果","This device could not display the effect");exportButton.isEnabled=false;livePhotoButton.isEnabled=false;replayButton.isEnabled=false}}
    }
}
