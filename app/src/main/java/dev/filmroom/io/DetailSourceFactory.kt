package dev.filmroom.io

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** An owned working grid made ONLY from original source data, never a display preview. */
class DetailSource internal constructor(
    val width:Int,val height:Int,val sourceRevision:String,val mappingRevision:String,
    val relativePath:String,private val cleanup:()->Unit
):AutoCloseable { override fun close()=cleanup() }

class DetailSourceFactory(
    private val suppliedRoot:File,private val memory:()->NativeMemorySnapshot,
    private val writeOwnershipMarker:(File)->Unit={it.writeText("filmroom-detail-v1")}
) {
    // The Context-supplied root may contain Android's trusted /data/user/0 alias.
    // Resolve ONLY this trust boundary; never canonicalize an untrusted descendant.
    private val trustedAliasRoot=suppliedRoot.absoluteFile.toPath()
    private val appRoot:File by lazy { suppliedRoot.canonicalFile }
    private val sources get()=File(appRoot,"cache/detail-preview/sources")
    private fun noTraversal(path:java.nio.file.Path) = path.none { it.toString()=="." || it.toString()==".." }
    internal fun checkedSourceFile(file:File):File {
        val canonicalRoot=appRoot.toPath()
        val absolute=file.absoluteFile.toPath()
        require(noTraversal(trustedAliasRoot) && noTraversal(absolute)) { "Invalid detail source scope" }
        val anchor=when {
            absolute.startsWith(trustedAliasRoot)->trustedAliasRoot
            absolute.startsWith(canonicalRoot)->canonicalRoot
            else->throw IllegalArgumentException("Invalid detail source scope")
        }
        val relative=anchor.relativize(absolute)
        require(relative.nameCount>0 && relative.toString().isNotEmpty()) { "Invalid detail source scope" }
        var current=canonicalRoot
        relative.forEachIndexed { index,component ->
            current=current.resolve(component)
            val attrs=Files.readAttributes(current,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(!attrs.isSymbolicLink && if(index==relative.nameCount-1)attrs.isRegularFile else attrs.isDirectory) { "Invalid detail source scope" }
        }
        // A hardlink can name data outside this owned source tree without any symlink.
        val links=Files.getAttribute(current,"unix:nlink",NOFOLLOW_LINKS) as Number
        require(links.toLong()==1L) { "Invalid detail source scope" }
        return current.toFile()
    }
    /** Known private cache components are checked before creation/listing, never followed. */
    private fun ownedDirectory(relative:String,create:Boolean):File? {
        var current=appRoot.toPath()
        for(component in relative.split('/')) {
            current=current.resolve(component)
            if(!Files.exists(current,NOFOLLOW_LINKS)) {
                if(!create)return null
                Files.createDirectory(current)
            }
            val attrs=Files.readAttributes(current,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink) { "Invalid detail cache scope" }
        }
        return current.toFile()
    }
    /** Source leases prevent cleanup of live work, including work in a different process. */
    fun cleanupOrphans() {
        val sourceDirectory=ownedDirectory("cache/detail-preview/sources",false) ?: return
        sourceDirectory.listFiles()?.filter { it.name.matches(Regex("grid-[a-f0-9-]{36}")) && it.isDirectory && it.canonicalFile==it.absoluteFile }?.forEach { dir ->
            val marker=File(dir,".owned-detail-source-v1");val lease=File(dir,".lease")
            if(marker.isFile && marker.canonicalFile==marker.absoluteFile && marker.length()==18L && marker.readText()=="filmroom-detail-v1" && lease.isFile && lease.canonicalFile==lease.absoluteFile){
                RandomAccessFile(lease,"rw").use { f ->
                    val lock=try{f.channel.tryLock()}catch(_:OverlappingFileLockException){null}
                    lock?.use { deleteOwnedTree(dir) }
                }
            }
        }
    }
    suspend fun prepare(source:PhotoSource,maxEdge:Int=1536):DetailSource {
        var owned:DetailSource?=null
        try { return withContext(Dispatchers.IO) {
        require(maxEdge in 1..1536)
        source.retainDetailSource().use {
            val job=currentCoroutineContext();job.ensureActive()
            val originalFile=checkedSourceFile(source.localFile)
            val linearFile=source.linearFile?.let(::checkedSourceFile)
            ownedDirectory("cache/detail-preview/sources",true)
            ownedDirectory("cache/detail-preview/sessions",true)
            val dir=File(sources,"grid-${UUID.randomUUID()}");check(dir.mkdir())
            var acquiredLease:RandomAccessFile?=null
            var acquiredLock:java.nio.channels.FileLock?=null
            var transferred=false
            try {
                val lease=RandomAccessFile(File(dir,".lease"),"rw").also{acquiredLease=it}
                val lock=lease.channel.lock().also{acquiredLock=it}
                writeOwnershipMarker(File(dir,".owned-detail-source-v1"))
                val size=source.outputSize(maxEdge)
                val originalBytes=source.originalWidth.toLong()*source.originalHeight*12
                val gridBytes=size.width.toLong()*size.height*12
                check(dir.usableSpace>gridBytes+(if(source.linearFile==null)originalBytes else 0)+64L*1024*1024) { "Detail storage budget unavailable" }
                val original=linearFile ?: File(dir,"original.f32le")
                val target=File(dir,"grid.f32le")
                NativeWorkReservations.run(128L*1024*1024,memory) {
                    if(source.linearFile==null)OriginalSpool.write(originalFile,source.originalWidth,source.originalHeight,original,job)
                    LinearSpool.resize(original,source.originalWidth,source.originalHeight,target,size,job)
                }
                val revision=sha256(linearFile ?: originalFile,job)
                if(source.linearFile==null)original.delete()
                val mapping="oriented-original-v1:${source.mimeType}:${source.originalWidth}x${source.originalHeight}:linear=${source.linearFile!=null}:bilinear-center-v1:${size.width}x${size.height}:grid=${sha256(target,job)}"
                job.ensureActive()
                val result=DetailSource(size.width,size.height,revision,mapping,"sources/${dir.name}/grid.f32le") {
                    synchronized(lease){if(lease.channel.isOpen){try{deleteOwnedTree(dir)}finally{try{lock.release()}finally{lease.close()}}}}
                }
                owned=result;transferred=true;result
            } finally {if(!transferred){try{deleteOwnedTree(dir)}finally{try{acquiredLock?.release()}finally{acquiredLease?.close()}}}}
        }
    } } catch(t:Throwable){withContext(NonCancellable+Dispatchers.IO){owned?.close()};throw t}
    }
    companion object {
        /** Never traverse a link even inside a marked owned directory. */
        private fun deleteOwnedTree(dir:File) {
            java.nio.file.Files.walkFileTree(dir.toPath(),object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>(){
                override fun visitFile(path:java.nio.file.Path,attrs:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {
                    // Keep ownership proof until all heavy children are removed, so failed cleanup can retry.
                    if(path.parent!=dir.toPath() || path.fileName.toString() !in setOf(".owned-detail-source-v1",".lease"))java.nio.file.Files.deleteIfExists(path)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path:java.nio.file.Path,error:java.io.IOException?):java.nio.file.FileVisitResult {
                    if(error!=null)throw error
                    if(path==dir.toPath()){
                        java.nio.file.Files.deleteIfExists(path.resolve(".owned-detail-source-v1"))
                        java.nio.file.Files.deleteIfExists(path.resolve(".lease"))
                    }
                    java.nio.file.Files.deleteIfExists(path);return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }

        internal fun sha256(file:File,job:kotlin.coroutines.CoroutineContext):String {
            require(file.isFile && file.canonicalFile==file.absoluteFile)
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->val b=ByteArray(65536);while(true){job.ensureActive();val n=input.read(b);if(n<0)break;md.update(b,0,n)}}
            return md.digest().joinToString(""){"%02x".format(it)}
        }
    }
}
