package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface
import dev.filmroom.diagnostics.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** A bounded, typed capture snapshot, never a copy of source offsets or opaque MakerNotes. */
@ConsistentCopyVisibility
data class PhotoMetadata private constructor(private val attributes:Map<String,String>, val warning:String?=null, private val sourceCreateDate:String?=null, val issues:List<MetadataIssue> = emptyList(), private val indexTimeZone:String?=null, private val indexTimeMillis:Long?=null, private val sourceOffsetPresent:Boolean=false, private val imprintFallback:String?=null, val imprintFallbackKind:String?=null) {
    /** Presentation fallback is separate from capture EXIF and never written as DateTimeOriginal. */
    fun imprintDateTime():LocalDateTime? = attributes["DateTimeOriginal"]?.let(::parseImprintDate)
        ?: imprintFallback?.let(::parseImprintDate)
    fun withImprintFallback(value:String?,kind:String):PhotoMetadata {
        if(kind !in setOf("modified","created") || (imprintFallback!=null && !(kind=="modified" && imprintFallbackKind=="created")))return this
        val valid=value?.takeIf{parseImprintDate(it)!=null} ?: return this
        return copy(imprintFallback=valid,imprintFallbackKind=kind)
    }
    /** Validated exposure and original capture facts; fallback stays presentation-only. */
    internal fun imprintAttributes():Map<String,String> = attributes.filterKeys { it in setOf(
        "DateTimeOriginal","ExposureTime","ShutterSpeedValue","FNumber","ApertureValue",
        "MeteringMode","ExposureBiasValue","FocalLength","FocalLengthIn35mmFilm","PhotographicSensitivity",
        "SensitivityType","StandardOutputSensitivity","RecommendedExposureIndex","ISOSpeed") }

    fun toJson():String=JSONObject().put("version",1).put("attributes",JSONObject(attributes)).put("warning",warning).put("sourceCreateDate",sourceCreateDate).put("issues",MetadataIssues.encode(issues)).put("indexTimeZone",indexTimeZone).put("indexTimeMillis",indexTimeMillis).put("sourceOffsetPresent",sourceOffsetPresent).put("imprintFallback",imprintFallback).put("imprintFallbackKind",imprintFallbackKind).toString()

    /** Preserve validated original camera/exposure/lens facts, never stale layout/vendor metadata. */
    fun livePhotoCaptureExif():List<dev.filmroom.livephoto.core.LivePhotoCaptureExif> =
        attributes.mapNotNull { (key,value) -> SCHEMA[key]?.takeIf {
            dev.filmroom.livephoto.core.LivePhotoCaptureExif.accepts(it.group,it.tag)
        }?.let { dev.filmroom.livephoto.core.LivePhotoCaptureExif(it.group,it.tag,it.type,encode(it,value)) } }

    /** A frozen snapshot stays authoritative, including intentionally omitted GPS in backups.
     * URI fallback may supply a missing original date, but must never replace other capture facts. */
    internal fun withCaptureDateFallback(fallback:PhotoMetadata):PhotoMetadata {
        if(attributes["DateTimeOriginal"]!=null || fallback.attributes["DateTimeOriginal"]==null)return this
        val dates=fallback.attributes.filterKeys { it in setOf("DateTimeOriginal","OffsetTimeOriginal","SubSecTimeOriginal") }
        val checked=fromAttributes((attributes - setOf("OffsetTimeOriginal","SubSecTimeOriginal"))+dates)
        return checked.copy(warning=warning ?: checked.warning,issues=MetadataIssues.bounded(issues+checked.issues),
            sourceCreateDate=fallback.sourceCreateDate,indexTimeZone=fallback.indexTimeZone,indexTimeMillis=fallback.indexTimeMillis,
            sourceOffsetPresent=fallback.sourceOffsetPresent,imprintFallback=imprintFallback,imprintFallbackKind=imprintFallbackKind)
    }
    internal fun hasCaptureFacts():Boolean=attributes.isNotEmpty()

