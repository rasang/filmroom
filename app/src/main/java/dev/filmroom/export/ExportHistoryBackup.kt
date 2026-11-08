package dev.filmroom.export

import android.util.AtomicFile
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Allowlisted record metadata and explicitly requested content URIs; never private paths, bytes or diagnostics. */
internal object ExportHistoryBackup {
    const val MAX_ROWS = 10000
    private val keys = setOf("id", "createdAt", "width", "height", "format", "state", "filmStock", "outputColorSpace", "exportMillis", "mediaUri", "outputName", "originalUri")
    fun exportRows(jobs: List<ExportJob>): JSONArray = JSONArray().apply {
        require(jobs.size <= MAX_ROWS)
        jobs.forEach { job -> put(JSONObject().apply {
            put("id",job.id); put("createdAt",job.createdAt)
            put("width",job.outputWidth); put("height",job.outputHeight)
            put("format",job.format.name); put("state",job.archivedSourceState ?: job.state.name)
            put("filmStock",job.parameters.filmStock); put("outputColorSpace",job.parameters.outputColorSpace)
            job.exportMillis?.let { put("exportMillis",it) }
            if (job.state == ExportJobState.COMPLETED && BackupMediaLink.validOutput(job.mediaUri) && !job.outputName.isNullOrBlank()) {
                put("mediaUri", job.mediaUri); put("outputName", job.outputName)
            }
            job.originalUri?.takeIf(BackupMediaLink::validOriginal)?.let { put("originalUri", it) }
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
            val uri = if(row.has("mediaUri")) string("mediaUri").also { require(BackupMediaLink.validOutput(it)) } else null
            val name = if(row.has("outputName")) string("outputName").also { require(it.isNotBlank() && it.length <= 255 && !it.contains('/') && !it.contains('\\')) } else null
            require((uri == null) == (name == null)); require(uri == null || sourceState == ExportJobState.COMPLETED)
            val original = if(row.has("originalUri")) string("originalUri").also { require(BackupMediaLink.validOriginal(it)) } else null
            ExportJob(id,timestamp,EditorState(filmStock=film,outputColorSpace=color),width.toInt(),height.toInt(),
                ExportFormat.valueOf(string("format")),95,state,ExportDisplayProgress(if(state==ExportJobState.COMPLETED)8 else 1),
                inputReady=false,completionViewed=true,exportMillis=millis,archivedHistory=true,archivedSourceState=sourceState.name,mediaUri=uri,outputName=name,originalUri=original)
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
    private fun encode(jobs:List<ExportJob>):ByteArray {
        val rows=ExportHistoryBackup.exportRows(jobs)
        ExportHistoryBackup.validateRows(rows)
        val bytes=JSONObject().put("version",1).put("rows",rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        return bytes
    }
    fun validate(jobs:List<ExportJob>) { encode(jobs) }
    fun write(jobs:List<ExportJob>) {
        val bytes=encode(jobs)
        val atomic=AtomicFile(file);file.parentFile?.mkdirs()
        val out=atomic.startWrite()
        try {out.write(bytes);atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}
        check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes))
    }
    companion object { const val MAX_BYTES=8*1024*1024 }
}
