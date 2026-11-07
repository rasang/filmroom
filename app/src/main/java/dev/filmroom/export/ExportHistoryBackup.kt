package dev.filmroom.export

import android.util.AtomicFile
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** An allowlist, not a copy of manifests. No source/media references or diagnostic prose. */
internal object ExportHistoryBackup {
    const val MAX_ROWS = 10000
    private val keys = setOf("id", "createdAt", "width", "height", "format", "state", "filmStock", "outputColorSpace", "exportMillis")
    fun exportRows(jobs: List<ExportJob>): JSONArray = JSONArray().apply {
        require(jobs.size <= MAX_ROWS)
        jobs.forEach { job -> put(JSONObject().apply {
            put("id",job.id); put("createdAt",job.createdAt)
            put("width",job.outputWidth); put("height",job.outputHeight)
            put("format",job.format.name); put("state",job.archivedSourceState ?: job.state.name)
            put("filmStock",job.parameters.filmStock); put("outputColorSpace",job.parameters.outputColorSpace)
            job.exportMillis?.let { put("exportMillis",it) }
        }) }
    }
    fun validateRows(rows: JSONArray): List<ExportJob> {
        require(rows.length() <= MAX_ROWS) { "Too many history entries" }
        val result = (0 until rows.length()).map { index ->
            val row=rows.getJSONObject(index)
            require(row.keys().asSequence().all { it in keys }) { "Newer history format; upgrade required" }
            fun string(key:String):String = (row.get(key) as? String) ?: error("Invalid $key")
            fun integer(key:String):Long {
                val value=row.get(key)
                require(value is Int || value is Long) { "Invalid $key" }
                return (value as Number).toLong()
            }
            val id=string("id"); require(UUID.fromString(id).toString()==id)
            val timestamp=integer("createdAt"); require(timestamp>=0)
            val width=integer("width");val height=integer("height")
            require(width in 1..100000 && height in 1..100000 && width*height<=1_000_000_000L)
            val film=string("filmStock");val color=string("outputColorSpace")
            require(film.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            require(dev.filmroom.io.OutputColorSpace.entries.any{it.id==color}) { "Unsupported output color space; upgrade required" }
            val sourceState=ExportJobState.valueOf(string("state"))
            val state=if(sourceState in setOf(ExportJobState.COMPLETED,ExportJobState.FAILED,ExportJobState.CANCELLED))sourceState else ExportJobState.CANCELLED
            val millis=if(row.has("exportMillis"))integer("exportMillis").also { require(it>=0) }else null
            ExportJob(id,timestamp,EditorState(filmStock=film,outputColorSpace=color),width.toInt(),height.toInt(),
                ExportFormat.valueOf(string("format")),95,state,ExportDisplayProgress(if(state==ExportJobState.COMPLETED)8 else 1),
                inputReady=false,completionViewed=true,exportMillis=millis,archivedHistory=true,archivedSourceState=sourceState.name)
        }
        require(result.map { it.id }.distinct().size==result.size) { "Duplicate history identifiers" }
        return result
    }
}

/** Imported rows are metadata only and never become executable job directories. */
internal class ArchivedExportHistory(private val file:File) {
    fun read():List<ExportJob> {
        val atomic=AtomicFile(file)
        if(!file.exists() && !File(file.path+".bak").exists())return emptyList()
        val bytes=atomic.openRead().use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {
                val count=input.read(buffer);if(count<0)break
                require(output.size()+count<=MAX_BYTES)
                output.write(buffer,0,count)
            }
            output.toByteArray()
        }
        val root=JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version")==1)
        return ExportHistoryBackup.validateRows(root.getJSONArray("rows"))
    }
    fun write(jobs:List<ExportJob>) {
        val rows=ExportHistoryBackup.exportRows(jobs)
        ExportHistoryBackup.validateRows(rows)
        val bytes=JSONObject().put("version",1).put("rows",rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        val atomic=AtomicFile(file);file.parentFile?.mkdirs()
        val out=atomic.startWrite()
        try {out.write(bytes);atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}
        check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes))
    }
    companion object { const val MAX_BYTES=4*1024*1024 }
}
