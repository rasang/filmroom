package dev.filmroom.lifecycle

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Invoked once by Application.onCreate, before any editor worker in this process. */
object PrivateWorkRecovery {
    data class Report(val removed:Int,val failed:Int)
    private val uuid="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val spoolPatterns=listOf(
        Regex("codec-output-$uuid\\.(?:jpg|tif)"),
        Regex("original-input-$uuid\\.rgb"),
        Regex("film-result-$uuid\\.rgb"),
        Regex("film-input-[A-Za-z0-9]+\\.rgb(?:\\.output)?"),
        Regex("original-spool-[A-Za-z0-9]+\\.part"),
    )
    private val nativeScratchPattern=Regex("film-native-v5-[0-9]{1,20}-[0-9]{1,39}-[0-9]{1,20}")
    private val rawPattern=Regex("(?:raw|heic)-source-$uuid\\.rgb")
    private val rawPolicyPattern=Regex("raw-source-$uuid\\.rgb"+Regex.escape(dev.filmroom.io.RawDevelopmentCache.SUFFIX))
    private val heicPrimaryPattern=Regex("heic-primary-[A-Za-z0-9]+\\.heic")
    private val sourcePattern=Regex("source-[A-Za-z0-9]+\\.image")
    fun recover(cacheDir:File,filesDir:File,retainedSourceKey:String?,retainedRawKey:String?=null):Report {
        var removed=0;var failed=0
        fun deleteKnown(file:File,parent:File) {
            // No recursion through symlinks and no arbitrary directory deletion.
            if(file.parentFile?.canonicalFile!=parent.canonicalFile||!file.isFile)return
            if(file.delete())removed++ else failed++
        }
        fun deleteNativeScratch(root:File) {
            if(root.parentFile?.canonicalFile!=cacheDir.canonicalFile)return
            // walkFileTree does not follow symbolic links unless explicitly requested.
            // Even a root link is unlinked itself; no target is opened or traversed.
            try {
                Files.walkFileTree(root.toPath(),object:SimpleFileVisitor<Path>() {
                    override fun visitFile(path:Path,attrs:BasicFileAttributes):FileVisitResult {
                        try {Files.delete(path);removed++} catch(_:IOException){failed++}
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(path:Path,error:IOException):FileVisitResult {failed++;return FileVisitResult.CONTINUE}
                    override fun postVisitDirectory(path:Path,error:IOException?):FileVisitResult {
                        if(error!=null)failed++
                        try {Files.delete(path);removed++} catch(_:IOException){failed++}
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch(_:IOException) {failed++}
        }
        val cacheEntries=cacheDir.listFiles()
        if(cacheEntries==null)failed++
        cacheEntries?.forEach {file->
            if(spoolPatterns.any{it.matches(file.name)})deleteKnown(file,cacheDir)
            else if(nativeScratchPattern.matches(file.name))deleteNativeScratch(file)
        }
        val sources=File(cacheDir,"photo-sources")
        val retained=retainedSourceKey?.takeIf{sourcePattern.matches(it)}
        if(Files.isDirectory(sources.toPath(),NOFOLLOW_LINKS)) {
            val sourceEntries=sources.listFiles()
            if(sourceEntries==null)failed++
            val retainedRaw=retainedRawKey?.takeIf{rawPattern.matches(it)}
            val retainedPolicy=retainedRaw?.takeIf{Files.isRegularFile(File(sources,it).toPath(),NOFOLLOW_LINKS)}
                ?.plus(dev.filmroom.io.RawDevelopmentCache.SUFFIX)
            sourceEntries?.forEach {file->if((sourcePattern.matches(file.name)&&file.name!=retained)
                ||(rawPattern.matches(file.name)&&file.name!=retainedRaw)
                ||(rawPolicyPattern.matches(file.name)&&file.name!=retainedPolicy)
                ||heicPrimaryPattern.matches(file.name))deleteKnown(file,sources)}
        }
        val data=File(filesDir,"engine-data")
        if(Files.isDirectory(data.toPath(),NOFOLLOW_LINKS)) data.walkTopDown().maxDepth(4).onFail{_,_->failed++}.onEnter{Files.isDirectory(it.toPath(),NOFOLLOW_LINKS)&&it.canonicalFile.toPath().startsWith(data.canonicalFile.toPath())}.forEach {file->
            if(file.isFile&&file.name.matches(Regex("asset-[A-Za-z0-9]+\\.tmp")))deleteKnown(file,file.parentFile!!)
        }
        return Report(removed,failed)
    }
}
