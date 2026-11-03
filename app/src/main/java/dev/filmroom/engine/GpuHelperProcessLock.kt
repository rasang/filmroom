package dev.filmroom.engine

import android.content.Context
import android.os.IBinder
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Kernel-owned cross-helper gate. Intentionally held until process death, not JNI return. */
internal object GpuHelperProcessLock {
    private var held: AutoCloseable? = null
    private fun file(context: Context) = File(context.filesDir.canonicalFile, "gpu-worker.lock")
    @Synchronized fun acquire(context: Context): Boolean {
        if (held != null) return false
        held = GpuProcessFileGate.tryAcquire(file(context))
        return held != null
    }
    suspend fun awaitStartup(context: Context): AutoCloseable? = GpuProcessFileGate.await(file(context), 5000)
}

internal object GpuProcessFileGate {
    fun tryAcquire(file: File): AutoCloseable? {
        var channel: FileChannel? = null
        return try {
            channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val lock = channel.tryLock()
            if (lock == null) { channel.close(); null }
            else {
                val owned = channel
                AutoCloseable { try { lock.release() } finally { owned.close() } }
            }
        } catch (_: Exception) { runCatching { channel?.close() }; null }
    }
    suspend fun await(file: File, timeoutMs: Long): AutoCloseable? {
        require(timeoutMs in 0..5000)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            tryAcquire(file)?.let { return it }
            if (System.nanoTime() >= deadline) return null
            kotlinx.coroutines.delay(25)
        }
    }
}

/** Retains the parent Binder and death recipient for the helper's entire lifetime. */
internal class GpuHelperParentWatch(private val died: () -> Unit) {
    private var parent: IBinder? = null
    private val recipient = IBinder.DeathRecipient { died() }
    @Synchronized fun isAlive(): Boolean = parent?.let { it.isBinderAlive && it.pingBinder() } == true
    fun claimNative(acquire: () -> Boolean): Boolean = isAlive() && acquire() && isAlive()
    @Synchronized fun attach(binder: IBinder): Boolean {
        parent?.let { return it == binder && it.isBinderAlive }
        parent = binder
        return try { binder.linkToDeath(recipient, 0); binder.isBinderAlive.also { if (!it) died() } }
        catch (_: android.os.RemoteException) { died(); false }
    }
}

/** Startup only, while the main process owns the exclusive kernel GPU gate. */
internal object GpuRenderRecovery {
    private val token = Regex("gpu-stage-[1-9][0-9]{0,18}")
    private val packetFiles = setOf("manifest.json", ".manifest.partial", "manifest.tmp", "tables.f64le", "source.rgb.f32le", "source.meter.f32le", "source.logxyz.f64le", "source.glare.f64le") + (0..2).flatMap { listOf("source-$it.f64le", "kernel-$it.f64le") }
    private val candidateFiles = setOf("complete.json", ".complete.partial", "complete.tmp", "candidate.f64le", "candidate.rgb.f32le", "meter.f32le") + (0..2).map { "plane-$it.f64le" }
    private val candidateDirectories = setOf("candidate", ".candidate.partial")
    private val prefixFiles=setOf("manifest.json",".manifest.partial","tables.f64le")+(0..2).map{"source-$it.f64le"}
    private val partialCandidateFiles = setOf("complete.json") + (0..2).map { "plane-$it.f64le" }
    fun recover(cache: File): dev.filmroom.lifecycle.PrivateWorkRecovery.Report {
        val root = File(cache.canonicalFile, "gpu-render")
        if (!java.nio.file.Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) || root.canonicalFile != root) return dev.filmroom.lifecycle.PrivateWorkRecovery.Report(0, 0)
        var removed = 0; var failed = 0
        try {
            java.nio.file.Files.newDirectoryStream(root.toPath()).use { entries ->
                packets@ for (path in entries.take(64)) {
                    val name = path.fileName.toString()
                    if (!token.matches(name) || name.removePrefix("gpu-stage-").toLongOrNull() == null || !java.nio.file.Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                    val children = bounded(path, packetFiles.size + candidateDirectories.size + 1) ?: continue
                    if (children.any { it.fileName.toString() !in packetFiles && it.fileName.toString() !in candidateDirectories && it.fileName.toString()!="prefix" }) continue
                    val direct = children.filter { it.fileName.toString() !in candidateDirectories && it.fileName.toString()!="prefix" }
                    if (direct.any { !java.nio.file.Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) continue
                    val candidates = mutableListOf<Pair<java.nio.file.Path, List<java.nio.file.Path>>>()
                    // Validate every owned subtree before deleting anything. Unknown entries
                    // or symlinks preserve the entire packet; no recursive sweep is permitted.
                    for (candidate in children.filter { it.fileName.toString() in candidateDirectories }) {
                        if (!java.nio.file.Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) continue@packets
                        val allowed = if (candidate.fileName.toString() == ".candidate.partial") partialCandidateFiles else candidateFiles
                        val results = bounded(candidate, allowed.size) ?: continue@packets
                        if (results.any { it.fileName.toString() !in allowed || !java.nio.file.Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) continue@packets
                        candidates.add(candidate to results)
                    }
                    val prefix=children.singleOrNull{it.fileName.toString()=="prefix"}
                    if(prefix!=null){
                        if(!java.nio.file.Files.isDirectory(prefix,LinkOption.NOFOLLOW_LINKS))continue@packets
                        val nested=bounded(prefix,1) ?: continue@packets
                        for(packet in nested){
                            if(packet.fileName.toString()!=name || !java.nio.file.Files.isDirectory(packet,LinkOption.NOFOLLOW_LINKS))continue@packets
                            val files=bounded(packet,prefixFiles.size) ?: continue@packets
                            if(files.any{it.fileName.toString() !in prefixFiles || !java.nio.file.Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS)})continue@packets
                            candidates.add(packet to files)
                        }
                    }
                    try {
                        for ((candidate, results) in candidates) {
                            results.forEach { java.nio.file.Files.delete(it); removed++ }
                            java.nio.file.Files.delete(candidate); removed++
                        }
                        if(prefix!=null){java.nio.file.Files.delete(prefix);removed++}
                        direct.forEach { java.nio.file.Files.delete(it); removed++ }
                        java.nio.file.Files.delete(path); removed++
                    } catch (_: Exception) { failed++ }
                }
            }
        } catch (_: Exception) { failed++ }
        return dev.filmroom.lifecycle.PrivateWorkRecovery.Report(removed, failed)
    }
    private fun bounded(path: java.nio.file.Path, max: Int): List<java.nio.file.Path>? = runCatching {
        java.nio.file.Files.newDirectoryStream(path).use { stream ->
            val paths = stream.take(max + 1).toList()
            paths.takeIf { it.size <= max }
        }
    }.getOrNull()
}
