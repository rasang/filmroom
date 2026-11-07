package dev.filmroom.livephoto.core

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Backend operations must be restricted to the hosting app's own rows. IDs are opaque, e.g. content Uris. */
interface MediaSink {
    fun insertPending(displayName: String, mimeType: String, relativePath: String): String
    fun copyAndVerify(id: String, source: File, expectedSha256: String, checkActive: () -> Unit)
    fun publish(id: String)
    fun deleteOwned(id: String): Boolean
    fun existsOwned(id: String): Boolean
    fun findOwned(displayName: String, mimeType: String, relativePath: String): List<String>
}

data class PublishedAsset(val assetId: String, val ids: List<String>, val galleryConfirmed: Boolean = false)
data class RecoveryReport(val rolledBack: List<String>, val retainedCommitted: Int, val failures: Map<String, String>)

/**
 * Durable publish journal with idempotence and rollback. Cross-file visibility is NOT atomic.
 * A process death before COMMITTED is handled by rollback, not automatic continuation.
 * No deletion is attempted without this SDK's journal and an ownership check in the backend.
 */
class PublicationCoordinator(private val directory: File, private val sink: MediaSink) {
    private data class Record(
        val id: String, var state: String, val path: String,
        val names: List<String>, val mimes: List<String>, val hashes: List<String>,
        val rows: MutableList<String>
    )
    init { Files.createDirectories(directory.toPath()) }
    private fun validateId(id: String) = demand(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),ErrorCode.INVALID_REQUEST,"Invalid asset ID")
    private fun journal(id: String) = File(directory,"$id.properties")
    private fun save(r: Record) {
        val p=Properties().apply {
            setProperty("id",r.id); setProperty("state",r.state); setProperty("path",r.path); setProperty("count",r.names.size.toString())
            for(i in r.names.indices) { setProperty("name.$i",r.names[i]); setProperty("mime.$i",r.mimes[i]); setProperty("hash.$i",r.hashes[i]); setProperty("row.$i",r.rows[i]) }
        }
        val tmp=File(directory,"${r.id}.tmp")
        FileOutputStream(tmp).use { p.store(it,"Filmroom LivePhoto publication journal v1"); it.fd.sync() }
        try { Files.move(tmp.toPath(),journal(r.id).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(e:AtomicMoveNotSupportedException) { Files.move(tmp.toPath(),journal(r.id).toPath(),StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun load(id: String): Record? {
        val file=journal(id); if(!file.isFile)return null
        demand(file.length() in 1..65536,ErrorCode.PUBLISH_FAILED,"Invalid journal size")
        val p=Properties().apply { file.inputStream().use { load(it) } }
        demand(p.getProperty("id")==id,ErrorCode.PUBLISH_FAILED,"Journal ID mismatch")
        val count=p.getProperty("count")?.toIntOrNull() ?: 0
        demand(count in 1..2,ErrorCode.PUBLISH_FAILED,"Invalid journal part count")
        fun values(key:String)=List(count) { p.getProperty("$key.$it") ?: throw LivePhotoException(ErrorCode.PUBLISH_FAILED,"Incomplete journal") }
        return Record(id,p.getProperty("state") ?: "UNKNOWN",p.getProperty("path") ?: "",values("name"),values("mime"),values("hash"),values("row").toMutableList())
    }
    private fun validatePath(path: String) {
        val parts=path.trimEnd('/').split('/')
        demand(path.endsWith('/') && path.length<=240 && parts.size>=2 && parts.first()=="DCIM" && parts.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) },ErrorCode.INVALID_REQUEST,"Use a safe DCIM subdirectory, e.g. DCIM/Filmroom/")
    }
    private fun <T> locked(id:String,block:()->T):T {
        validateId(id)
        RandomAccessFile(File(directory,"$id.lock"),"rw").use { f ->
            val lock=try { f.channel.tryLock() } catch(_:OverlappingFileLockException) { null }
            demand(lock!=null,ErrorCode.BUSY,"Publication is already active")
            return lock!!.use { block() }
        }
    }
    private fun rollback(r:Record):List<String> {
        val failed=mutableListOf<String>()
        for(i in r.names.indices) {
            // Finding exact journaled names closes the crash window between insert and journaling its Uri.
            val ids=linkedSetOf<String>()
            if(r.rows[i].isNotEmpty())ids += r.rows[i]
            try { ids += sink.findOwned(r.names[i],r.mimes[i],r.path) } catch(e:Exception) { failed += "find:${r.names[i]}:${e.javaClass.simpleName}" }
            for(id in ids) try { if(!sink.deleteOwned(id))failed += id } catch(e:Exception) { failed += "$id:${e.javaClass.simpleName}" }
        }
        if(failed.isEmpty()) {
            demand(journal(r.id).delete() || !journal(r.id).exists(),ErrorCode.PUBLISH_FAILED,"Could not remove rollback journal")
        } else { r.state="ROLLBACK_FAILED"; save(r) }
        return failed
    }
    fun publish(asset:PackagedAsset,relativePath:String="DCIM/Filmroom/",checkActive:()->Unit={}):PublishedAsset = locked(asset.id) {
        validatePath(relativePath); checkActive()
        var old=load(asset.id)
        if(old!=null && old.state=="COMMITTED") {
            demand(old.hashes==asset.parts.map { it.sha256 } && old.path==relativePath && old.rows.all { it.isNotEmpty() && sink.existsOwned(it) },ErrorCode.PUBLISH_FAILED,"Committed publication changed or was deleted; create a new asset to republish")
            return@locked PublishedAsset(asset.id,old.rows.toList())
        }
        if(old!=null) {
            val leftovers=rollback(old)
            demand(leftovers.isEmpty(),ErrorCode.PUBLISH_FAILED,"Previous publication needs cleanup: $leftovers")
        }
        LivePhotoVerifier.verify(asset,checkActive)
        val r=Record(asset.id,"PLANNED",relativePath,asset.parts.map { it.file.name },asset.parts.map { it.mimeType },asset.parts.map { it.sha256 },MutableList(asset.parts.size){""})
        save(r)
        try {
            for((i,part) in asset.parts.withIndex()) {
                checkActive(); r.rows[i]=sink.insertPending(r.names[i],r.mimes[i],r.path); save(r)
                sink.copyAndVerify(r.rows[i],part.file,part.sha256,checkActive)
            }
            r.state="READY"; save(r)
            for(id in r.rows) { checkActive(); sink.publish(id) }
            checkActive(); r.state="COMMITTED"; save(r)
            PublishedAsset(asset.id,r.rows.toList())
        } catch(t:Throwable) {
            try { val leftovers=rollback(r); if(leftovers.isNotEmpty())t.addSuppressed(LivePhotoException(ErrorCode.PUBLISH_FAILED,"Rollback incomplete: $leftovers")) }
            catch(cleanup:Throwable) { t.addSuppressed(cleanup) }
            throw t
        }
    }
    /** Skip active jobs and retain committed rows. Only incomplete journaled exports are removed. */
    fun recoverInterrupted():RecoveryReport {
        val rolledBack=mutableListOf<String>(); val failures=linkedMapOf<String,String>(); var committed=0
        for(file in directory.listFiles { f -> f.name.endsWith(".properties") }.orEmpty()) {
            val id=file.name.removeSuffix(".properties")
            try {
                locked(id) {
                    val r=load(id) ?: return@locked
                    if(r.state=="COMMITTED")committed++ else {
                        val leftovers=rollback(r)
                        if(leftovers.isEmpty())rolledBack+=id else failures[id]=leftovers.joinToString()
                    }
                }
            } catch(e:LivePhotoException) { if(e.code!=ErrorCode.BUSY)failures[id]=e.message ?: e.code.name }
            catch(e:Exception) { failures[id]=e.message ?: e.javaClass.simpleName }
        }
        return RecoveryReport(rolledBack,committed,failures)
    }
}
