package dev.filmroom.codec

import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.io.PhotoException
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CodecExportScopeTest {
    private class Sandbox:AutoCloseable {
        val app=Files.createTempDirectory("codec-scope").toFile()
        val cache=File(app,"cache").apply{mkdir()}
        val jobs=File(app,"files/export-jobs").apply{mkdirs()}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val input=File(job,"film-result-${UUID.randomUUID()}.rgb").apply{writeBytes(ByteArray(12))}
        val output=File(job,"codec-output-${UUID.randomUUID()}.jpg")
        fun select(input:File=this.input,output:File=this.output,root:File?=jobs)=CodecExportScope.select(cache,root,input,output)
        override fun close(){app.deleteRecursively()}
    }
    private fun reject(block:()->Unit) {
        try{block();fail("Expected confined export rejection")}
        catch(e:PhotoException){assertEquals(CodecErrorCode.INVALID_PATH,e.codecCode);assertEquals(CodecStage.VALIDATE,e.codecStage);assertFalse(e.message!!.contains("codec-scope"))}
    }
    @Test fun cacheRemainsCacheOnlyAndQueueGetsExactlyOneJob()=Sandbox().use{s->
        assertEquals(s.cache.canonicalFile,s.select(File(s.cache,"input.rgb"),File(s.cache,"output.jpg")))
        assertEquals(s.job.canonicalFile,s.select())
        assertEquals(s.job.canonicalFile,s.select(output=File(s.job,"codec-output-${UUID.randomUUID()}.tif")))
        reject{s.select(root=null)}
    }
    @Test fun androidStyleParentAliasDoesNotWidenScope()=Sandbox().use{s->
        val alias=File(s.app,"app-alias");Files.createSymbolicLink(alias.toPath(),s.app.toPath())
        try{assertEquals(s.job.canonicalFile,s.select(File(alias,"files/export-jobs/${s.job.name}/${s.input.name}"),File(alias,"files/export-jobs/${s.job.name}/${s.output.name}")))}finally{alias.delete()}
    }
    @Test fun siblingJobsAndMixedCacheQueuePathsAreRejected()=Sandbox().use{s->
        val other=File(s.jobs,UUID.randomUUID().toString()).apply{mkdir()}
        reject{s.select(output=File(other,s.output.name))}
        reject{s.select(output=File(s.cache,s.output.name))}
        reject{s.select(input=File(s.cache,s.input.name))}
    }
    @Test fun malformedIdentityRootOrFileNamesAreRejected()=Sandbox().use{s->
        for(dir in listOf(File(s.jobs,"bad-job"),File(s.app,"files/${UUID.randomUUID()}"),File(s.app,"shared_prefs/${UUID.randomUUID()}"))) {
            dir.mkdirs();reject{s.select(File(dir,s.input.name),File(dir,s.output.name))}
        }
        reject{s.select(input=File(s.job,"input.rgb"))}
        reject{s.select(output=File(s.job,"manifest.json"))}
        reject{s.select(root=File(s.app,"files"))}
    }
    @Test fun inputOutputAndJobSymlinksCannotEscapeOrAliasAnotherJob()=Sandbox().use{s->
        val other=File(s.jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val outside=File(s.app,"outside").apply{mkdir()}
        for(target in listOf(other,outside)) {
            val link=File(s.jobs,UUID.randomUUID().toString());Files.createSymbolicLink(link.toPath(),target.toPath())
            try{reject{s.select(File(link,s.input.name),File(link,s.output.name))}}finally{link.delete()}
        }
        val inputLink=File(s.job,"film-result-${UUID.randomUUID()}.rgb");Files.createSymbolicLink(inputLink.toPath(),s.input.toPath())
        reject{s.select(input=inputLink)};inputLink.delete()
        for(target in listOf(s.input,File(outside,"missing"))) {
            Files.createSymbolicLink(s.output.toPath(),target.toPath());reject{s.select()};s.output.delete()
        }
    }
    @Test fun configuredJobsSymlinkAndFilesEscapeAreRejected()=Sandbox().use{s->
        val alias=File(s.app,"jobs-alias");Files.createSymbolicLink(alias.toPath(),s.jobs.toPath())
        reject{s.select(root=alias)};alias.delete()
        val other=File(s.app,"outside").apply{mkdir()}
        val moved=File(s.app,"original-files");assertTrue(File(s.app,"files").renameTo(moved))
        Files.createSymbolicLink(File(s.app,"files").toPath(),other.toPath())
        try{reject{s.select()}}finally{File(s.app,"files").delete();moved.renameTo(File(s.app,"files"))}
    }
}
