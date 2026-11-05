package dev.filmroom.export

import java.io.File
import java.util.Collections
import java.util.LinkedHashMap

/** Optional physical preview, either early low-resolution simulation or exact density samples.
 * The two producers never overwrite one another. No previous attempt may supply new pixels.
 */
internal object NegativePreviewStore {
    // Explicit graceful-failure ceiling for a known in-process worker, not a render deadline.
    // Beyond this limit the optional negative may be skipped; real completion never waits.
    internal const val PENDING_GRACE_MS=5*60*1000L
    private val liveAttempts=Collections.synchronizedMap(object:LinkedHashMap<String,Boolean>(256,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Boolean>?):Boolean=size>256
    })
    private val artifact=Regex("negative-preview-(early-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|status)(\\.part)?")
    private fun bases(id:String)=listOf("negative-preview-early-$id","negative-preview-$id")
    fun begin(id:String){liveAttempts[id]=true}
    fun clear(dir:File) {
        ScanPreviewStore.token(dir)?.let{liveAttempts.remove(it)}
        dir.listFiles()?.filter{artifact.matches(it.name)}.orEmpty().forEach{if(it.isFile)it.delete()}
    }
    private fun status(dir:File,base:String):String?=runCatching {
        File(dir,"$base.status").takeIf{it.isFile && it.length() in 1L..32L}?.readText(Charsets.US_ASCII)?.trim()
    }.getOrNull()
    internal fun producerStatuses(dir:File,id:String):List<String?> = if(ScanPreviewStore.token(dir)==id)bases(id).map{status(dir,it)} else emptyList()
    fun pending(dir:File,now:Long=System.currentTimeMillis()):Boolean {
        val id=ScanPreviewStore.token(dir)?:return false
        if(!liveAttempts.containsKey(id))return false
        // Either producer may still succeed. Slow physical simulation gets a five-minute
        // grace; process-restart orphans are already excluded by liveAttempts above.
        return bases(id).any { base ->
            status(dir,base)=="pending" && (now-File(dir,"$base.status").lastModified()).coerceAtLeast(0) <= PENDING_GRACE_MS
        } && ScanPreviewStore.token(dir)==id
    }
    fun select(dir:File,preferredName:String?=null):File? {
        val id=ScanPreviewStore.token(dir)
        val legacy=File(dir,"negative-preview.png").takeIf{it.isFile && it.length() in 1L..524288L}
        if(preferredName=="negative-preview.png" && legacy!=null)return legacy.takeIf{ScanPreviewStore.token(dir)==id}
        if(id!=null) {
            val candidates=bases(id).sortedBy{if("$it.png"==preferredName)0 else 1}
            for(base in candidates) {
                val image=File(dir,"$base.png")
                if(status(dir,base)=="ready" && image.isFile && image.length() in 1L..524288L && ScanPreviewStore.token(dir)==id)return image
            }
        }
        // CPU compatibility: begin() deletes the old fixed-name image before each attempt.
        return legacy?.takeIf{ScanPreviewStore.token(dir)==id}
    }
    fun alternatives(dir:File):List<File> {
        val id=ScanPreviewStore.token(dir)?:return listOfNotNull(select(dir))
        return (bases(id).mapNotNull{select(dir,"$it.png")}+listOfNotNull(select(dir,"negative-preview.png"))).distinctBy{it.name}
    }
    fun isNegative(file:File?)=file?.let{it.name=="negative-preview.png" || artifact.matches(it.name)&&it.extension=="png"}==true
    fun current(dir:File,file:File)=select(dir,file.name)?.name==file.name
    fun retain(dir:File,file:File):Boolean {
        val id=ScanPreviewStore.token(dir)?:return false
        return file.name=="scan-preview-attempt" || bases(id).any { base ->
            file.name=="$base.png" || file.name=="$base.status" ||
                (pending(dir) && (file.name=="$base.png.part" || file.name=="$base.status.part"))
        }
    }
}
