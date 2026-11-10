package dev.filmroom.update

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*
import android.graphics.drawable.GradientDrawable
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dev.filmroom.ui.FilmroomPalette
import kotlinx.coroutines.*

/** Only unordered Markdown list markers are interpreted. No HTML, images or auto-linked URLs. */
fun renderReleaseNotes(value:String):String = value.split('\n').joinToString("\n") {
    it.replace(Regex("^([ \\t]*)[-*+] +"), "$1• ")
}

class UpdateSettingsUi(private val activity:ComponentActivity,
    private val endpoint:String=UpdateConfig.MANIFEST_URL,
    private val check:(String)->UpdateManifest=UpdateRepository(preferenceCache(activity.applicationContext))::check,
    private val openLink:(String)->Unit={url->activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))}
) {
    private val installed=activity.packageManager.getPackageInfo(activity.packageName,0)
    private var job:Job?=null
    private var dialog:AlertDialog?=null
    private var closed=false
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun text(zh:String,en:String,ja:String,ko:String):String=when(activity.resources.configuration.locales[0].language){"zh"->zh;"ja"->ja;"ko"->ko;else->en}
    private val closeLabel get()=text("关闭","Close","閉じる","닫기")
    private val checkLabel get()=text("检测更新","Check for updates","更新を確認","업데이트 확인")
    private fun label(value:String)=TextView(activity).apply{this.text=value;setTextColor(FilmroomPalette.ink);textSize=14f;setPadding(0,dp(6),0,dp(6))}
    fun addTo(parent:LinearLayout) {
        val block=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;tag="settings-section:update";setPadding(dp(16),dp(12),dp(16),dp(12));background=GradientDrawable().apply{setColor(FilmroomPalette.surface);cornerRadius=dp(16).toFloat()}}
        block.addView(label(text("应用更新","App updates","アプリの更新","앱 업데이트")))
        block.addView(label(text("当前版本：","Current version: ","現在のバージョン：","현재 버전: ")+(installed.versionName ?: "")))
        val button=Button(activity).apply{
            text=checkLabel;tag="check-updates";isAllCaps=false;textSize=16f;minHeight=dp(48)
            setTextColor(FilmroomPalette.onAccent)
            background=GradientDrawable().apply{setColor(FilmroomPalette.accent);cornerRadius=dp(12).toFloat()}
        }
        block.addView(button,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)})
        parent.addView(block,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(24)})
        if(activity.packageName=="dev.filmroom.homemotion") {
            button.isEnabled=false
            button.text=text("实验版不检测正式版更新","Experimental build: updates disabled","実験版：更新確認は無効","실험 버전: 업데이트 비활성화")
            return
        }
        button.setOnClickListener {
            if(closed||job?.isActive==true)return@setOnClickListener
            if(endpoint.isBlank()){message(text("更新地址尚未配置","Update address is not configured","更新先が未設定です","업데이트 주소가 설정되지 않았습니다"));return@setOnClickListener}
            button.isEnabled=false;button.text=text("正在检测…","Checking…","確認中…","확인 중…")
            job=activity.lifecycleScope.launch {
                try {
                    val fetch=check;val address=endpoint
                    val result=UpdateChecks.request(activity,false){fetch(address)}!!.await()
                    if(!closed&&!activity.isFinishing&&!activity.isDestroyed) {
                        if(result.isNewerThan(installed.longVersionCode)){if(UpdateChecks.claimResult(result))showUpdate(result)}
                        else message(text("暂无新版本","No newer version available","新しいバージョンはありません","새 버전이 없습니다"))
                    }
                } catch(e:TimeoutCancellationException){if(!closed)failure()}
                catch(e:CancellationException){throw e}
                catch(e:Exception){if(!closed)failure()}
                finally {button.isEnabled=true;button.text=checkLabel}
            }
        }
    }
    fun checkOnColdStart() {
        if(activity.packageName=="dev.filmroom.homemotion" || closed||endpoint.isBlank())return
        val fetch=check;val address=endpoint
        val task=UpdateChecks.request(activity,true){fetch(address)} ?: return
        job=activity.lifecycleScope.launch {
            try {
                val result=task.await()
                activity.lifecycle.withResumed {
                    if(!closed&&!activity.isFinishing&&result.isNewerThan(installed.longVersionCode)&&UpdateChecks.claimResult(result))showUpdate(result)
                }
            } catch(e:CancellationException){throw e}
            catch(_:Exception){/* Automatic checks are silent on network/schema failures. */}
        }
    }
    private fun failure()=message(text("检测失败，请稍后重试","Could not check for updates. Try again later.","確認できませんでした。後でもう一度お試しください","확인하지 못했습니다. 나중에 다시 시도해 주세요"))
    private fun message(value:String){if(!activity.isFinishing&&!activity.isDestroyed)Toast.makeText(activity,value,Toast.LENGTH_LONG).show()}
    fun showUpdate(manifest:UpdateManifest) {
        if(closed)return
        visibleDialog?.get()?.dismiss()
        dialog?.dismiss()
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(16))}
        if(manifest.releaseNotes.isNotBlank())body.addView(label(renderReleaseNotes(manifest.releaseNotes)))
        manifest.downloads.forEachIndexed {index,link->
            body.addView(Button(activity).apply {
                tag="update-download:$index";isAllCaps=false;setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null;setHorizontallyScrolling(false)
                text=link.name+"\n"+java.net.URI(link.url).host
                setOnClickListener {try{openLink(SafeUpdateUrl.requireHttps(link.url))}catch(e:Exception){message(text("无法打开链接，请尝试其他下载地址","Could not open link. Try another download link.","リンクを開けません。他のリンクをお試しください","링크를 열 수 없습니다. 다른 링크를 시도해 주세요"))}}
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        }
        dialog=AlertDialog.Builder(activity).setTitle(text("发现新版本 ","New version ","新しいバージョン ","새 버전 ")+manifest.versionName)
            .setView(ScrollView(activity).apply{addView(body)})
            .setNegativeButton(closeLabel){_,_->}.create().also{it.show();visibleDialog=java.lang.ref.WeakReference(it)}
    }
    fun close(){closed=true;job?.cancel();dialog?.dismiss();dialog=null}
    companion object {
        private var visibleDialog:java.lang.ref.WeakReference<AlertDialog>?=null
        private fun preferenceCache(activity:android.content.Context):UpdateCache {
            val prefs=activity.getSharedPreferences("update_manifest_cache",0)
            return object:UpdateCache {
                override fun read():UpdateCache.Entry? {
                    val url=prefs.getString("url",null)?:return null
                    val body=prefs.getString("body",null)?:return null
                    return UpdateCache.Entry(url,body,prefs.getString("etag",null))
                }
                override fun write(entry:UpdateCache.Entry){prefs.edit().putString("url",entry.url).putString("body",entry.body).putString("etag",entry.etag).apply()}
            }
        }
    }
}
