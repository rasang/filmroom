package dev.filmroom.export

import org.json.JSONObject
import java.io.File
import dev.filmroom.io.ExportFormat

/** The encoder's completed report certifies finite full-image conversion and fsync, not just a
 * file extension/header. Missing fields from legacy/fake APIs mean no cache, not export failure. */
internal object EncodedPayloadProof {
    fun valid(report:JSONObject,file:File,width:Int,height:Int,format:ExportFormat,colorSpace:String):Boolean =
        report.optBoolean("outputValid") && report.optString("stage")=="complete" &&
        report.optInt("schemaVersion")==1 && report.optString("operation")=="encode" &&
        report.optInt("width")==width && report.optInt("height")==height &&
        report.optString("format")==format.name && report.optString("colorSpace")==colorSpace &&
        report.optInt("bitDepth")== (if(format==ExportFormat.TIFF16)16 else 8) &&
        report.optInt("outputOrientation")==1 && report.optLong("outputBytes",-1)==file.length() && file.length()>0
}
