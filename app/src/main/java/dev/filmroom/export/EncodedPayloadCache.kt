package dev.filmroom.export

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** One process-owned, pre-metadata encoded image. No gallery paths, persisted cache hits, or aliases.
 * A borrower owns a read lease, never the mutable file. Copy/hash verification finishes BEFORE
 * MediaStore insertion. Admission can retire idle entries and staged candidates, never a borrower.
 * All failures are misses; cancellation is propagated by callers. No image-sized RAM allocation.
 */
internal class EncodedPayloadCache private constructor(private val root:File) {
    data class Key(val digest:String)
    internal data class Entry(val key:Key,val bytes:Long,val hash:String,val receipt:String,val completeIdentity:String)
    private var entry:Entry?=null
    private var pending:Entry?=null
    private var streaming:StreamCandidate?=null
    private var readers=0
    private var generation=0L
    private val payload=File(root,"payload")
    private val part=File(root,"payload.part")
    private val lockChannel:FileChannel?
    private val processLock:java.nio.channels.FileLock?
    private val enabled:Boolean
    init {
        var channel:FileChannel?=null;var lock:java.nio.channels.FileLock?=null
        enabled=runCatching {
            require(root.absoluteFile==root.canonicalFile && !Files.isSymbolicLink(root.toPath()))
            val created=root.mkdir()
            require(created || root.isDirectory)
            val marker=File(root,"owner-v1")
            if(created)FileOutputStream(marker).use{it.write(OWNER.toByteArray());it.fd.sync()}
            require(regular(marker) && marker.length()==OWNER.length.toLong() && marker.readText()==OWNER)
            require(root.listFiles()?.all{it.name in setOf("owner-v1","lock","payload","payload.part") && regular(it)}==true)
            channel=FileChannel.open(File(root,"lock").toPath(),CREATE,WRITE,NOFOLLOW_LINKS)
            lock=channel!!.tryLock();require(lock!=null)
            // These two names are explicitly owned by this marker. Unknown files disable caching.
            require((!payload.exists() || payload.delete()) && (!part.exists() || part.delete()))
            true
        }.getOrElse{false}
        if(!enabled){runCatching{lock?.release()};runCatching{channel?.close()}}
        lockChannel=channel;processLock=lock
    }
    @Synchronized fun trimForReserve(required:Long,available:()->Long) {
        if(!enabled || required<0 || available()>=required)return
        if(readers==0){streaming?.close();retire();pending=null;generation++;part.delete()}
    }
    @Synchronized fun acquire(key:Key):Lease? {
        val found=entry?.takeIf{it.key==key} ?: return null
        if(found.completeIdentity!=EncodedPayloadCache.key("completed-encoded-payload-v1",found.key.digest,found.receipt,found.bytes.toString(),found.hash).digest || !regular(payload) || payload.length()!=found.bytes){if(readers==0)retire();return null}
        readers++
        return Lease(found)
    }
    inner class Lease internal constructor(private val value:Entry):Closeable {
        private var closed=false
        val bytes get()=value.bytes
        val receipt get()=value.receipt
        /** create_new prevents overwriting another job's output, including symlink collisions. */
        fun copyTo(destination:File,checkActive:()->Unit)=copyTo(destination,null,checkActive)
        fun copyTo(destination:File,onProgress:((Long,Long)->Unit)?,checkActive:()->Unit) {
            synchronized(this@EncodedPayloadCache){check(!closed)}
            var owned=false
            try {
                checkActive();check(regular(payload) && payload.length()==value.bytes)
                FileChannel.open(destination.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use { channel ->
                    owned=true
                    val hash=copy(payload,channel,value.bytes,checkActive,onProgress)
                    check(hash==value.hash){"Encoded cache integrity mismatch"};channel.force(true)
                }
                checkActive()
            }catch(t:Throwable){if(owned)destination.delete();synchronized(this@EncodedPayloadCache){entry=null};throw t}
        }
        override fun close()=synchronized(this@EncodedPayloadCache){if(!closed){closed=true;readers--;if(readers==0 && entry==null)payload.delete()};Unit}
    }
    /** Stage only a successful native encoder's metadata-free private image, before metadata injection.
     * Caller includes the complete remaining queue reserve; cache never consumes that reservation. */
    @Synchronized fun stage(key:Key,source:File,receipt:String,reserve:Long,available:()->Long,checkActive:()->Unit):Ticket? {
        if(!enabled || readers!=0 || reserve<0 || !regular(source))return null
        val bytes=source.length()
        if(bytes !in 1..MAX_BYTES || receipt.length !in 1..16384)return null
        streaming?.close()
        if(!retire() || (part.exists() && !part.delete()))return null
        pending=null;generation++
        if(available()<Math.addExact(reserve,bytes))return null
        var owned=false
        try {
            checkActive()
            val hash=FileChannel.open(part.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use{channel->owned=true;copy(source,channel,bytes,checkActive).also{channel.force(true)}}
            checkActive();check(source.length()==bytes && regular(source))
            // Exact completed artifact identity also binds the actual numeric plan/algorithm
            // receipt. Lookup chooses this known completed plan, not a hypothetical rerender.
            pending=Entry(key,bytes,hash,receipt,EncodedPayloadCache.key("completed-encoded-payload-v1",key.digest,receipt,bytes.toString(),hash).digest)
            return Ticket(generation)
        }catch(t:Throwable){if(owned)part.delete();throw t}
    }
    /** Optional single-pass encoder tee. Each chunk is admitted under the queue's existing
     * storage lock using actual free space and its current full reserve. Contention, capacity,
     * disk errors or trimming abandon only this side stream; the normal export keeps running. */
    @Synchronized fun beginStream(key:Key,receipt:String,admit:(Int,()->Unit)->Boolean):StreamCandidate? {
        if(!enabled || readers!=0 || streaming!=null || receipt.length !in 1..16384)return null
        if(!retire() || (part.exists() && !part.delete()))return null
        pending=null;generation++
        val channel=try {FileChannel.open(part.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS)}catch(_:Exception){return null}
        return StreamCandidate(key,receipt,generation,channel,admit).also{streaming=it}
    }
    inner class StreamCandidate internal constructor(private val key:Key,private val receipt:String,
        private val version:Long,private val channel:FileChannel,private val admit:(Int,()->Unit)->Boolean):java.io.OutputStream() {
        private val digest=MessageDigest.getInstance("SHA-256")
        private var bytes=0L
        private var stopped=false
        override fun write(value:Int)=write(byteArrayOf(value.toByte()),0,1)
        override fun write(buffer:ByteArray,offset:Int,length:Int)=synchronized(this@EncodedPayloadCache) {
            if(stopped)return@synchronized
            try {
                check(version==generation && length>=0 && bytes+length<=MAX_BYTES)
                if(!admit(length){
                    val data=java.nio.ByteBuffer.wrap(buffer,offset,length)
                    while(data.hasRemaining())channel.write(data)
                    digest.update(buffer,offset,length);bytes+=length
                })close()
            }catch(c:java.util.concurrent.CancellationException){close();throw c}
            catch(e:InterruptedException){close();throw e}
            catch(_:Exception){close()}
            catch(_:OutOfMemoryError){close()}
        }
        /** Only after the complete finite PNG encode AND normal save have succeeded. */
        fun finish(checkActive:()->Unit):Ticket?=synchronized(this@EncodedPayloadCache) {
            if(stopped || version!=generation)return@synchronized null
            try {
                checkActive();check(bytes in 1..MAX_BYTES);channel.force(true);channel.close()
                check(regular(part) && part.length()==bytes);checkActive()
                val hash=hex(digest.digest())
                pending=Entry(key,bytes,hash,receipt,EncodedPayloadCache.key("completed-encoded-payload-v1",key.digest,receipt,bytes.toString(),hash).digest)
                stopped=true;streaming=null;Ticket(version)
            }catch(t:Throwable){close();throw t}
        }
        override fun close()=synchronized(this@EncodedPayloadCache) {
            if(!stopped){stopped=true;runCatching{channel.close()};if(version==generation){part.delete();streaming=null}}
            Unit
        }
    }
    inner class Ticket internal constructor(private val version:Long):Closeable {
        private var done=false
        /** Called only after the normal MediaStore commit and the caller's cancellation check. */
        fun commit(checkActive:()->Unit)=synchronized(this@EncodedPayloadCache){
            if(done)return@synchronized
            done=true
            val value=pending?.takeIf{generation==version} ?: return@synchronized
            try {checkActive();check(regular(part) && part.length()==value.bytes);check(!payload.exists() && !Files.isSymbolicLink(payload.toPath()));Files.move(part.toPath(),payload.toPath(),ATOMIC_MOVE);checkActive();entry=value}
            finally {pending=null;if(entry!==value){part.delete();payload.delete()}}
        }
        override fun close()=synchronized(this@EncodedPayloadCache){if(!done){done=true;if(version==generation){pending=null;part.delete()}};Unit}
    }
    private fun retire():Boolean {check(readers==0);entry=null;return !payload.exists() || payload.delete()}
    companion object {
        const val MAX_BYTES=32L*1024*1024
        const val MAX_SOURCE_BYTES=64L*1024*1024
        private const val OWNER="filmroom-private-encoded-payload-v1\n"
        private val instances=mutableMapOf<String,EncodedPayloadCache>()
        @Synchronized fun get(cacheDir:File)=instances.getOrPut(cacheDir.canonicalPath){EncodedPayloadCache(File(cacheDir.canonicalFile,"encoded-payload-v1"))}
        private fun regular(file:File)=Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) && file.absoluteFile==file.canonicalFile
        fun hashFile(file:File,limit:Long,checkActive:()->Unit):String? =
            hashFile(file,limit,{true},checkActive)
        /** Cooperative optional-work budget; never returns a digest of a prefix. */
        fun hashFile(file:File,limit:Long,continueHash:()->Boolean,checkActive:()->Unit):String? {
            checkActive();if(!continueHash() || !regular(file) || file.length() !in 1..limit)return null
            val size=file.length();val stamp=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            val digest=MessageDigest.getInstance("SHA-256");var total=0L
            Files.newInputStream(file.toPath(),READ,NOFOLLOW_LINKS).use{input->val bytes=ByteArray(64*1024);while(true){checkActive();if(!continueHash())return null;val n=input.read(bytes);if(n<0)break;total+=n;check(total<=size);digest.update(bytes,0,n)}}
            val after=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            checkActive();check(total==size && stamp.size()==after.size() && stamp.lastModifiedTime()==after.lastModifiedTime() && stamp.fileKey()==after.fileKey())
            if(!continueHash())return null
            return hex(digest.digest())
        }
        /** Length-delimited components cannot alias through separators, nulls or JSON text. */
        fun key(vararg components:String?):Key {
            val digest=MessageDigest.getInstance("SHA-256")
            components.forEach{value->val bytes=value?.toByteArray(Charsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array());bytes?.let{digest.update(it)}}
            return Key(hex(digest.digest()))
        }
        private fun copy(source:File,target:FileChannel,size:Long,checkActive:()->Unit,onProgress:((Long,Long)->Unit)?=null):String {
            val digest=MessageDigest.getInstance("SHA-256");var total=0L
            Files.newInputStream(source.toPath(),READ,NOFOLLOW_LINKS).use{input->val bytes=ByteArray(64*1024);while(true){checkActive();val n=input.read(bytes);if(n<0)break;total+=n;check(total<=size);digest.update(bytes,0,n);val buffer=java.nio.ByteBuffer.wrap(bytes,0,n);while(buffer.hasRemaining()){checkActive();target.write(buffer)};onProgress?.invoke(total,size)}}
            check(total==size);return hex(digest.digest())
        }
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    }
}
