package dev.filmroom.livephoto.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import dev.filmroom.livephoto.*
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.*

/** Explicit gallery feedback is never inferred from export, activity launch or onResume. */
class MainActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var sdk:LivePhotoSdk
    private var input:Uri?=null
    private var task:Job?=null
    private var result:ConversionResult?=null
    private var openedAssetId:String?=null
    private lateinit var status:TextView
    private lateinit var profile:Spinner
    private lateinit var target:Spinner
    private lateinit var gallery:Spinner
    private var galleries:List<GalleryTarget> = emptyList()
    private lateinit var start:EditText
    private lateinit var duration:EditText
    private lateinit var cover:EditText
    private lateinit var audio:CheckBox
    private lateinit var experimental:CheckBox
    private lateinit var run:Button
    private lateinit var progress:ProgressBar
    private val profiles=LivePhotoProfile.values()
    private val brands=TargetBrand.values()
    private val brandNames=listOf("OPPO / 一加 / realme","vivo / iQOO","小米 / Redmi / POCO","荣耀","三星","其他 / Google 通用")

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        sdk=LivePhotoSdk(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(24),dp(20),dp(24)) }
        val scroll=ScrollView(this).apply { addView(root) };setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v,insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)
            insets
        }
        fun label(text:String) { root.addView(TextView(this).apply { this.text=text }) }
        fun button(text:String,action:()->Unit):Button = Button(this).apply { this.text=text;setOnClickListener { action() };root.addView(this) }
        fun spinner(items:List<String>):Spinner = Spinner(this).also {
            it.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items);root.addView(it)
        }
        fun number(text:String,value:String):EditText {
            label(text)
            return EditText(this).apply { setText(value);inputType=android.text.InputType.TYPE_CLASS_NUMBER;root.addView(this) }
        }
        root.addView(TextView(this).apply { text="视频 → Live Photo";textSize=26f })
        label("alpha02 · 自动选择 / 兼容记录 · JPEG / SDR\n未知不代表不支持；保存成功不代表相册识别。")
        button("选择本地视频") {
            if(task?.isActive!=true) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="video/*";addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },100)
        }
        label("在哪里查看？默认本机；跨机生成请选择接收端品牌")
        target=spinner(listOf("本机（自动识别品牌）")+brandNames)
        label("目标相册：用于绑定测试记录，不会根据能打开 JPG 就认定支持实况")
        gallery=spinner(listOf("自动 / 默认图片查看器"))
        label("封装格式：正常使用选自动；手动选择可重试失败配置")
        profile=spinner(listOf("自动（优先已验证格式）")+profiles.map { it.displayName })
        start=number("开始位置（毫秒）","0")
        duration=number("片段时长（毫秒）","3000")
        cover=number("封面相对片段位置（毫秒）","0")
        audio=CheckBox(this).apply { text="保留声音";isChecked=true;root.addView(this) }
        experimental=CheckBox(this).apply { text="允许尝试实验性厂商格式";root.addView(this) }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100;root.addView(this) }
        button("检查支持情况 / 当前候选") {
            scope.launch {
                try { status.text=describe(sdk.resolveProfile(request(probeOnly=true))) }
                catch(e:Exception) { status.text="检查失败（不记为不支持）：${e.message}" }
            }
        }
        run=button("转换并保存到相册") { convert() }
        button("取消转换") { task?.cancel() }
        button("打开目标相册验收") { openGallery() }
        button("播放正常，记住此格式") { confirm(SupportStatus.SUPPORTED) }
        button("相册不识别此格式，下次换一个") { confirm(SupportStatus.UNSUPPORTED) }
        button("清除本机兼容记录") {
            if(task?.isActive!=true) AlertDialog.Builder(this).setTitle("清除兼容记录？")
                .setMessage("只清除格式验收记录，不删除任何照片或视频。")
                .setPositiveButton("清除") { _,_ -> scope.launch {
                    try { sdk.compatibility.clearRecords();status.text="已清除兼容记录；各格式恢复未知。" }
                    catch(e:Exception) { status.text=e.toString() }
                } }.setNegativeButton("取消",null).show()
        }
        button("清理中断发布的残留") {
            scope.launch { try { val r=sdk.recoverInterruptedPublications();status.text="已回滚 ${r.rolledBack.size} 项；失败 ${r.failures}" }
                catch(e:Exception) { status.text=e.toString() } }
        }
        status=TextView(this).apply { text="未选择视频。首次没有验收记录时显示未知。\n仅本地处理，不上传媒体或兼容记录。";setTextIsSelectable(true);root.addView(this) }
        scope.launch {
            galleries=sdk.compatibility.availableGalleries()
            gallery.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("自动 / 默认图片查看器")+galleries.map { "${it.label} (${it.packageName})" })
        }
    }
    private fun request(probeOnly:Boolean=false):VideoToLiveRequest {
        val uri=input ?: if(probeOnly) Uri.parse("content://dev.filmroom.livephoto/selection-only") else error("请先选择视频")
        return VideoToLiveRequest(uri,profile=profiles.getOrNull(profile.selectedItemPosition-1),
            startMs=start.text.toString().toLong(),durationMs=duration.text.toString().toLong(),
            coverOffsetMs=cover.text.toString().toLong(),keepAudio=audio.isChecked,
            allowExperimentalProfiles=experimental.isChecked,
            targetBrand=brands.getOrNull(target.selectedItemPosition-1),
            targetGalleryPackage=if(target.selectedItemPosition==0) galleries.getOrNull(gallery.selectedItemPosition-1)?.packageName else null)
    }
    private fun describe(s:ProfileSelection):String =
        "当前：${s.selectedProfile?.displayName ?: "无未失败候选"} · ${state(s.status)}\n"+
        "依据：${s.reason}\n相册：${s.environment?.galleryPackage ?: "未知或远端"}\n"+
        s.candidates.joinToString("\n") { "${it.profile.displayName}：${state(it.status)}" }+"\n"+s.warnings.joinToString("\n")
    private fun state(s:SupportStatus)=when(s) { SupportStatus.SUPPORTED->"已有用户确认";SupportStatus.UNSUPPORTED->"此配置有失败记录";SupportStatus.UNKNOWN->"未知 / 尚未验收" }
    private fun convert() {
        if(task?.isActive==true)return
        val request=try { request() } catch(e:Exception) { return toast(e.message ?: "参数错误") }
        run.isEnabled=false;result=null;openedAssetId=null
        task=scope.launch {
            try {
                val converted=sdk.convert(request) { update -> runOnUiThread {
                    progress.isIndeterminate=update.percent==null;update.percent?.let { progress.progress=it }
                    status.text="${update.stage} ${update.percent?.let { "$it%" }.orEmpty()}"
                } }
                result=converted
                status.text="已保存 ${converted.publishedUris.size} 个文件。此输出尚未验收。\n"+describe(requireNotNull(converted.selection))
                withContext(Dispatchers.IO) { sdk.deleteLocalAsset(converted.asset) }
                AlertDialog.Builder(this@MainActivity).setTitle("已保存，请在目标相册验收")
                    .setMessage("点击打开目标相册，检查实况标识、播放、声音和封面；退出重开后再确认。然后返回本页记录结果。不会自动把保存成功记为支持。")
                    .setPositiveButton("知道了",null).show()
            } catch(e:TimeoutCancellationException) { status.text="转码超时；兼容记录未改变。" }
            catch(e:CancellationException) { status.text="已取消；兼容记录未改变。" }
            catch(e:Exception) { status.text="失败：${(e as? LivePhotoException)?.code ?: e.javaClass.simpleName}\n${e.message}\n此次转换错误不记为相册不支持。" }
            finally { run.isEnabled=true;progress.isIndeterminate=false }
        }
    }
    private fun openGallery() {
        val r=result ?: return toast("请先转换")
        val uri=r.publishedUris.firstOrNull() ?: return toast("尚未保存相册")
        try {
            val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            r.selection?.environment?.galleryPackage?.let { intent.setPackage(it) }
            startActivity(intent);openedAssetId=r.asset.id
        } catch(e:Exception) { toast("无法打开（不记为不支持）：${e.message}") }
    }
    private fun confirm(answer:SupportStatus) {
        val r=result ?: return toast("请先转换并在目标相册查看")
        if(task?.isActive==true)return
        if(r.selection?.environment?.canRemember!=true)return toast("目标相册未知或为远端。请先选定本机相册，重新生成后验收。")
        if(openedAssetId!=r.asset.id)return toast("请先点击打开目标相册验收")
        val ok=answer==SupportStatus.SUPPORTED
        AlertDialog.Builder(this).setTitle(if(ok) "确认此格式播放正常？" else "确认是相册不识别？")
            .setMessage("${r.asset.profile.displayName}\n相册：${r.selection?.environment?.galleryPackage}\n"+
                if(ok) "确认实况标识、播放、声音与封面正常，退出重开后仍可播放。仅记录此环境和导出参数。"
                else "只在完整文件已保存、相册仍无法识别时记录。权限不足、等待扫描或转换报错不要记为不支持。下次自动选择另一候选。")
            .setPositiveButton("确认") { _,_ -> scope.launch {
                try {
                    sdk.compatibility.recordGalleryResult(r,answer)
                    profile.setSelection(0)
                    status.text="已记录：${state(answer)}。\n"+describe(sdk.resolveProfile(request(probeOnly=true)))
                } catch(e:Exception) { status.text="未记录：${e.message}" }
            } }.setNegativeButton("取消",null).show()
    }
    @Deprecated("Small framework-only demo")
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
    override fun onDestroy() { scope.cancel();super.onDestroy() }
}
