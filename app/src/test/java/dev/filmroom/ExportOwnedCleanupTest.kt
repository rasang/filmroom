package dev.filmroom

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportOwnedCleanupTest {
    private fun inDirectory(block:(File)->Unit) {
        val directory=Files.createTempDirectory("export-cleanup-test").toFile()
        try {block(directory)} finally {directory.deleteRecursively()}
    }
    @Test fun ownedFilesFinishBeforeCompletionAndProgressCountsRealDeletes()=inDirectory { dir -> runBlocking {
        val input=File(dir,"input").apply{writeText("input")}
        val output=File(dir,"output").apply{writeText("output")}
        val progress=mutableListOf<Pair<Long,Long>>()
        assertTrue(cleanupOwnedExportFiles(input,false,output){done,total->
            progress+=done to total
            if(done>=1)assertFalse(input.exists())
            if(done==2L)assertFalse(output.exists())
        })
        assertEquals(listOf(0L to 2L,1L to 2L,2L to 2L),progress)
    }}
    @Test fun borrowedRawSpoolSurvivesAndAbsentOutputNeedsNoDeletion()=inDirectory { dir -> runBlocking {
        val source=File(dir,"raw-source").apply{writeText("keep")}
        val output=File(dir,"missing-output")
        val progress=mutableListOf<Pair<Long,Long>>()
        assertTrue(cleanupOwnedExportFiles(source,true,output){done,total->progress+=done to total})
        assertEquals("keep",source.readText())
        assertEquals(listOf(0L to 1L,1L to 1L),progress)
    }}
    @Test fun cancelledOperationStillDeletesOwnedFiles()=inDirectory { dir -> runBlocking {
        val input=File(dir,"input").apply{writeText("input")}
        val output=File(dir,"output").apply{writeText("output")}
        var cleaned=false
        val job=launch {
            currentCoroutineContext().cancel()
            cleaned=cleanupOwnedExportFiles(input,false,output){_,_->}
        }
        job.join()
        assertTrue(cleaned)
        assertFalse(input.exists());assertFalse(output.exists())
    }}
    @Test fun failedDeleteDoesNotClaimFullCompletionOrDeleteChildren()=inDirectory { dir -> runBlocking {
        val input=File(dir,"nonempty").apply{mkdir()}
        val child=File(input,"retain").apply{writeText("keep")}
        val output=File(dir,"output").apply{writeText("output")}
        val progress=mutableListOf<Pair<Long,Long>>()
        assertFalse(cleanupOwnedExportFiles(input,false,output){done,total->progress+=done to total})
        assertTrue(child.exists());assertFalse(output.exists())
        assertEquals(1L to 2L,progress.last())
        assertFalse(progress.any{it.first==it.second})
    }}
}
