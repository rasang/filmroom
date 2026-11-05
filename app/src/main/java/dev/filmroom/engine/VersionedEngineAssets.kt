package dev.filmroom.engine

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Versioned preparation without changing the native trust root (files/engine-data).
 * Preview/export callers share one installation gate. Each file is atomically replaced and the
 * version manifest is committed LAST, after verification. Failure never memoizes success. No old
 * trees accumulate. APK updates kill the app process; no active handle spans an application update.
 */
internal object VersionedEngineAssets {
    private val mutex=Mutex()
    private var cached:Prepared?=null
    private data class Stamp(val path:String,val size:Long,val modified:Long)
    private data class Prepared(val key:String,val root:File,val files:List<Stamp>) {
        fun unchanged()=root.isDirectory && files.all { val file=File(root,it.path);file.isFile && file.length()==it.size && file.lastModified()==it.modified }
    }
    suspend fun prepare(context:Context):File=mutex.withLock {
        val job=currentCoroutineContext()
        val app=context.applicationContext
        val info=app.packageManager.getPackageInfo(app.packageName,0)
        val apk=File(app.applicationInfo.sourceDir ?: "")
        val version="assets-v2:${info.longVersionCode}:${info.lastUpdateTime}:${apk.length()}:${apk.lastModified()}"
        val root=File(app.filesDir,"engine-data")
        val key="${root.absolutePath}:$version"
        cached?.takeIf{it.key==key && it.unchanged()}?.let{job.ensureActive();return@withLock it.root}
        job.ensureActive()
        val files=mutableListOf<Stamp>()
        val manifest=StringBuilder(version).append('\n')
        fun copy(asset:String,relative:String){
            job.ensureActive()
            val children=app.assets.list(asset) ?: emptyArray()
            if(children.isNotEmpty())children.sorted().forEach{copy("$asset/$it",if(relative.isEmpty())it else "$relative/$it")}
            else {
                val path=if(asset==PackedSpectralLut.ASSET)relative.substringBeforeLast('/')+"/"+PackedSpectralLut.ORIGINAL_NAME else relative
                val destination=File(root,path)
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                if(asset==PackedSpectralLut.ASSET)app.assets.open(asset).use { PackedSpectralLut.install(it,destination,app.filesDir,{job.ensureActive()}) }
                else {
                    // Streaming comparison bounds scratch and also detects same-size corruption.
                    val digest=MessageDigest.getInstance("SHA-256")
                    val temporary=File.createTempFile("prepare-",".tmp",destination.parentFile)
                    try {
                        app.assets.open(asset).use { input -> FileOutputStream(temporary).use { output ->
                            val buffer=ByteArray(64*1024)
                            while(true){job.ensureActive();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);output.write(buffer,0,n)}
                            output.fd.sync()
                        }}
                        val expected=digest.digest()
                        if(!destination.isFile || destination.length()!=temporary.length() || !hashFile(destination){job.ensureActive()}.contentEquals(expected)){
                            job.ensureActive();check(temporary.renameTo(destination))
                        }
                    }finally{temporary.delete()}
                }
                job.ensureActive()
                val hash=hashFile(destination){job.ensureActive()}.joinToString(""){"%02x".format(it)}
                files+=Stamp(path,destination.length(),destination.lastModified())
                manifest.append(path).append('\t').append(destination.length()).append('\t').append(hash).append('\n')
            }
        }
        cached=null
        copy("engine-data","")
        check(files.any{it.path=="profile_catalog.json"})
        val marker=File(root,".prepared-v2")
        val temporary=File.createTempFile("prepare-",".tmp",root)
        try {
            FileOutputStream(temporary).use{it.write(manifest.toString().toByteArray());it.fd.sync()}
            job.ensureActive();check(temporary.renameTo(marker))
        }finally{temporary.delete()}
        files+=Stamp(marker.name,marker.length(),marker.lastModified())
        cached=Prepared(key,root,files)
        root
    }
    private fun hashFile(file:File,checkCurrent:()->Unit):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true){checkCurrent();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        return digest.digest()
    }
    internal suspend fun clearMemoryForTest()=mutex.withLock { cached=null }
}
