package dev.filmroom.engine

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** No caller paths, recursive deletion, or access to original user files. */
internal object GpuProbePaths {
    private val files = setOf("manifest.json", "source.f64le", "kernel.f64le", "reference.f64le")
    fun snapshot(context: Context, token: Long): File {
        require(token > 0)
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "gpu-probes")
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root)
        val dir = File(root, "probe-$token")
        require(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) && dir.canonicalFile == dir)
        // Native revalidates the exact manifest, files, lengths, and sample hashes.
        return dir
    }
    fun deleteOwned(context: Context, token: Long) {
        runCatching {
            val dir = snapshot(context, token)
            val entries = dir.listFiles() ?: return
            // Never recurse, follow a link, or delete unexpected contents.
            if (entries.any { it.name !in files || !Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }) return
            entries.forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(dir.toPath())
        }
    }
}
