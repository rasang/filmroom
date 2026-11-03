package dev.filmroom.engine

import android.content.Context
import dev.filmroom.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.SecureRandom
import org.json.JSONObject

/** Process-owned, one-shot capture. Never serialized into an editing preset or queue. */
internal object ProbeCaptureRequestStore {
    const val DISK_BYTES=64L*1024*1024
    const val MEMORY_BYTES=2L*1024*1024
    data class Request(val token:Long,val directory:File)
    private var armed=false
    private var capturing:Request?=null
    private var ready:Request?=null
    private var cancelledToken:Long?=null
    private var message="未准备 GPU 单块诊断 / No GPU diagnostic snapshot"
    @Synchronized fun arm(context:Context):Boolean {
        if(!BuildConfig.PERF_BUILD || capturing!=null || ready!=null)return false
        val root=root(context) ?: return false
        // Unknown/orphan ownership is never deleted speculatively while another process may read it.
        if(root.listFiles()?.isNotEmpty()!=false){message="发现上次遗留诊断快照，已安全停用。请在系统应用设置强行停止 Filmroom Perf，再清除缓存（不要清除数据）。 / Orphan snapshot: force stop Perf, then clear cache only.";return false}
        armed=true;message="已准备：下次全新 CPU 导出保存柔光单块 / Next fresh CPU export will capture a block";return true
    }
    @Synchronized fun consume(context:Context):Request? {
        if(!BuildConfig.PERF_BUILD || !armed || capturing!=null || ready!=null)return null
        armed=false
        val root=root(context) ?: return null
        if(root.listFiles()?.isNotEmpty()!=false || root.usableSpace<DISK_BYTES){message="空间不足或快照正在使用，已跳过 / Capture skipped";return null}
        var token:Long
        do {token=SecureRandom().nextLong() and Long.MAX_VALUE}while(token==0L)
        return Request(token,File(root,"probe-$token")).also{capturing=it;message="正在保存 CPU 参考，尚未运行 GPU / Capturing CPU reference"}
    }
    @Synchronized fun finish(request:Request?,nativeResult:JSONObject?,success:Boolean) {
        if(request==null || capturing?.token!=request.token)return
        capturing=null
        val info=(nativeResult?.optJSONObject("metrics") ?: nativeResult)?.optJSONObject("gpuProbeCapture")
        val status=info?.optString("status") ?: "NOT_CAPTURED"
        if(success && cancelledToken!=request.token && status=="captured" && Files.isRegularFile(File(request.directory,"manifest.json").toPath(),NOFOLLOW_LINKS)) {
            ready=request;message="CPU 参考已准备；点击运行 GPU 单块诊断 / CPU reference ready"
        }else{
            if(status=="captured")deleteOwned(request);message="GPU 快照未生成 / Snapshot skipped: "+when(status){
                "skipped_limit"->"原始柔光核超过单块上限"
                "skipped_unsupported"->"预算不足"
                "skipped_resumed"->"续跑已跳过柔光阶段"
                "skipped_inactive"->"未启用镜头柔光"
                "skipped_cancelled"->"已取消"
                else->"本次不符合诊断条件"
            }
        }
    }
    @Synchronized fun readyToken():Long?=ready?.token
    @Synchronized fun transferred(token:Long){if(ready?.token==token){ready=null;message="GPU 单块诊断已提交 / Probe submitted"}}
    @Synchronized fun cancelPending(){armed=false;cancelledToken=capturing?.token;if(capturing==null){ready?.let(::deleteOwned);ready=null;message="已取消待运行诊断 / Pending probe cancelled"}}
    @Synchronized fun summary():String=message
    private fun root(context:Context):File?=runCatching{
        val cache=context.applicationContext.cacheDir.canonicalFile
        val root=File(cache,"gpu-probes")
        if(!root.exists() && !root.mkdir())return null
        if(!Files.isDirectory(root.toPath(),NOFOLLOW_LINKS)||root.canonicalFile!=root.absoluteFile)return null
        root
    }.getOrNull()
    private fun deleteOwned(request:Request){
        val dir=request.directory
        if(dir.name!="probe-${request.token}" || !Files.isDirectory(dir.toPath(),NOFOLLOW_LINKS))return
        val allowed=setOf("manifest.json","source.f64le","kernel.f64le","reference.f64le")
        val files=dir.listFiles() ?: return
        if(files.any{it.name !in allowed || !Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS)})return
        files.forEach{it.delete()};dir.delete()
    }
}
