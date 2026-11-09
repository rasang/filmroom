package dev.filmroom.effects

import android.app.AlertDialog
import java.io.File
import dev.filmroom.diagnostics.*
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.DecelerateInterpolator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
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
    private var pageLoad:Job?=null
    private var prefetchJob:Job?=null
    private val prefetchWorkers=mutableSetOf<Job>()
    private val adjacentPhotos=EffectAdjacentCache<EffectPagePhoto>{it.close()}
    private var gestureNeighbours=emptyMap<Int,dev.filmroom.export.ExportJob>()
    private var adjacentEpoch=0
    // Main-thread ownership: a bitmap belongs to current, incoming, retired GL work, or idle cache.
    private var retentionEpoch=0
    private var currentRecord:dev.filmroom.export.ExportJob?=null
    private val pendingRetirements=mutableSetOf<()->Unit>()
    private var pageCommitPending=false
    private val pageWorkers=mutableSetOf<Job>()
    private var pageEpoch=0
    private var incomingPhoto:EffectPagePhoto?=null
    private var incomingPreview:EffectPreview?=null
    private var incomingReady=false
    private var incomingId:String?=null
    private var pageDirection=0
    private var pageDrag=0f
    private var displayedPageOffset=0f
    private var draggingPage=false
    private var pageRequested=false
    private var pageAnimator:ValueAnimator?=null
    private val pageBusy get()=draggingPage||pageRequested||pageAnimator!=null||pageCommitPending
    private data class EffectPagePhoto(val record:dev.filmroom.export.ExportJob,val input:Bitmap,val output:Bitmap,
        val composition:EffectSourceComposition?,val readout:EffectCameraReadout,val motionFile:File?=null) {
        fun close(){motionFile?.delete();if(!input.isRecycled)input.recycle();if(output!==input&&!output.isRecycled)output.recycle()}
    }
    private val previewAudio by lazy { EffectPreviewAudio(this) }
    private val audioEpoch=java.util.concurrent.atomic.AtomicInteger()
    private fun stopPreviewAudio(){audioEpoch.incrementAndGet();previewAudio.stop()}
    private var source:Bitmap?=null
    private var result:Bitmap?=null
    private var resultUri:String?=null
    private var sourceUri:String?=null
    private var jobId:String?=null
    private var sourceComposition:EffectSourceComposition?=null
    private var sourceMotionFile:File?=null
    private var cameraReadout=EffectCameraReadout()
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
            "Viewfinder" to ("ファインダー" to "뷰파인더"),
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
        header.addView(TextView(this).apply{text=if(kind==EffectKind.LIGHT_TABLE)t("观片台","Light table")else t("取景器","Viewfinder");textSize=20f;setTextColor(Palette.ink)},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
        root.addView(header)
        stage=object:FrameLayout(this@PhotoEffectActivity){
            private var downX=0f;private var downY=0f;private var tracking=false;private var horizontal=false
            private var velocity:VelocityTracker?=null
            private val maxFlingVelocity=ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
            private fun clearVelocity(){velocity?.recycle();velocity=null}
            override fun onDetachedFromWindow(){clearVelocity();super.onDetachedFromWindow()}
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw&&oldw>0)cancelPageTransition();fitPreview();incomingPhoto?.let{incomingPreview?.layoutParams=pageLayout(it.output)}}
            override fun dispatchTouchEvent(event:MotionEvent):Boolean {
                if(event.actionMasked==MotionEvent.ACTION_DOWN){clearVelocity();velocity=VelocityTracker.obtain()}
                velocity?.addMovement(event)
                when(event.actionMasked){
                    MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;tracking=canDragPage();horizontal=false}
                    MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->{tracking=false;if(horizontal)releasePage(false);horizontal=false;clearVelocity()}
                    MotionEvent.ACTION_MOVE->if(tracking){
                        val dx=event.x-downX;val dy=event.y-downY
                        if(!horizontal && kotlin.math.abs(dy)>dp(12) && kotlin.math.abs(dy)>kotlin.math.abs(dx)){tracking=false;clearVelocity()}
                        else if(horizontal || EffectPhotoNavigation.swipeDirection(dx,dy,dp(10).toFloat())!=0){horizontal=true;dragPage(dx)}
                    }
                    MotionEvent.ACTION_UP->{
                        if(tracking&&horizontal){
                            // Include the final position and release velocity, not total gesture time.
                            dragPage(event.x-downX)
                            velocity?.computeCurrentVelocity(1000,maxFlingVelocity)
                            releasePage(true,velocity?.xVelocity ?: 0f,velocity?.yVelocity ?: 0f)
                        }
                        tracking=false;horizontal=false;clearVelocity()
                    }
                }
                super.dispatchTouchEvent(event);return true
            }
        };root.addView(stage,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(12);bottomMargin=dp(12)})
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
        load(refreshLinks=true,autoPlay=state==null)
    }
    private fun qualityLabel()=t("清晰度：","Quality: ")+when(selectedQuality){
        EffectExportQuality.ORIGINAL->t("原图","Original")
        EffectExportQuality.P1920->t("长边 1920","Long edge 1920")
        EffectExportQuality.P1080->t("长边 1080","Long edge 1080")
    }
    private fun chooseQuality(){
        if(working||liveChecking||pageBusy)return
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
    private fun decode(uri:String,maxEdge:Int=1600):Bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,Uri.parse(uri))){decoder,info,_->
        val scale=minOf(1.0,maxEdge.toDouble()/maxOf(info.size.width,info.size.height));decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
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
    /** Decode both neighbours while idle; cached ownership transfers to a page, never to two renderers. */
    private fun clearAdjacentPhotos(){
        retentionEpoch++
        adjacentEpoch++;prefetchWorkers.toList().forEach{it.cancel()};prefetchJob=null
        adjacentPhotos.clear()
    }
    /** Return a displayed page, not a newly decoded copy. Stale/temporary links never enter the cache. */
    private fun retainPhoto(photo:EffectPagePhoto,epoch:Int){
        val jobs=dev.filmroom.export.ExportQueue.get(this).store.jobs.value
        val neighbours=listOfNotNull(EffectPhotoNavigation.neighbor(jobs,jobId,1),EffectPhotoNavigation.neighbor(jobs,jobId,-1))
        val available=Runtime.getRuntime().maxMemory()-Runtime.getRuntime().totalMemory()+Runtime.getRuntime().freeMemory()
        if(epoch==retentionEpoch && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            && available>=24L*1024*1024 && neighbours.any{it==photo.record}){
            adjacentPhotos.retain(neighbours.map{it.id}.toSet())
            adjacentPhotos.put(photo.record.id,photo)
        }else photo.close()
    }
    private fun preloadAdjacentPhotos(){
        val current=jobId ?: return
        val jobs=dev.filmroom.export.ExportQueue.get(this).store.jobs.value
        val neighbours=listOfNotNull(EffectPhotoNavigation.neighbor(jobs,current,1),EffectPhotoNavigation.neighbor(jobs,current,-1))
        val keep=neighbours.map{it.id}.toSet()
        adjacentPhotos.retain(keep)
        val previous=prefetchWorkers.toList();previous.forEach{it.cancel()}
        val epoch=++adjacentEpoch
        val worker=lifecycleScope.launch(start=CoroutineStart.LAZY) {
            try {
            previous.joinAll();ensureActive()
            for(record in neighbours){
                if(record.id==incomingId || adjacentPhotos.contains(record.id))continue
                val available=Runtime.getRuntime().maxMemory()-Runtime.getRuntime().totalMemory()+Runtime.getRuntime().freeMemory()
                // Two low-resolution neighbours, never two original-resolution decode jobs.
                val edge=EffectPageMotion.prefetchEdge(available)
                if(edge==0)break
                var photo:EffectPagePhoto?=null
                try {
                    photo=decodePage(record,edge)
                    ensureActive()
                    if(epoch==adjacentEpoch && jobId==current && record.id!=incomingId && !adjacentPhotos.contains(record.id)){adjacentPhotos.put(record.id,photo);photo=null}
                }catch(e:CancellationException){throw e}
                catch(_:Exception){}catch(_:OutOfMemoryError){break}
                finally{photo?.close()}
            }
            } finally {prefetchWorkers.remove(coroutineContext[Job])}
        }
        prefetchJob=worker;prefetchWorkers.add(worker);worker.start()
    }
    private suspend fun decodePage(record:dev.filmroom.export.ExportJob,edge:Int):EffectPagePhoto {
        var output:Bitmap?=null;var input:Bitmap?=null;var motionFile:File?=null
        try {
            val composition=EffectSourceComposition.from(record)
            val readout=withContext(Dispatchers.IO){EffectCameraReadout.read(contentResolver,record.originalUri,record.photoMetadata)}
            withContext(Dispatchers.IO){output=decode(requireNotNull(record.mediaUri),edge)}
            if(kind==EffectKind.OPTICAL_LEAK)withContext(Dispatchers.IO){
                val raw=decode(requireNotNull(record.originalUri),edge)
                try{input=composition?.apply(raw){ensureActive()} ?: raw;if(input!==raw)raw.recycle()}
                catch(t:Throwable){raw.recycle();throw t}
            }else input=output
            if(kind==EffectKind.OPTICAL_LEAK)withContext(Dispatchers.IO){motionFile=EffectMotionInput.prepare(this@PhotoEffectActivity,record.originalUri){ensureActive()}}
            currentCoroutineContext().ensureActive()
            return EffectPagePhoto(record,requireNotNull(input),requireNotNull(output),composition,readout,motionFile).also{input=null;output=null;motionFile=null}
        }finally{motionFile?.delete();input?.takeUnless{it.isRecycled}?.recycle();if(output!==input)output?.takeUnless{it.isRecycled}?.recycle()}
    }
    private fun canDragPage()=preview?.isFrameReady()==true && !pageCommitPending && !working && !liveChecking && pageAnimator==null && !pageRequested && loadJob?.isActive!=true && selectionJob?.isActive!=true
    private fun setPageControls(enabled:Boolean){
        replayButton.isEnabled=enabled&&source!=null&&result!=null&&preview?.hasFailed()!=true
        livePhotoButton.isEnabled=replayButton.isEnabled;exportButton.isEnabled=replayButton.isEnabled
        qualityButton.isEnabled=enabled
    }
    /** Keep the current rendered photo on screen until the adjacent page has a real GL frame. */
    private fun dragPage(dx:Float){
        if(!draggingPage){if(!canDragPage())return;draggingPage=true;stopPreviewAudio();preview?.reset();setPageControls(false)
            val jobs=dev.filmroom.export.ExportQueue.get(this).store.jobs.value
            gestureNeighbours=listOf(-1,1).mapNotNull{d->EffectPhotoNavigation.neighbor(jobs,jobId,d)?.let{d to it}}.toMap()}
        pageDrag=dx
        val direction=if(dx<0)1 else -1
        val next=gestureNeighbours[direction]
        if(pageDirection!=direction || incomingId!=next?.id){discardIncoming();pageDirection=direction;if(next!=null)prepareIncoming(next)}
        applyPageDrag()
    }
    private fun pageWidth():Float = (if(kind==EffectKind.LIGHT_TABLE)preview?.width?.takeIf{it>0} ?: stage.width else stage.width).toFloat().coerceAtLeast(1f)
    private fun applyPageDrag(){
        val width=pageWidth()
        val offset=EffectPageMotion.dragOffset(pageDrag,width,incomingId!=null)
        movePage(offset,width)
    }
    private fun movePage(offset:Float,width:Float){
        if(kind==EffectKind.LIGHT_TABLE){
            preview?.translationX=0f
            preview?.slideFilm(offset/width.coerceAtLeast(1f),pageDirection)
        }else{
            preview?.translationX=offset
            incomingPreview?.translationX=offset+pageDirection*width
        }
        displayedPageOffset=offset
    }
    private fun prepareIncoming(record:dev.filmroom.export.ExportJob){
        incomingId=record.id
        val epoch=pageEpoch
        val predecessors=pageWorkers.toList()
        val worker=lifecycleScope.launch(start=CoroutineStart.LAZY){
            var output:Bitmap?=null;var input:Bitmap?=null
            try {
                predecessors.joinAll()
                ensureActive()
                // Stop speculative work before an urgent page load. Await cancellation to bound live bitmaps.
                val cached=adjacentPhotos.take(record.id){it.record==record}
                val speculative=prefetchWorkers.toList();speculative.forEach{it.cancel()};prefetchJob=null
                val photo=if(cached!=null)cached else {
                    speculative.joinAll();ensureActive()
                    val edge=EffectPageMotion.decodeEdge(Runtime.getRuntime().maxMemory()-Runtime.getRuntime().totalMemory()+Runtime.getRuntime().freeMemory())
                    decodePage(record,edge)
                }
                if(epoch!=pageEpoch){photo.close();return@launch}
                incomingPhoto=photo;input=null;output=null
                if(kind==EffectKind.LIGHT_TABLE){
                    preview?.prepareFilm(photo.output){
                        if(epoch==pageEpoch && incomingPhoto===photo){
                            incomingReady=true
                            if(pageRequested)settlePage(true)else if(draggingPage)applyPageDrag()
                        }
                    }
                }else{
                    val view=EffectPreview(photo.input,photo.output,photo.readout,false,photo.motionFile,photo.composition){rendered->
                        if(epoch==pageEpoch && incomingPreview===rendered){
                            incomingReady=true
                            if(pageRequested)settlePage(true) else if(draggingPage)applyPageDrag()
                        }
                    }
                    incomingPreview=view
                    view.setZOrderMediaOverlay(true)
                    view.translationX=stage.width.toFloat()*pageDirection
                    stage.addView(view,pageLayout(photo.output))
                    if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))view.onPause()
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(epoch==pageEpoch)failedPage()}
            catch(e:OutOfMemoryError){if(epoch==pageEpoch)failedPage()}
            finally{input?.takeUnless{it.isRecycled}?.recycle();if(output!==input)output?.takeUnless{it.isRecycled}?.recycle();pageWorkers.remove(coroutineContext[Job])}
        }
        pageLoad=worker;pageWorkers.add(worker);worker.start()
    }
    private fun failedPage(){
        // A late renderer error must not let an already running commit listener change records.
        val interrupted=pageAnimator;pageAnimator=null;interrupted?.cancel()
        pageRequested=false;draggingPage=false
        preview?.restoreCurrentFrame()
        status.visibility=android.view.View.GONE
        Toast.makeText(this,t("无法读取这张照片，请在导出记录中重新选择照片。","Cannot load this photo. Select it again from its export record."),Toast.LENGTH_SHORT).show()
        settlePage(false)
    }
    private fun releasePage(allowCommit:Boolean,velocityX:Float=0f,velocityY:Float=0f){
        if(!draggingPage)return
        draggingPage=false
        val commit=allowCommit && incomingId!=null && EffectPageMotion.shouldCommit(pageDrag,pageWidth(),dp(64).toFloat(),velocityX,velocityY,dp(600).toFloat(),dp(16).toFloat())
        pageRequested=commit
        if(commit && !incomingReady){
            // Keep the finger position; do not rebound and then start a second animation.
            // Commit begins when the same candidate has its first renderable frame.
        }else settlePage(commit)
    }
    private fun settlePage(commit:Boolean){
        if(pageAnimator!=null)return
        if(commit && (!incomingReady||incomingPhoto==null||(kind!=EffectKind.LIGHT_TABLE&&incomingPreview==null)))return
        val width=pageWidth()
        val outgoing=preview ?: return
        val incoming=incomingPreview
        val from=displayedPageOffset
        val target=if(commit)-pageDirection*width else 0f
        val epoch=pageEpoch
        val animator=ValueAnimator.ofFloat(from,target)
        pageAnimator=animator
        animator.duration=220L;animator.interpolator=DecelerateInterpolator()
        animator.addUpdateListener{movePage(it.animatedValue as Float,width)}
        animator.addListener(object:AnimatorListenerAdapter(){
            private var cancelled=false
            override fun onAnimationCancel(animation:Animator){cancelled=true}
            override fun onAnimationEnd(animation:Animator){
                if(pageAnimator!==animator)return
                pageAnimator=null
                if(cancelled||epoch!=pageEpoch)return
                if(commit)commitPage()else{movePage(0f,width);discardIncoming();pageRequested=false;setPageControls(true);preloadAdjacentPhotos()}
            }
        })
        animator.start()
    }
    private fun commitPage(){
        val photo=incomingPhoto ?: return
        val lightTable=kind==EffectKind.LIGHT_TABLE
        val nextPreview=if(lightTable)preview else incomingPreview
        if(nextPreview==null)return
        stopPreviewAudio()
        if(!lightTable)preview?.let{it.onPause();stage.removeView(it)}
        val oldSource=source;val oldResult=result;val oldMotion=sourceMotionFile
        val oldRecord=currentRecord?.takeIf{!sourceTemporary&&!resultTemporary&&it.originalUri==sourceUri&&it.mediaUri==resultUri}
        val departed=if(oldRecord!=null&&oldSource!=null&&oldResult!=null)
            EffectPagePhoto(oldRecord,oldSource,oldResult,sourceComposition,cameraReadout,oldMotion) else null
        val retainEpoch=retentionEpoch
        val commitEpoch=pageEpoch
        currentRecord=photo.record
        source=photo.input;result=photo.output;sourceComposition=photo.composition;cameraReadout=photo.readout;sourceMotionFile=photo.motionFile
        jobId=photo.record.id;sourceUri=photo.record.originalUri;resultUri=photo.record.mediaUri
        recordSourceUri=sourceUri;recordResultUri=resultUri;sourceTemporary=false;resultTemporary=false
        preview=nextPreview;preview?.translationX=0f
        incomingPhoto=null;incomingPreview=null;incomingId=null;incomingReady=false;pageRequested=false;pageDrag=0f
        val disposeOld={oldMotion?.delete();oldSource?.takeUnless{it.isRecycled}?.recycle();if(oldResult!==oldSource)oldResult?.takeUnless{it.isRecycled}?.recycle();Unit}
        pendingRetirements.add(disposeOld)
        val releaseOld={runOnUiThread{
            if(pendingRetirements.remove(disposeOld)){
                if(departed!=null)retainPhoto(departed,retainEpoch)else disposeOld()
            }
            if(preview===nextPreview && !isDestroyed){
                pageCommitPending=false
                if(commitEpoch==pageEpoch && loadJob?.isActive!=true){
                    setPageControls(true)
                    if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))preloadAdjacentPhotos()
                }
            }
        };Unit}
        displayedPageOffset=0f
        // Do not admit a second gesture before the shared renderer has relinquished the old inputs.
        pageCommitPending=lightTable
        status.text="";status.visibility=android.view.View.GONE;setPageControls(!lightTable)
        if(lightTable)nextPreview.replaceFilm(photo.input,photo.output,photo.readout,releaseOld)else releaseOld()
    }
    private fun discardIncoming(){
        pageEpoch++;pageLoad?.cancel();pageLoad=null
        incomingPreview?.let{it.onPause();stage.removeView(it)};incomingPreview=null
        val unused=incomingPhoto
        val retainEpoch=retentionEpoch
        incomingPhoto=null;incomingReady=false;incomingId=null
        if(unused!=null){
            val dispose={unused.close()}
            pendingRetirements.add(dispose)
            val returned={runOnUiThread{if(pendingRetirements.remove(dispose))retainPhoto(unused,retainEpoch)};Unit}
            if(kind==EffectKind.LIGHT_TABLE){preview?.clearFilmPage(returned) ?: returned()}
            else returned()
        }else if(kind==EffectKind.LIGHT_TABLE)preview?.clearFilmPage()
    }
    private fun cancelPageTransition(){
        val animator=pageAnimator;pageAnimator=null;animator?.cancel()
        discardIncoming();preview?.translationX=0f;displayedPageOffset=0f;draggingPage=false;pageRequested=false;pageDrag=0f
        if(::replayButton.isInitialized && !working && !liveChecking && loadJob?.isActive!=true)setPageControls(true)
    }
    private fun clearPreview(){
        cancelPageTransition()
        clearAdjacentPhotos()
        stopPreviewAudio()
        preview?.onPause();stage.removeAllViews();preview=null
        // The GL views have now stopped and detached; no upload can still access retired inputs.
        pendingRetirements.toList().also{pendingRetirements.clear()}.forEach{it()}
        pageCommitPending=false;currentRecord=null
        sourceMotionFile?.delete();sourceMotionFile=null
        val oldSource=source;val oldResult=result;source=null;result=null
        oldSource?.takeUnless{it.isRecycled}?.recycle()
        if(oldResult!==oldSource)oldResult?.takeUnless{it.isRecycled}?.recycle()
    }
    private fun load(refreshLinks:Boolean=false,autoPlay:Boolean=false){
        loadJob?.cancel();replayButton.isEnabled=false;livePhotoButton.isEnabled=false;exportButton.isEnabled=false;status.visibility=android.view.View.VISIBLE;status.text=t("加载照片…","Loading photo…")
        loadJob=lifecycleScope.launch{
            var ownedOutput:Bitmap?=null;var ownedInput:Bitmap?=null;var ownedMotion:File?=null
            try {
                val record=jobId?.let{id->withContext(Dispatchers.IO){runCatching{dev.filmroom.export.ExportQueue.get(this@PhotoEffectActivity).store.get(id)}.getOrNull()}}
                sourceComposition=EffectSourceComposition.from(record)
                if(refreshLinks && jobId!=null) {
                    if(record!=null){recordSourceUri=record.originalUri;recordResultUri=record.mediaUri}
                    val links=EffectPhotoAccess.restoredLinks(record,sourceUri,resultUri,sourceTemporary,resultTemporary)
                    sourceUri=links.source;resultUri=links.result
                }
                cameraReadout=withContext(Dispatchers.IO){EffectCameraReadout.read(contentResolver,sourceUri,record?.photoMetadata)}
                withContext(Dispatchers.IO){ownedOutput=runCatching{decode(requireNotNull(resultUri))}.getOrNull()}
                val output=ownedOutput
                if(output==null){missing(false);return@launch}
                if(kind==EffectKind.OPTICAL_LEAK)withContext(Dispatchers.IO){ownedInput=runCatching{
                    val raw=decode(requireNotNull(sourceUri))
                    try { (sourceComposition?.apply(raw){ensureActive()} ?: raw).also{if(it!==raw)raw.recycle()} }
                    catch(t:Throwable){raw.recycle();throw t}
                }.getOrNull()}else ownedInput=output
                ensureActive()
                val input=ownedInput
                if(input==null){missing(true);return@launch}
                if(kind==EffectKind.OPTICAL_LEAK)withContext(Dispatchers.IO){ownedMotion=EffectMotionInput.prepare(this@PhotoEffectActivity,sourceUri){ensureActive()}}
                ensureActive()
                clearPreview();currentRecord=record;result=output;source=input;sourceMotionFile=ownedMotion;ownedOutput=null;ownedInput=null;ownedMotion=null
                preview=EffectPreview(input,output,cameraReadout,autoPlay,sourceMotionFile,sourceComposition).also{stage.addView(it,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))};fitPreview();if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))preview?.onPause()
                replayButton.isEnabled=true;livePhotoButton.isEnabled=true;exportButton.isEnabled=true;status.text="";status.visibility=android.view.View.GONE;preloadAdjacentPhotos()
            } finally {
                ownedMotion?.delete()
                ownedInput?.takeUnless{it.isRecycled}?.recycle()
                if(ownedOutput!==ownedInput)ownedOutput?.takeUnless{it.isRecycled}?.recycle()
            }
        }
    }
    private fun pageLayout(photo:Bitmap):FrameLayout.LayoutParams {
        val aspect=EffectGeometry.sceneAspect(kind,photo.width.toFloat()/photo.height)
        val width=minOf(stage.width.toFloat().coerceAtLeast(1f),stage.height.coerceAtLeast(1)*aspect)
        return FrameLayout.LayoutParams(width.toInt().coerceAtLeast(1),(width/aspect).toInt().coerceAtLeast(1),Gravity.CENTER)
    }
    private fun fitPreview(){
        if(stage.width<=0||stage.height<=0)return
        val photo=result ?: return
        preview?.layoutParams=pageLayout(photo)
    }
    private fun missing(original:Boolean){
        clearPreview()
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
        if(liveChecking||pageBusy)return
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
        stopPreviewAudio();preview?.reset()
        qualityButton.isEnabled=false;working=true;liveExport=true;cancelled.set(false);replayButton.isEnabled=false;exportButton.isEnabled=false
        livePhotoButton.text=t("取消导出","Cancel export")
        status.visibility=android.view.View.VISIBLE;status.text=t("导出中 0%","Exporting 0%")
        val quality=selectedQuality
        lifecycleScope.launch {
            val images=EffectExportImages()
            var report:EffectExportReport?=null
            var motion:EffectMotionSource?=null
            try{
                withContext(Dispatchers.IO){images.load(contentResolver,kind,sourceUri,requireNotNull(resultUri),quality,live=true,composition=sourceComposition){cancelled.get()||!isActive}}
                val captureMetadata=withContext(Dispatchers.IO){EffectCaptureMetadata.read(contentResolver,currentRecord?.photoMetadata,sourceUri,resultUri)}
                withContext(Dispatchers.IO){motion=openExportMotion()}
                val saved=EffectLivePhotoExporter.export(applicationContext,kind,images.source,images.result,check,slow=selectedSlow,cancelled={cancelled.get()},onProgress={p->runOnUiThread{if(!isDestroyed){status.text=t("导出中 ${(p*100).toInt()}%","Exporting ${(p*100).toInt()}%")}}},quality=quality,requestedSize=images.requested,onResolution={report=it},readout=cameraReadout,motionSource=motion,motionAudioFile=sourceMotionFile,photoMetadata=captureMetadata)
                status.text=t("Live Photo 已保存，请在相册确认播放效果","Live Photo saved. Check playback in your gallery")+resolutionMessage(report)
                AlertDialog.Builder(this@PhotoEffectActivity).setMessage(status.text).setNegativeButton(t("完成","Done"),null).setPositiveButton(t("查看","View")){_,_->saved.publishedUris.firstOrNull()?.let{uri->runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{saved.selection?.environment?.galleryPackage?.let{setPackage(it)}})}.onFailure{showLiveMessage(t("请打开系统相册查看","Open your gallery to view it"))}}}.show()
            }catch(e:CancellationException){if(!isActive)throw e;status.text=t("已取消导出","Export cancelled")}
            catch(e:OutOfMemoryError){status.text=t("内存不足，请选择较低清晰度后重试","Not enough memory. Choose a lower quality and retry")}
            catch(e:Exception){status.text=if(cancelled.get())t("已取消导出","Export cancelled")else t("Live Photo 导出失败，请重试或导出视频","Live Photo export failed. Retry or export a video")}
            finally{withContext(NonCancellable+Dispatchers.IO){motion?.close()};images.close();working=false;liveExport=false;qualityButton.isEnabled=true;if(!isDestroyed){livePhotoButton.text=t("导出 Live Photo","Export Live Photo");replayButton.isEnabled=true;exportButton.isEnabled=true}}
        }
    }
    private fun export(){
        if(working||liveChecking||pageBusy)return
        if(source==null||result==null)return
        val slow=selectedSlow
        stopPreviewAudio();preview?.reset()
        qualityButton.isEnabled=false;working=true;liveExport=false;cancelled.set(false);exportButton.text=t("取消导出","Cancel export");replayButton.isEnabled=false;livePhotoButton.isEnabled=false
        val quality=selectedQuality
        lifecycleScope.launch{
            val images=EffectExportImages()
            var report:EffectExportReport?=null
            var motion:EffectMotionSource?=null
            try{
                val uri=withContext(Dispatchers.IO){
                    images.load(contentResolver,kind,sourceUri,requireNotNull(resultUri),quality,composition=sourceComposition){cancelled.get()||!isActive}
                    motion=openExportMotion()
                    EffectVideoExporter.export(applicationContext,kind,images.source,images.result,{p->runOnUiThread{if(!isDestroyed){val percent=(p*100).toInt();status.visibility=android.view.View.VISIBLE;status.text=t("导出中 $percent%","Exporting $percent%")}}}, {cancelled.get()||!isActive},slow=slow,quality=quality,requestedSize=images.requested,onResolution={report=it},readout=cameraReadout,motionSource=motion,motionAudioFile=sourceMotionFile)}
                status.visibility=android.view.View.VISIBLE;status.text=t("视频已保存到相册","Video saved to gallery")+resolutionMessage(report)
                AlertDialog.Builder(this@PhotoEffectActivity).setMessage(status.text).setNegativeButton(t("完成","Done"),null).setPositiveButton(t("查看视频","View video")){_,_->runCatching{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{Toast.makeText(this@PhotoEffectActivity,t("没有可播放视频的应用","No video player available"),Toast.LENGTH_SHORT).show()}}.show()
            }catch(e:CancellationException){if(!isActive)throw e;status.visibility=android.view.View.VISIBLE;status.text=t("已取消导出","Export cancelled")}
            catch(e:OutOfMemoryError){status.visibility=android.view.View.VISIBLE;status.text=t("内存不足，请选择较低清晰度后重试","Not enough memory. Choose a lower quality and retry")}
            catch(e:Exception){status.visibility=android.view.View.VISIBLE;status.text=if(cancelled.get())t("已取消导出","Export cancelled")else t("视频导出失败，请重试","Video export failed. Try again")}
            finally{withContext(NonCancellable+Dispatchers.IO){motion?.close()};images.close();working=false;qualityButton.isEnabled=true;if(!isDestroyed){exportButton.text=t("导出视频","Export video");replayButton.isEnabled=true;livePhotoButton.isEnabled=true}}
        }
    }
    private fun openExportMotion():EffectMotionSource? {
        if(kind!=EffectKind.OPTICAL_LEAK)return null
        val file=sourceMotionFile ?: return null
        return try{EffectMotionSource(file,sourceComposition)}catch(_:Exception){null}catch(_:OutOfMemoryError){null}
    }
    private fun leave(){if(!working)finish()else AlertDialog.Builder(this).setMessage(t("取消导出并返回？","Cancel export and return?")).setNegativeButton(t("继续导出","Keep exporting"),null).setPositiveButton(t("返回","Leave")){_,_->cancelled.set(true);finish()}.show()}
    override fun onSaveInstanceState(out:Bundle){out.putString("record_source_uri",recordSourceUri);out.putString("record_result_uri",recordResultUri);out.putBoolean("source_temporary",sourceTemporary);out.putBoolean("result_temporary",resultTemporary);out.putString(JOB_ID,jobId);out.putBoolean("effect_slow",selectedSlow);out.putString("effect_quality",selectedQuality.name);out.putString(RESULT_URI,resultUri);out.putString(SOURCE_URI,sourceUri);super.onSaveInstanceState(out)}
    override fun onResume(){super.onResume();preview?.onResume();if(source!=null)preloadAdjacentPhotos()}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)clearAdjacentPhotos()}
    override fun onPause(){cancelPageTransition();clearAdjacentPhotos();stopPreviewAudio();preview?.reset();preview?.onPause();super.onPause()}
    override fun onDestroy(){clearPreview();stopPreviewAudio();cancelled.set(true);super.onDestroy()}
    private inner class EffectPreview(private var a:Bitmap,private var b:Bitmap,private var readout:EffectCameraReadout,autoPlay:Boolean,private val motionFile:File?=null,private val composition:EffectSourceComposition?=null,private val firstFrame:((EffectPreview)->Unit)?=null):GLSurfaceView(this@PhotoEffectActivity),GLSurfaceView.Renderer{
        private var renderer=EffectGlRenderer(kind,a,b,readout,previewFinder=true)
        private var start=0L;private var widthPx=1;private var heightPx=1;@Volatile private var failed=false;private val playback=EffectPreviewPlayback(autoPlay);private var playingSlow=selectedSlow;private var phase="";private var audioRequested=false;private var reportedFrame=false;private var filmSlide=0f;private var filmDirection=0;private var hadSurface=false
        @Volatile private var frameDrawn=false
        private val motionLifecycle=EffectMotionLifecycleGate()
        @Volatile private var motion:EffectMotionPreview?=null
        @Volatile private var surfaceMotion:EffectSurfaceMotionPreview?=null
        @Volatile private var motionLog:EffectMotionPreviewLog?=null
        private var motionAttempted=false
        private var motionPrimed=false
        private var motionFramePrepared=false
        private var preparedSourceAudio=false
        private var pendingFirstFrame=false
        private var pendingFirstPts:Long?=null
        private var pendingFirstSubmitNs=0L
        private var motionWaitBegan=0L
        override fun onPause(){motionLifecycle.pause();disposeMotion(false,Outcome.CANCELLED);super.onPause()}
        override fun onResume(){motionLifecycle.resume();super.onResume()}
        private fun disposeMotion(releaseGl:Boolean,outcome:Outcome){
            val (surface,old,log)=motionLifecycle.take{Triple(surfaceMotion,motion,motionLog).also{surfaceMotion=null;motion=null;motionLog=null}}
            if(surface!=null)log?.decoder(surface.snapshot())
            surface?.close(releaseGl);old?.close();log?.end(outcome)
        }
        private fun fallbackMotion(reason:MotionPreviewFallback){
            surfaceMotion?.let{motionLog?.decoder(it.snapshot());it.close()};surfaceMotion=null
            if(motionLifecycle.paused)return
            motionLog?.path(MotionPreviewPath.BETA25_FALLBACK,reason)
            val fallback=motionFile?.let{EffectMotionPreview(it,composition)}
            if(!motionLifecycle.publish{motion=fallback})fallback?.close()
        }
        private fun ensureMotion(){
            if(motionAttempted||motionLifecycle.paused)return
            motionAttempted=true
            val file=motionFile?:return
            if(!motionLifecycle.publish{motionLog=EffectMotionPreviewLog()})return
            try {val stream=EffectSurfaceMotionPreview(file,composition)
                if(!motionLifecycle.publish{surfaceMotion=stream}){stream.close();return}
                motionLog?.path(MotionPreviewPath.SURFACE)
            }catch(_:Exception){fallbackMotion(MotionPreviewFallback.SURFACE)}catch(_:OutOfMemoryError){fallbackMotion(MotionPreviewFallback.OUT_OF_MEMORY)}
        }
        private fun tickMotion(timeMs:Long):Boolean {
            surfaceMotion?.let{stream ->
                if(stream.unavailable){val state=stream.snapshot();motionLog?.decoder(state);fallbackMotion(state.failureReason.takeUnless{it==MotionPreviewFallback.NONE}?:MotionPreviewFallback.CODEC)}
                else {stream.request(timeMs,playingSlow);val began=System.nanoTime()
                    try {val frame=stream.render(renderer,timeMs,playingSlow)
                        if(frame!=null){motionLog?.path(MotionPreviewPath.SURFACE,width=stream.sourceWidth,height=stream.sourceHeight,hardware=stream.hardware)
                            if(!motionFramePrepared)motionLog?.decoder(stream.snapshot())
                            recordMotionFrame(frame.timestampNs/1000,timeMs,System.nanoTime()-began);return true}
                        return motionFramePrepared
                    }catch(_:Exception){fallbackMotion(MotionPreviewFallback.SURFACE)}catch(_:OutOfMemoryError){fallbackMotion(MotionPreviewFallback.OUT_OF_MEMORY)}
                }
            }
            val fallback=motion?:return true
            fallback.request(timeMs,playingSlow)
            val frame=fallback.poll()
            if(frame!=null){val began=System.nanoTime();frame.use{if(!renderer.updateMotionFrame(it)){fallback.close();motion=null;motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.SURFACE)}};recordMotionFrame(null,timeMs,System.nanoTime()-began);return true}
            if(fallback.isUnavailable()){fallback.close();motion=null;motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.CODEC);return true}
            return motionFramePrepared
        }
        private fun recordMotionFrame(ptsUs:Long?,timeMs:Long,cpuNs:Long){
            if(!motionPrimed){pendingFirstFrame=true;pendingFirstPts=ptsUs;pendingFirstSubmitNs=cpuNs}else motionLog?.frame(ptsUs,timeMs,cpuNs)
        }
        init{setEGLContextClientVersion(2);setRenderer(this);renderMode=RENDERMODE_CONTINUOUSLY;if(autoPlay)previewAudio.prepare(kind,selectedSlow,motionFile)}
        override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){
            val recreated=hadSurface;hadSurface=true;disposeMotion(false,Outcome.CANCELLED);motionAttempted=false;motionPrimed=false;motionFramePrepared=false;preparedSourceAudio=false;pendingFirstFrame=false;motionWaitBegan=0;reportedFrame=false;frameDrawn=false;filmSlide=0f;filmDirection=0
            runCatching{renderer=EffectGlRenderer(kind,a,b,readout,previewFinder=true);failed=false;renderer.initialize();start=SystemClock.elapsedRealtime();playingSlow=selectedSlow;renderMode=RENDERMODE_CONTINUOUSLY}
                .onFailure{renderError()}
            if(recreated)runOnUiThread{
                if(preview===this && (pageBusy||incomingId!=null))cancelPageTransition()
                else if(incomingPreview===this)cancelPageTransition()
            }
        }
        override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int){widthPx=width;heightPx=height}
        override fun onDrawFrame(gl:GL10?){if(failed)return;runCatching{
            val duration=EffectTimeline.durationMs(kind,playingSlow)
            var elapsed=playback.elapsed(SystemClock.elapsedRealtime(),start,duration)
            if(!playback.stopped&&!motionLifecycle.paused){
                ensureMotion()
                val frameReady=tickMotion(if(motionPrimed)elapsed.coerceIn(0,duration)else 0)
                motionFramePrepared=motionFramePrepared||frameReady
                val audioReady=previewAudio.isPrepared(kind,playingSlow,motionFile)
                var ready=frameReady&&audioReady
                if(!motionPrimed&&!ready){val now=SystemClock.elapsedRealtime();if(motionWaitBegan==0L)motionWaitBegan=now;start=now
                    if(now-motionWaitBegan<1500){renderer.draw(widthPx,heightPx,duration,playingSlow,filmSlide,filmDirection);return}
                    if(EffectMotionWarmupPolicy.retryBitmapDecoder(now-motionWaitBegan,motionFramePrepared,surfaceMotion!=null)) {
                        fallbackMotion(MotionPreviewFallback.TIMEOUT);motionWaitBegan=now
                        renderer.draw(widthPx,heightPx,duration,playingSlow,filmSlide,filmDirection);return
                    }
                    if(!motionFramePrepared){surfaceMotion?.let{motionLog?.decoder(it.snapshot());it.close()};surfaceMotion=null;motion?.close();motion=null;motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.TIMEOUT)}
                    ready=true
                }
                if(!motionPrimed&&ready){motionPrimed=true;preparedSourceAudio=audioReady;motionWaitBegan=0;start=SystemClock.elapsedRealtime();motionLog?.started();if(pendingFirstFrame){motionLog?.frame(pendingFirstPts,0,pendingFirstSubmitNs);pendingFirstFrame=false};elapsed=0}
            }
            renderer.draw(widthPx,heightPx,elapsed.coerceIn(0,duration),playingSlow,filmSlide,filmDirection)
            frameDrawn=true
            if(!reportedFrame){reportedFrame=true;postOnAnimation{firstFrame?.invoke(this)}}
            if(!playback.stopped && !audioRequested){
                audioRequested=true
                val epoch=audioEpoch.get();val began=start;val slow=playingSlow;val prepared=preparedSourceAudio
                runOnUiThread{
                    if(audioEpoch.get()==epoch && preview===this && !working && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                        previewAudio.play(kind,slow,began,motionFile,prepared)
                }
            }
            playback.rendered(elapsed,duration)
            if(playback.stopped){renderMode=RENDERMODE_WHEN_DIRTY;disposeMotion(true,Outcome.SUCCESS)}
        }.onFailure{renderError()}}
        fun isFrameReady()=frameDrawn&&!failed
        fun hasFailed()=failed
        fun restoreCurrentFrame(){
            queueEvent{
                filmSlide=0f;filmDirection=0;playback.stop()
                if(failed)runCatching{renderer.release();renderer=EffectGlRenderer(kind,a,b,readout,previewFinder=true);renderer.initialize();failed=false}.onFailure{
                    failed=true
                    runOnUiThread{
                        status.visibility=android.view.View.VISIBLE;status.text=t("当前设备无法显示此效果","This device could not display the effect")
                        replayButton.isEnabled=false;livePhotoButton.isEnabled=false;exportButton.isEnabled=false
                    }
                }
            };requestRender()
        }
        fun prepareFilm(photo:Bitmap,onReady:()->Unit){
            queueEvent{
                runCatching{renderer.setLightTablePage(photo)}.onSuccess{postOnAnimation{onReady()}}.onFailure{runOnUiThread{if(incomingPhoto?.output===photo)failedPage()}}
            }
            requestRender()
        }
        fun slideFilm(offset:Float,direction:Int){queueEvent{filmSlide=offset;filmDirection=direction;playback.stop()};requestRender()}
        fun clearFilmPage(release:(()->Unit)?=null){queueEvent{filmSlide=0f;filmDirection=0;if(!failed)runCatching{renderer.setLightTablePage(null)};release?.invoke()};requestRender()}
        fun replaceFilm(input:Bitmap,output:Bitmap,camera:EffectCameraReadout,releaseOld:()->Unit){
            queueEvent{
                a=input;b=output;readout=camera
                try{renderer.release();renderer=EffectGlRenderer(kind,a,b,readout,previewFinder=true);renderer.initialize();filmSlide=0f;filmDirection=0;playback.stop()}
                catch(t:Throwable){renderError()}
                finally{releaseOld()}
            }
            requestRender()
        }
        fun replay(){stopPreviewAudio();val slow=selectedSlow;previewAudio.prepare(kind,slow,motionFile);queueEvent{disposeMotion(true,Outcome.CANCELLED);motionAttempted=false;renderer.resetMotionFrame();motionPrimed=false;motionFramePrepared=false;preparedSourceAudio=false;pendingFirstFrame=false;motionWaitBegan=0;audioRequested=false;playingSlow=slow;playback.replay();phase="";start=SystemClock.elapsedRealtime();renderMode=RENDERMODE_CONTINUOUSLY};requestRender()}
        fun reset(){queueEvent{playback.stop();disposeMotion(true,Outcome.CANCELLED);phase=""};requestRender()}
        private fun renderError(){if(failed)return;failed=true;runCatching{disposeMotion(true,Outcome.FAILED)};renderMode=RENDERMODE_WHEN_DIRTY;runOnUiThread{if(incomingPreview===this || (preview===this&&pageBusy)){failedPage();return@runOnUiThread};if(preview!==this)return@runOnUiThread;stopPreviewAudio();status.visibility=android.view.View.VISIBLE;status.text=t("当前设备无法显示此效果","This device could not display the effect");exportButton.isEnabled=false;livePhotoButton.isEnabled=false;replayButton.isEnabled=false}}
    }
}
