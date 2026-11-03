package dev.filmroom.export

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Exact immutable queue snapshot; no filename/path search is ever an output locator. */
data class ExportHistorySnapshot(val id:String,val fingerprint:String,val state:ExportJobState,val mediaUri:String?,val exportMillis:Long?,val outputName:String?)
data class ExportHistoryOperation(val id:String,val deletePhotos:Boolean,val targets:List<ExportHistorySnapshot>,val outcomes:Map<String,String> = emptyMap(),val status:String="ready",val consentUri:String?=null,val consentKind:String?=null) {
    val remaining get()=targets.filter{it.id !in outcomes}
}

/** Private durable intent journal, written before any external photo mutation. Never replays deletion on startup. */
internal class ExportHistoryJournal(private val root:File) {
    private fun file(id:String):AtomicFile {require(UUID.fromString(id).toString()==id);check(root.mkdirs()||root.isDirectory);return AtomicFile(File(root,"$id.json"))}
    fun write(op:ExportHistoryOperation) {
        val j=JSONObject().put("version",1).put("id",op.id).put("deletePhotos",op.deletePhotos).put("status",op.status)
            .put("consentUri",op.consentUri).put("consentKind",op.consentKind).put("outcomes",JSONObject(op.outcomes))
            .put("targets",JSONArray().apply{op.targets.forEach{s->put(JSONObject().put("id",s.id).put("fingerprint",s.fingerprint).put("state",s.state.name).put("mediaUri",s.mediaUri).put("exportMillis",s.exportMillis).put("outputName",s.outputName))}})
        val bytes=j.toString().toByteArray(Charsets.UTF_8);val target=file(op.id);val out=target.startWrite()
        try{out.write(bytes);out.fd.sync();target.finishWrite(out);check(target.openRead().use{it.readBytes()}.contentEquals(bytes))}
        catch(t:Throwable){target.failWrite(out);throw t}
    }
    fun read(id:String):ExportHistoryOperation? {
        val target=file(id);if(!target.baseFile.exists())return null
        val j=JSONObject(target.openRead().bufferedReader().use{it.readText()});require(j.getInt("version")==1 && j.getString("id")==id)
        val values=j.getJSONArray("targets");val outcomes=j.getJSONObject("outcomes")
        return ExportHistoryOperation(id,j.getBoolean("deletePhotos"),(0 until values.length()).map{i->values.getJSONObject(i).let{v->
            ExportHistorySnapshot(v.getString("id"),v.getString("fingerprint"),ExportJobState.valueOf(v.getString("state")),v.optString("mediaUri").takeIf{it.isNotEmpty()},if(v.has("exportMillis"))v.getLong("exportMillis")else null,v.optString("outputName").takeIf{it.isNotEmpty()})
        }},outcomes.keys().asSequence().associateWith{outcomes.getString(it)},j.getString("status"),j.optString("consentUri").takeIf{it.isNotEmpty()},j.optString("consentKind").takeIf{it.isNotEmpty()})
    }
}