    fun livePhotoDates(exportMillis:Long):dev.filmroom.livephoto.core.LivePhotoDates {
        val original=attributes["DateTimeOriginal"]?.takeIf { parseImprintDate(it)!=null }
        val source=sourceCreateDate?.takeIf { validatedSourceInstant(attributes,it)!=null }?.let(OffsetDateTime::parse)
        val now=Instant.ofEpochMilli(exportMillis).atOffset(ZoneOffset.UTC)
        return dev.filmroom.livephoto.core.LivePhotoDates(
            original=original,
            originalOffset=if(original!=null) attributes["OffsetTimeOriginal"] ?: source?.offset?.id?.let{if(it=="Z")"+00:00" else it} else null,
            originalSubsec=if(original!=null) attributes["SubSecTimeOriginal"] ?: source?.takeIf{it.nano!=0}?.nano?.toString()?.padStart(9,'0')?.trimEnd('0') else null,
            modified=DATE.format(now),modifiedOffset="+00:00",modifiedSubsec=String.format(Locale.ROOT,"%03d",now.nano/1_000_000))
    }

    /** A timestamp without its original offset is a local wall time, not an invented UTC instant. */
    fun captureTimeMillis():Long?=runCatching {
        val date=attributes["DateTimeOriginal"] ?: return null
        sourceCreateDate?.let { validatedSourceInstant(attributes,it) }?.let { return it }
        val offset=attributes["OffsetTimeOriginal"] ?: return null
        val base=LocalDateTime.parse(date,DATE).toInstant(ZoneOffset.of(offset)).toEpochMilli()
        val sub=attributes["SubSecTimeOriginal"].orEmpty().take(3).padEnd(3,'0').toIntOrNull() ?: 0
        Math.addExact(base,sub.toLong())
    }.getOrNull()

    /** Gallery indexing needs an epoch even for old cameras without OffsetTimeOriginal.
     * Preserve the original wall clock using the import device's frozen local zone. This
     * indexing assumption does not alter source facts or export time; the approved output
     * compatibility writer may add an explicitly diagnosed inferred offset. */
    fun mediaStoreCaptureTimeMillis():Long?=captureTimeMillis() ?: if(sourceOffsetPresent)null else indexTimeMillis ?: localIndexTime(attributes,indexTimeZone ?: ZoneId.systemDefault().id)

