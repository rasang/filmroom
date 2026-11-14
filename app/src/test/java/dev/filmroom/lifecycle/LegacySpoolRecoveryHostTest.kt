package dev.filmroom.lifecycle

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacySpoolRecoveryHostTest {
    @get:Rule val temporary=TemporaryFolder()
    private val id="12345678-1234-1234-1234-123456789abc"
    private val name="film-result-$id.rgb.finishing.part"
    private fun touch(parent:File,name:String)=File(parent,name).apply{writeText("owned data")}

    @Test fun processRestartReclaimsOnlyFinishingPartialsAndPreservesCheckpointLinks() {
        val cache=temporary.newFolder("cache");val files=temporary.newFolder("files")
        val job=File(files,"export-jobs/$id").apply{mkdirs()}
        val partial=touch(job,name);val cachePartial=touch(cache,name)
        val legacy=touch(cache,"film-input-Ab12.rgb.output.finishing.part")
        val input=touch(job,"input.rgb");val result=touch(job,"film-result-$id.rgb")
        val checkpoint=File(job,"checkpoint").apply{mkdirs()};val saved=touch(checkpoint,"density.rgb")
        val nested=touch(checkpoint,name)
        val link=File(job,"saved-link.rgb");Files.createLink(link.toPath(),saved.toPath())
        val unknown=touch(job,"photo.rgb.finishing.part")
        val other=File(files,"export-jobs/not-a-job").apply{mkdirs()};val otherPartial=touch(other,name)
        val report=PrivateWorkRecovery.recover(cache,files,null)
        assertEquals(0,report.failed);assertEquals(3,report.removed)
        for(f in listOf(partial,cachePartial,legacy))assertFalse(f.exists())
        for(f in listOf(input,result,saved,nested,link,unknown,otherPartial))assertTrue(f.path,f.exists())
        assertEquals("owned data",saved.readText());assertEquals("owned data",link.readText())
        assertEquals(0,PrivateWorkRecovery.recover(cache,files,null).removed)
    }
    @Test fun startupDoesNotFollowJobRootJobDirectoryOrTemporarySymlinks() {
        val cache=temporary.newFolder("cache");val files=temporary.newFolder("files");val outside=temporary.newFolder("outside")
        val victim=touch(outside,name)
        val jobs=File(files,"export-jobs").apply{mkdirs()}
        Files.createSymbolicLink(File(jobs,id).toPath(),outside.toPath())
        val real=File(jobs,"22345678-1234-1234-1234-123456789abc").apply{mkdirs()}
        Files.createSymbolicLink(File(real,name).toPath(),victim.toPath())
        assertEquals(0,PrivateWorkRecovery.recover(cache,files,null).removed);assertTrue(victim.exists())
        val otherFiles=temporary.newFolder("other-files")
        Files.createSymbolicLink(File(otherFiles,"export-jobs").toPath(),jobs.toPath())
        assertEquals(0,PrivateWorkRecovery.recover(cache,otherFiles,null).removed);assertTrue(victim.exists())
    }
    @Test fun retryRecognizesKilledFinishingOutputButNotSourcesOrArbitraryFiles() {
        for(n in listOf("film-result-$id.rgb","film-result-$id.rgb.part",name))
            assertTrue(n,PrivateWorkRecovery.isDisposableExportResult(n))
        for(n in listOf("input.rgb","source.rgb","checkpoint.rgb", "film-result-not-a-uuid.rgb.finishing.part",name+".bak","photo.jpg"))
            assertFalse(n,PrivateWorkRecovery.isDisposableExportResult(n))
        val job=temporary.newFolder("retry");val stale=touch(job,name);val input=touch(job,"input.rgb")
        job.listFiles()!!.filter{it.isFile&&PrivateWorkRecovery.isDisposableExportResult(it.name)}.forEach{assertTrue(it.delete())}
        assertFalse(stale.exists());assertTrue(input.exists())
    }
}
