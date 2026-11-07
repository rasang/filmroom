package dev.filmroom.livephoto.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import dev.filmroom.livephoto.*
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.*

/** Deliberately small validation app. It does not pretend to be an OEM Gallery playback test. */
class MainActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var sdk:LivePhotoSdk
    private var input:Uri?=null
    private var task:Job?=null
    private var result:ConversionResult?=null
    private lateinit var status:TextView
    private lateinit var profile:Spinner
    private lateinit var start:EditText
    private lateinit var duration:EditText
    private lateinit var cover:EditText
    private lateinit var audio:CheckBox
    private lateinit var experimental:CheckBox
    private lateinit var run:Button
    private lateinit var progress:ProgressBar
    private val profiles=LivePhotoProfile.values()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        sdk=LivePhotoSdk(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(24),dp(20),dp(24)) }
        val scroll=ScrollView(this).apply { addView(root) }; setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply { text="视频 → Live Photo"; textSize=26f })
        root.addView(TextView(this).apply { text="0.1.0 alpha · 本地处理 · JPEG / SDR\n输出格式按接收端选择；保存成功不代表原厂相册已验收。"; textSize=14f })
        root.addView(Button(this).apply { text="选择本地视频"; setOnClickListener {
            if(task?.isActive==true)return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="video/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },100)
        } })
        profile=Spinner(this).also { s -> s.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,profiles.map { it.displayName }); root.addView(s) }
        fun number(label:String,default:String):EditText {
            root.addView(TextView(this).apply { text=label })
            return EditText(this).apply { setText(default); inputType=android.text.InputType.TYPE_CLASS_NUMBER; root.addView(this) }
        }
        start=number("开始位置（毫秒）","0")
        duration=number("片段时长（毫秒，默认 3 秒）","3000")
        cover=number("封面相对片段的位置（毫秒；旧 vivo 仅首帧）","0")
        audio=CheckBox(this).apply { text="保留声音"; isChecked=true; root.addView(this) }
        experimental=CheckBox(this).apply { text="允许实验性厂商格式（需真机相册验证）"; root.addView(this) }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; root.addView(this) }
        run=Button(this).apply { text="转换并保存到相册"; setOnClickListener { convert() }; root.addView(this) }
        root.addView(Button(this).apply { text="取消本次转换"; setOnClickListener { task?.cancel() } })
        root.addView(Button(this).apply { text="用相册打开已保存图片"; setOnClickListener {
            val uri=result?.publishedUris?.firstOrNull() ?: return@setOnClickListener toast("请先完成转换")
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch(e:Exception) { toast(e.message ?: "没有可打开此图片的相册") }
        } })
        root.addView(Button(this).apply { text="清理中断发布的残留"; setOnClickListener {
            scope.launch { try { val r=sdk.recoverInterruptedPublications(); status.text="已回滚 ${r.rolledBack.size} 项；保留 ${r.retainedCommitted} 项已完成发布；失败 ${r.failures}" } catch(e:Exception) { status.text=e.toString() } }
        } })
        status=TextView(this).apply { text="未选择视频。\n不会自动上传任何媒体；示例不申请联网权限。"; setTextIsSelectable(true); root.addView(this) }
    }
    private fun convert() {
        if(task?.isActive==true)return
        val uri=input ?: return toast("请先选择视频")
        val request=try {
            VideoToLiveRequest(uri,profiles[profile.selectedItemPosition],start.text.toString().toLong(),duration.text.toString().toLong(),cover.text.toString().toLong(),audio.isChecked,
                allowExperimentalProfiles=experimental.isChecked,saveToGallery=true)
        } catch(e:Exception) { return toast("参数不正确：${e.message}") }
        run.isEnabled=false; result=null
        task=scope.launch {
            try {
                val converted=sdk.convert(request) { update -> runOnUiThread {
                    progress.isIndeterminate=update.percent==null
                    update.percent?.let { progress.progress=it }
                    status.text="${update.stage} ${update.percent?.let { "$it%" }.orEmpty()}"
                } }
                result=converted
                status.text="已保存 ${converted.publishedUris.size} 个媒体文件。\n${converted.asset.profile.displayName}\n封面 PTS：${converted.asset.cover.timestampUs} μs\n${converted.asset.report.warnings.joinToString("\n")}\n\n${converted.publishedUris.joinToString("\n")}"
                // The published files have been hash-verified. Drop only this SDK's private copies.
                withContext(Dispatchers.IO) { sdk.deleteLocalAsset(converted.asset) }
                AlertDialog.Builder(this@MainActivity).setTitle("文件已保存，待相册验收")
                    .setMessage("请在原厂相册检查实况标识、播放、声音、封面和重新打开后的表现。vivo 双文件可能暂时显示为一张图片和一个视频；这不算配对成功。")
                    .setPositiveButton("知道了",null).show()
            } catch(e:TimeoutCancellationException) { status.text="转码超时；未提交项已尝试回滚。" }
            catch(e:CancellationException) { status.text="已取消；未提交项按日志回滚，已经提交的媒体会保留。" }
            catch(e:Exception) { status.text="失败：${(e as? LivePhotoException)?.code ?: e.javaClass.simpleName}\n${e.message}\n${e.suppressed.joinToString { it.message.orEmpty() }}" }
            finally { run.isEnabled=true; progress.isIndeterminate=false }
        }
    }
    @Deprecated("Small framework-only demo; host apps can use Activity Result APIs")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100 && resultCode==RESULT_OK) {
            input=data?.data
            input?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:SecurityException) { }
                status.text="已选视频：$uri"
            }
        }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(text:String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