    /** Approved compatibility interpretation, not a claim about the original camera zone. */
    internal fun inferredOriginalOffset():String? {
        if(sourceOffsetPresent || captureTimeMillis()!=null)return null
        return runCatching {
            val date=attributes["DateTimeOriginal"] ?: return null
            val wall=LocalDateTime.parse(date,DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
            val sub=attributes["SubSecTimeOriginal"].orEmpty().take(3).padEnd(3,'0').toIntOrNull() ?: 0
            val epoch=mediaStoreCaptureTimeMillis() ?: return null
            val offset=Math.subtractExact(Math.addExact(wall,sub.toLong()),epoch)
            require(offset%60000L==0L && offset in -64800000L..64800000L)
            ZoneOffset.ofTotalSeconds((offset/1000).toInt()).id.let{if(it=="Z")"+00:00" else it}
        }.getOrNull()
    }
    val diagnosticDateBasis:MetadataDateBasis? get()=if(inferredOriginalOffset()!=null)MetadataDateBasis.ASSUMED_IMPORT_ZONE else null


    fun exifBytes(width:Int,height:Int,colorSpace:String,exportMillis:Long):ByteArray {
        require(width>0 && height>0)
        val groups=Array(3){mutableListOf<Entry>()}
        attributes.forEach { (key,value)->SCHEMA[key]?.let { s->groups[s.group].add(Entry(s.tag,s.type,encode(s,value))) } }
        fun add(group:Int,tag:Int,type:Int,value:String) { groups[group].removeAll{it.tag==tag};groups[group].add(Entry(tag,type,encode(Spec(group,tag,type),value))) }
        // Only fill a missing original offset from a matching, explicitly zoned source XMP
        // capture date. Persisted sourceCreateDate records provenance; no device-zone guess.
        if(sourceCreateDate!=null && validatedSourceInstant(attributes,sourceCreateDate)!=null) {
            val source=OffsetDateTime.parse(sourceCreateDate)
            if(attributes["OffsetTimeOriginal"]==null)add(1,0x9011,2,if(source.offset==ZoneOffset.UTC)"+00:00" else source.offset.id)
            if(attributes["SubSecTimeOriginal"]==null && source.nano!=0)add(1,0x9291,2,source.nano.toString().padStart(9,'0').trimEnd('0'))
        }
        // User-approved gallery compatibility: retain the original wall clock, but supply
        // a missing offset from the frozen import-zone interpretation. Snapshot attributes
        // remain the source facts; diagnostics explicitly label this offset as assumed.
        inferredOriginalOffset()?.let{add(1,0x9011,2,it)}
        val now=Instant.ofEpochMilli(exportMillis).atOffset(ZoneOffset.UTC)
        add(0,0x100,4,width.toString());add(0,0x101,4,height.toString());add(0,0x112,3,"1")
        add(0,0x132,2,DATE.format(now));add(1,0x9010,2,"+00:00");add(1,0x9290,2,String.format(Locale.ROOT,"%03d",now.nano/1_000_000))
        add(1,0xA002,4,width.toString());add(1,0xA003,4,height.toString());add(1,0xA001,3,if(colorSpace=="sRGB")"1" else "65535")
        add(1,0x9000,7,"0232")
        groups[0].add(Entry(0x8769,4,ByteArray(4)))
        if(groups[2].isNotEmpty())groups[0].add(Entry(0x8825,4,ByteArray(4)))
        groups.forEach { it.sortBy{e->e.tag} }
        val starts=IntArray(3);var end=8
        groups.forEachIndexed { i,g->if(g.isNotEmpty()){starts[i]=end;end+=2+12*g.size+4} }
        groups[0].first{it.tag==0x8769}.data.putU32(0,starts[1].toLong())
        groups[0].firstOrNull{it.tag==0x8825}?.data?.putU32(0,starts[2].toLong())
        val payload=ByteArrayOutputStream();val header=ByteArray(end);header[0]=73;header[1]=73;header[2]=42;header.putU32(4,8)
        groups.forEachIndexed { i,g->if(g.isNotEmpty()) {
            header.putU16(starts[i],g.size)
            g.forEachIndexed { n,e->val p=starts[i]+2+12*n;header.putU16(p,e.tag);header.putU16(p+2,e.type);header.putU32(p+4,(e.data.size/sizeOf(e.type)).toLong())
                if(e.data.size<=4)e.data.copyInto(header,p+8) else {header.putU32(p+8,(end+payload.size()).toLong());payload.write(e.data);if(payload.size()%2!=0)payload.write(0)} }
        } }
        return (header+payload.toByteArray()).also { require(it.size<=BUDGET) }
    }

    companion object {
        const val BUDGET=48*1024
        private val DATE=DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
        internal fun parseImprintDate(value:String):LocalDateTime?=runCatching {
            require(value.matches(Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
            LocalDateTime.parse(value,DATE).also{require(it.year in 1..9999)}
        }.getOrNull()
        private data class Spec(val group:Int,val tag:Int,val type:Int,val count:Int=1)
        private data class Entry(val tag:Int,val type:Int,val data:ByteArray)
        // No source geometry, color profile, IFD pointers, thumbnails, strip offsets or MakerNote.
        private val SCHEMA=linkedMapOf<String,Spec>().apply {
            fun tags(group:Int,type:Int,vararg entries:Pair<String,Int>) {entries.forEach{put(it.first,Spec(group,it.second,type))}}
            tags(0,2,"ImageDescription" to 0x10e,"Make" to 0x10f,"Model" to 0x110,"Artist" to 0x13b,"Copyright" to 0x8298)
            tags(0,3,"Rating" to 0x4746,"RatingPercent" to 0x4749)
            tags(1,2,"DateTimeOriginal" to 0x9003,"DateTimeDigitized" to 0x9004,"OffsetTimeOriginal" to 0x9011,"OffsetTimeDigitized" to 0x9012,"SubSecTimeOriginal" to 0x9291,"SubSecTimeDigitized" to 0x9292,"CameraOwnerName" to 0xA430,"BodySerialNumber" to 0xA431,"LensMake" to 0xA433,"LensModel" to 0xA434,"LensSerialNumber" to 0xA435,"ImageUniqueID" to 0xA420)
            tags(1,5,"ExposureTime" to 0x829a,"FNumber" to 0x829d,"ApertureValue" to 0x9202,"MaxApertureValue" to 0x9205,"SubjectDistance" to 0x9206,"FocalLength" to 0x920a,"DigitalZoomRatio" to 0xA404,"ExposureIndex" to 0xA215)
            tags(1,10,"ShutterSpeedValue" to 0x9201,"BrightnessValue" to 0x9203,"ExposureBiasValue" to 0x9204)
            tags(1,3,"PhotographicSensitivity" to 0x8827,"SensitivityType" to 0x8830,"ExposureProgram" to 0x8822,"MeteringMode" to 0x9207,"LightSource" to 0x9208,"Flash" to 0x9209,"FocalLengthIn35mmFilm" to 0xA405,"ExposureMode" to 0xA402,"WhiteBalance" to 0xA403,"SceneCaptureType" to 0xA406,"GainControl" to 0xA407,"Contrast" to 0xA408,"Saturation" to 0xA409,"Sharpness" to 0xA40A,"SubjectDistanceRange" to 0xA40C)
            tags(1,4,"StandardOutputSensitivity" to 0x8831,"RecommendedExposureIndex" to 0x8832,"ISOSpeed" to 0x8833)
            put("LensSpecification",Spec(1,0xA432,5,4));put("UserComment",Spec(1,0x9286,7))
            tags(2,2,"GPSLatitudeRef" to 1,"GPSLongitudeRef" to 3,"GPSSatellites" to 8,"GPSStatus" to 9,"GPSMeasureMode" to 10,"GPSSpeedRef" to 12,"GPSTrackRef" to 14,"GPSImgDirectionRef" to 16,"GPSMapDatum" to 18,"GPSDestLatitudeRef" to 19,"GPSDestLongitudeRef" to 21,"GPSDestBearingRef" to 23,"GPSDestDistanceRef" to 25,"GPSDateStamp" to 29)
            tags(2,5,"GPSAltitude" to 6,"GPSDOP" to 11,"GPSSpeed" to 13,"GPSTrack" to 15,"GPSImgDirection" to 17,"GPSDestBearing" to 24,"GPSDestDistance" to 26,"GPSHPositioningError" to 31)
            put("GPSAltitudeRef",Spec(2,5,1));put("GPSDifferential",Spec(2,30,3))
            for((key,tag) in listOf("GPSLatitude" to 2,"GPSLongitude" to 4,"GPSTimeStamp" to 7,"GPSDestLatitude" to 20,"GPSDestLongitude" to 22))put(key,Spec(2,tag,5,3))
            put("GPSVersionID",Spec(2,0,1,4));put("GPSProcessingMethod",Spec(2,27,7));put("GPSAreaInformation",Spec(2,28,7))
        }
        fun unavailable():PhotoMetadata=PhotoMetadata(emptyMap(),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_62634ce08e7b),issues=listOf(MetadataIssue(MetadataField.METADATA,MetadataIssueReason.READ_FAILED)))
        fun readCaptureDates(input:java.io.InputStream):PhotoMetadata=try {
            val exif=ExifInterface(input)
            val fields=listOf("DateTimeOriginal","OffsetTimeOriginal","SubSecTimeOriginal").mapNotNull{key->exif.getAttribute(key)?.let{key to it}}.toMap()
            val checked=fromAttributes(fields)
            val hint=runCatching{XmpCaptureTime.read(exif.getAttributeBytes(ExifInterface.TAG_XMP))}.getOrNull()?.takeIf{validatedSourceInstant(checked.attributes,it)!=null}
            checked.copy(sourceCreateDate=hint)
        }catch(_:Exception){unavailable()}
        /** Standard metadata from an already authorized stream; no URI or permission bypass. */
        fun readCaptureMetadata(input:java.io.InputStream):PhotoMetadata=try {
            fromExif(ExifInterface(input),null)
        }catch(_:Exception){unavailable()}catch(_:OutOfMemoryError){unavailable()}
        fun read(file:File):PhotoMetadata=try {
            val cr3=CanonCr3CaptureMetadata.read(file,SCHEMA.values.map{it.group to it.tag}.toSet())
            if(cr3!=null)fromCanonCr3(cr3) else fromExif(ExifInterface(file.absolutePath),file)
        } catch(_:Exception){unavailable()} catch(_:OutOfMemoryError){unavailable()}
        private fun fromExif(exif:ExifInterface,file:File?):PhotoMetadata {
            var unsupportedText=false
            val textIssues=mutableListOf<MetadataIssue>()
            val fields=SCHEMA.keys.mapNotNull { key->
                val value=if(key=="GPSVersionID") {
                    // EXIF type BYTE[4], not an ASCII string (AndroidX exposes raw controls).
                    exif.getAttributeBytes(key)?.let{bytes->if(bytes.size==4)bytes.joinToString(","){(it.toInt() and 255).toString()}else ""}
                } else if(key in setOf("UserComment","GPSProcessingMethod","GPSAreaInformation")) {
                    val bytes=exif.getAttributeBytes(key)
                    // Several cameras reserve an entirely zero-filled, unspecified-encoding
                    // comment. It contains no text to lose; do not report a metadata failure.
                    if(bytes==null || (bytes.size in 8..8192 && bytes.all { it==0.toByte() }))null else try {decodeComment(bytes)}catch(_:Exception){unsupportedText=true;textIssues+=MetadataIssue(checkNotNull(MetadataField.fromId(key)),MetadataIssueReason.UNSUPPORTED_TEXT_ENCODING);null}
                } else exif.getAttribute(key) ?: if(key=="PhotographicSensitivity")exif.getAttribute("ISOSpeedRatings")else null
                value?.let{key to it}
            }.toMap()
            val checked=fromAttributes(fields + listOfNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME)?.let{"DateTime" to it}).toMap()).withImprintFallback(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),"created")
            val sourceHint=runCatching { XmpCaptureTime.read(exif.getAttributeBytes(ExifInterface.TAG_XMP)) }.getOrNull()?.takeIf{validatedSourceInstant(checked.attributes,it)!=null}
            val safeFields=checked.attributes
            val nikon=fields["Make"]?.trim()?.uppercase(Locale.ROOT) in setOf("NIKON", "NIKON CORPORATION")
            val fallback=if(file!=null && nikon && (safeFields["PhotographicSensitivity"]==null || safeFields["LensSpecification"]==null || (safeFields["OffsetTimeOriginal"]==null && sourceHint==null)))NikonCaptureFallback.read(file)else emptyMap()
            val merged=fields.toMutableMap()
            fallback.forEach{(key,value)->if(safeFields[key]==null && !(key=="OffsetTimeOriginal" && (sourceHint!=null || fields.containsKey("OffsetTimeOriginal"))))merged[key]=value}
            val result=(if(fallback.isEmpty())checked else fromAttributes(merged).withImprintFallback(checked.imprintFallback,checked.imprintFallbackKind.orEmpty())).let { metadata ->
                if(sourceHint!=null)metadata.copy(sourceCreateDate=sourceHint) else metadata
            }
            val sourceResult=result.copy(sourceOffsetPresent=result.sourceOffsetPresent || exif.hasAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            return if(unsupportedText)sourceResult.copy(warning=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5de5710e49a1),issues=MetadataIssues.bounded(sourceResult.issues+textIssues))else sourceResult
        }
        private fun fromCanonCr3(cmt:CanonCr3CaptureMetadata.Result):PhotoMetadata {
            val fields=linkedMapOf<String,String>()
            SCHEMA.forEach { (key,spec)->cmt.fields[spec.group to spec.tag]?.let { field ->
                require(field.type==spec.type)
                val bytes=field.bytes;val buffer=ByteBuffer.wrap(bytes).order(field.order)
                val value=when(spec.type) {
                    2->{require(bytes.all{(it.toInt() and 255)<128});bytes.toString(Charsets.US_ASCII).trimEnd('\u0000')}
                    7->if(bytes.all{it==0.toByte()})null else decodeComment(bytes)
                    1->bytes.joinToString(","){(it.toInt() and 255).toString()}
                    3->List(field.count){(buffer.short.toInt() and 65535).toString()}.joinToString(",")
                    4->List(field.count){(buffer.int.toLong() and 0xffffffffL).toString()}.joinToString(",")
                    5,10->{
                        val numbers=List(field.count){
                            val n=buffer.int;val d=buffer.int
                            if(spec.type==5)"${n.toLong() and 0xffffffffL}/${d.toLong() and 0xffffffffL}" else "$n/$d"
                        }
                        // Canon sometimes stores degree/minute or hour/minute only; seconds are zero.
                        (if(spec.group==2 && spec.tag in listOf(2,4,7) && numbers.size==2)numbers+"0/1" else numbers).joinToString(",")
                    }
                    else->error("Capture field type")
                }
                value?.let{fields[key]=it}
            } }
            if(fields.isEmpty())return unavailable()
            val checked=fromAttributes(fields)
            val hint=XmpCaptureTime.read(cmt.xmp)?.takeIf{validatedSourceInstant(checked.attributes,it)!=null}
            return if(hint!=null)checked.copy(sourceCreateDate=hint) else checked
        }
        fun fromJson(text:String):PhotoMetadata=try {
            require(text.toByteArray(Charsets.UTF_8).size<=BUDGET)
            val j=JSONObject(text);require(j.getInt("version")==1);val a=j.getJSONObject("attributes")
            val fields=a.keys().asSequence().associateWith { key->a.getString(key) }
            val hint=j.optString("sourceCreateDate").takeIf{it.isNotEmpty() && validatedSourceInstant(fields,it)!=null}
            val checked=fromAttributes(fields)
            val persistedZone=j.optString("indexTimeZone").takeIf{it.length in 1..128 && runCatching{ZoneId.of(it)}.isSuccess}
            val zone=persistedZone ?: checked.indexTimeZone
            val derived=zone?.let{localIndexTime(fields,it)}
            val stored=if(j.has("indexTimeMillis") && !j.isNull("indexTimeMillis"))runCatching{j.getLong("indexTimeMillis")}.getOrNull() else null
            // Accept only an indexing instant consistent with the persisted original wall time.
            val frozen=stored?.takeIf{derived!=null && it==derived} ?: derived
            val result=checked.copy(sourceCreateDate=hint,indexTimeZone=zone,indexTimeMillis=frozen,sourceOffsetPresent=checked.sourceOffsetPresent || j.optBoolean("sourceOffsetPresent",false),issues=MetadataIssues.bounded(checked.issues+MetadataIssues.parse(j.optString("issues"))))
            result.copy(warning=result.warning ?: if(j.isNull("warning"))null else j.optString("warning").take(160).takeIf{it.isNotEmpty()}).withImprintFallback(j.optString("imprintFallback"),j.optString("imprintFallbackKind"))
        }catch(_:Exception){unavailable()}
        internal fun fromAttributes(input:Map<String,String>):PhotoMetadata {
            val accepted=linkedMapOf<String,String>();val rejected=mutableListOf<MetadataIssue>()
            fun reject(key:String,reason:MetadataIssueReason) {
                val issue=MetadataIssue(MetadataField.fromId(key) ?: MetadataField.UNKNOWN,reason)
                if(issue !in rejected) {
                    if(rejected.size<MetadataIssues.MAX_ITEMS)rejected+=issue
                    else rejected[MetadataIssues.MAX_ITEMS-1]=MetadataIssue(MetadataField.METADATA,MetadataIssueReason.ISSUE_LIMIT)
                }
            }
            input.forEach { (key,raw)->if(key=="DateTime")return@forEach
                val spec=SCHEMA[key]
                if(spec==null){reject(key,MetadataIssueReason.UNKNOWN_FIELD);return@forEach}
                if(raw.length>4096){reject(key,MetadataIssueReason.FIELD_TOO_LONG);return@forEach}
                var reason=MetadataIssueReason.INVALID_TEXT
                try {
                    require(!raw.contains('\u0000'))
                    val value=if(key=="GPSTimeStamp" && raw.contains(':'))raw.replace(':',',') else if(key=="GPSVersionID")raw.replace('.',',') else raw
                    if(key.startsWith("DateTime")){reason=MetadataIssueReason.INVALID_DATE;LocalDateTime.parse(value,DATE)}
                    if(key.startsWith("OffsetTime")){reason=MetadataIssueReason.INVALID_OFFSET;require(value.matches(Regex("[+-]\\d{2}:\\d{2}")));ZoneOffset.of(value)}
                    if(key.startsWith("SubSec")){reason=MetadataIssueReason.INVALID_SUBSECONDS;require(value.matches(Regex("[0-9]{1,12}")))}
                    reason=if(spec.type in listOf(1,3,4,5,10))MetadataIssueReason.INVALID_NUMERIC else MetadataIssueReason.INVALID_TEXT
                    encode(spec,value);accepted[key]=value
                    // Keep the original 48 KiB budget and reserve generated fields/diagnostic codes.
                    if(PhotoMetadata(accepted).toJson().toByteArray().size>BUDGET-4096 || PhotoMetadata(accepted).exifBytes(1,1,"sRGB",0).size>BUDGET-4096){accepted.remove(key);reject(key,MetadataIssueReason.SNAPSHOT_BUDGET)}
                }catch(_:Exception){accepted.remove(key);reject(key,reason)}
            }
            val zone=if(accepted.containsKey("DateTimeOriginal") && !accepted.containsKey("OffsetTimeOriginal"))ZoneId.systemDefault().id else null
            return PhotoMetadata(accepted.toMap(),if(rejected.isNotEmpty())dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_97154723b761) else null,issues=rejected.toList(),indexTimeZone=zone,indexTimeMillis=zone?.let{localIndexTime(accepted,it)},sourceOffsetPresent=input.containsKey("OffsetTimeOriginal")).withImprintFallback(input["DateTime"],"modified").withImprintFallback(accepted["DateTimeDigitized"],"created")
        }

        private fun localIndexTime(fields:Map<String,String>,zone:String):Long?=runCatching {
            val date=fields["DateTimeOriginal"] ?: return null
            val local=LocalDateTime.parse(date,DATE)
            val sub=fields["SubSecTimeOriginal"].orEmpty().take(3).padEnd(3,'0').toIntOrNull() ?: 0
            // java.time resolves gaps forward and overlaps to the earlier valid offset.
            Math.addExact(local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli(),sub.toLong())
        }.getOrNull()

        /** XMP supplies an explicit source offset; never infer a zone from the exporting phone. */
        private fun validatedSourceInstant(fields:Map<String,String>,iso:String):Long?=runCatching {
            require(iso.length<=64)
            val source=OffsetDateTime.parse(iso)
            require(source.offset.totalSeconds%60==0)
            fields["OffsetTimeOriginal"]?.let { require(ZoneOffset.of(it)==source.offset) }
            val original=LocalDateTime.parse(fields["DateTimeOriginal"] ?: return null,DATE)
            require(source.toLocalDateTime().withNano(0)==original)
            val sub=fields["SubSecTimeOriginal"]
            if(sub!=null)require(source.nano==(sub.take(9).padEnd(9,'0').toIntOrNull() ?: return null))
            source.toInstant().toEpochMilli()
        }.getOrNull()
        private fun decodeComment(bytes:ByteArray):String {
            require(bytes.size in 8..8192)
            val prefix=bytes.copyOfRange(0,8).toString(Charsets.US_ASCII)
            val body=bytes.copyOfRange(8,bytes.size)
            val charset=when(prefix) {
                "ASCII\u0000\u0000\u0000"->{require(body.all{it.toInt() in 0..127});Charsets.US_ASCII}
                "UNICODE\u0000"->{require(body.size>=2 && ((body[0]==0xfe.toByte() && body[1]==0xff.toByte()) || (body[0]==0xff.toByte() && body[1]==0xfe.toByte())));Charsets.UTF_16}
                else->throw IllegalArgumentException("Unsupported EXIF text encoding")
            }
            return charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString().trimEnd('\u0000')
        }
        private fun sizeOf(type:Int)=when(type){1,2,7->1;3->2;4->4;5,10->8;else->error("type")}
        private fun encode(s:Spec,value:String):ByteArray {
            if(s.type==2){require(value.all{it.code in 1..127});return value.toByteArray(Charsets.US_ASCII)+byteArrayOf(0)}
            if(s.type==7) {
                if(s.tag==0x9000)return value.toByteArray(Charsets.US_ASCII)
                return if(value.all{it.code in 1..127})"ASCII\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)+value.toByteArray(Charsets.US_ASCII)
                else "UNICODE\u0000".toByteArray(Charsets.US_ASCII)+byteArrayOf(0xfe.toByte(),0xff.toByte())+value.toByteArray(Charsets.UTF_16BE)
            }
            val parts=value.split(',').map{it.trim()};require(parts.size==s.count)
            val b=ByteBuffer.allocate(parts.size*sizeOf(s.type)).order(ByteOrder.LITTLE_ENDIAN)
            parts.forEach { part->when(s.type) {
                1->{val v=part.toLong();require(v in 0..255);b.put(v.toByte())}
                3->{val v=part.toLong();require(v in 0..65535);b.putShort(v.toShort())}
                4->{val v=part.toLong();require(v in 0..0xffffffffL);b.putInt(v.toInt())}
                5,10->{val (n,d)=rational(part);val lo=if(s.type==5)0L else Int.MIN_VALUE.toLong();val hi=if(s.type==5)0xffffffffL else Int.MAX_VALUE.toLong();require(n in lo..hi && (d in 1..hi || (s.tag==0xA432 && n==0L && d==0L)));b.putInt(n.toInt());b.putInt(d.toInt())}
            } }
            return b.array()
        }
        private fun rational(value:String):Pair<Long,Long> {
            if(value.contains('/')) {val p=value.split('/');require(p.size==2);return p[0].toLong() to p[1].toLong()}
            val parsed=BigDecimal(value);require(parsed.precision()<=64 && parsed.scale() in -20..30)
            val d=parsed.stripTrailingZeros()
            val denominator=if(d.scale()>0)java.math.BigInteger.TEN.pow(d.scale())else java.math.BigInteger.ONE
            val numerator=d.multiply(BigDecimal(denominator)).toBigIntegerExact();val gcd=numerator.gcd(denominator)
            val n=numerator.divide(gcd);val q=denominator.divide(gcd)
            if(n.abs()<=java.math.BigInteger.valueOf(0xffffffffL) && q<=java.math.BigInteger.valueOf(0xffffffffL))return n.toLong() to q.toLong()
            // Legacy rational tags are exposed as decimal doubles. Recover a bounded rational
            // (e.g. 0.0003333333333333333 -> 1/3000), never copy a broken zero denominator.
            val sign=if(parsed.signum()<0)-1 else 1;var x=kotlin.math.abs(parsed.toDouble());require(x.isFinite())
            val target=x;var h0=0L;var h1=1L;var k0=1L;var k1=0L
            for(iteration in 0 until 32) {
                val a=kotlin.math.floor(x);if(a>0xffffffffL)break
                if(h1>0 && a>(0xffffffffL-h0)/h1 || k1>0 && a>(Int.MAX_VALUE.toLong()-k0)/k1)break
                val h=a.toLong()*h1+h0;val k=a.toLong()*k1+k0
                if(h>0xffffffffL || k>Int.MAX_VALUE || h<0 || k<=0)break
                h0=h1;h1=h;k0=k1;k1=k
                if(kotlin.math.abs(h.toDouble()/k-target)<=maxOf(1e-15,target*1e-13))return sign*h to k
                val rem=x-a;if(rem==0.0)return sign*h to k;x=1.0/rem
            }
            require(k1>0);return sign*h1 to k1
        }
        private fun ByteArray.putU16(p:Int,v:Int){this[p]=v.toByte();this[p+1]=(v ushr 8).toByte()}
        private fun ByteArray.putU32(p:Int,v:Long){for(i in 0..3)this[p+i]=(v ushr (8*i)).toByte()}
    }
}
