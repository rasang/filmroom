package dev.filmroom.livephoto.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

private class FakeSink:MediaSink {
    data class Row(val name:String,val mime:String,val path:String,var bytes:ByteArray=byteArrayOf(),var pending:Boolean=true,val owned:Boolean=true)
    val rows=linkedMapOf<String,Row>()
    var inserts=0; var copies=0; var publishes=0
    var failInsertAt=0; var failCopyAt=0; var failPublishAt=0; var failDelete=false; var throwAfterInsert=false
    override fun insertPending(displayName:String,mimeType:String,relativePath:String):String {
        inserts++
        if(inserts==failInsertAt)throw java.io.IOException("insert")
        val id="row-$inserts"; rows[id]=Row(displayName,mimeType,relativePath)
        if(throwAfterInsert)throw java.io.IOException("crash window after insert")
        return id
    }
    override fun copyAndVerify(id:String,source:File,expectedSha256:String,checkActive:()->Unit) {
        copies++; checkActive()
        if(copies==failCopyAt)throw java.io.IOException("copy")
        rows.getValue(id).bytes=source.readBytes()
        check(FileDigests.sha256(source)==expectedSha256)
    }
    override fun publish(id:String) { publishes++; if(publishes==failPublishAt)throw java.io.IOException("publish"); rows.getValue(id).pending=false }
    override fun deleteOwned(id:String):Boolean {
        if(failDelete)return false
        val row=rows[id] ?: return true
        check(row.owned) { "foreign row" }; rows.remove(id); return true
    }
    override fun existsOwned(id:String)=rows[id]?.owned==true
    override fun findOwned(displayName:String,mimeType:String,relativePath:String)=rows.filter { (_,r)->r.owned && r.name==displayName && r.mime==mimeType && r.path==relativePath }.keys.toList()
}

fun main(args:Array<String>) {
    require(args.size==2) { "Usage: CoreSelfTestKt <new-output-directory> <fixture-directory>" }
    val out=File(args[0]); out.mkdirs(); val fixtures=File(args[1])
    val jpg=File(fixtures,"cover.jpg"); val mp4=File(fixtures,"clip_sdr.mp4")
    check(jpg.isFile && mp4.isFile)
    val checks=mutableListOf<Triple<String,Boolean,String>>()
    fun test(name:String,block:()->Unit) {
        try { block(); checks+=Triple(name,true,""); println("PASS $name") }
        catch(t:Throwable) { checks+=Triple(name,false,t.toString()); println("FAIL $name: $t"); t.printStackTrace() }
    }
    fun reject(block:()->Unit) {
        var thrown=false
        try { block() } catch(_:Exception) { thrown=true }
        check(thrown) { "Invalid input was accepted" }
    }
    val cover=CoverInfo(0,0,30,1_000_000)
    val originals=listOf(FileDigests.sha256(jpg),FileDigests.sha256(mp4))
    val assets=linkedMapOf<LivePhotoProfile,PackagedAsset>()
    fun pack(profile:LivePhotoProfile,name:String=profile.name)=LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,profile,cover,true),File(out,name))
    for(profile in LivePhotoProfile.values()) {
        test("${profile.name}: package and verify") {
            val a=pack(profile); assets[profile]=a
            val r=LivePhotoVerifier.verify(a)
            check(r.videoRange.length==mp4.length())
            check(!r.galleryConfirmed)
            check(r.videoSha256==originals[1])
        }
        test("${profile.name}: extracted bytes identical") {
            val a=assets.getValue(profile)
            val extracted=File(out,"${profile.name}.extracted.mp4")
            LivePhotoVerifier.extractVideo(a,extracted)
            check(FileDigests.sha256(extracted)==originals[1])
        }
        test("${profile.name}: full JPEG pixel decode") {
            val b=ImageIO.read(assets.getValue(profile).image.file)
            val original=ImageIO.read(jpg)
            check(b!=null && b.width==original.width && b.height==original.height)
            check(b.getRGB(0,0,b.width,b.height,null,0,b.width).contentEquals(original.getRGB(0,0,original.width,original.height,null,0,original.width)))
        }
    }
    test("inputs remain unchanged") { check(originals==listOf(FileDigests.sha256(jpg),FileDigests.sha256(mp4))) }
    test("OEM profile requires opt-in") { reject { LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.OPLUS,cover),File(out,"opt-in-missing")) } }
    test("existing directory is not overwritten or removed") {
        val d=File(out,"existing"); d.mkdirs(); File(d,"sentinel").writeText("safe")
        reject { LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.GOOGLE,cover),d) }
        check(File(d,"sentinel").readText()=="safe")
    }
    test("cancellation deletes only SDK-created output") {
        val d=File(out,"cancelled"); var calls=0
        reject { LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.GOOGLE,cover),d) { if(++calls>=4)throw CancellationException() } }
        check(!d.exists()); check(jpg.isFile && mp4.isFile)
    }
    test("vivo legacy rejects non-first-frame cover") {
        reject { LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.VIVO_PAIR_LEGACY,cover.copy(timestampUs=500_000,frameIndex=15),true),File(out,"wrong-legacy-cover")) }
    }
    test("vivo writer rejects non-hex ID") {
        reject { LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.VIVO_PAIR_2104,cover,true,"z".repeat(28)),File(out,"wrong-id")) }
    }
    test("cover time outside clip is rejected") { reject { CoverInfo(1_000_000,0,30,1_000_000) } }
    test("cover index outside frame count is rejected") { reject { CoverInfo(0,30,30,1_000_000) } }
    test("MP4 rejects truncated final box") {
        val f=File(out,"truncated.mp4"); f.writeBytes(mp4.readBytes().dropLast(10).toByteArray()); reject { Mp4.validate(f) }
    }
    test("MP4 rejects size=0 before append") {
        val f=File(out,"size-zero.mp4"); f.writeBytes(mp4.readBytes())
        val last=Mp4.validate(f).last(); RandomAccessFile(f,"rw").use { it.seek(last.start);it.writeInt(0) }
        reject { Mp4.validate(f) }
    }
    test("MP4 rejects extended size larger than signed range") {
        val f=File(out,"overflow.mp4"); f.writeBytes(be32(1)+ascii("ftyp")+ByteArray(8){0xff.toByte()}+ByteArray(10))
        reject { Mp4.validate(f) }
    }
    test("MP4 ftyp plus empty moov/mdat is not valid media") {
        val f=File(out,"empty.mp4"); f.writeBytes(be32(24)+ascii("ftypisom")+be32(0)+ascii("isommp42")+be32(8)+ascii("moov")+be32(9)+ascii("mdat")+byteArrayOf(0))
        reject { Mp4.validate(f) }
    }
    test("JPEG scanner ignores EOI inside APP1") {
        val b=jpg.readBytes(); val fake=Jpeg.app(0xe1,byteArrayOf(12,0xff.toByte(),0xd9.toByte(),34))
        val wrapped=b.copyOfRange(0,2)+fake+b.copyOfRange(2,b.size)
        check(Jpeg.inspect(wrapped).end==wrapped.size)
    }
    test("JPEG scanner rejects missing primary EOI") { reject { Jpeg.inspect(jpg.readBytes().dropLast(2).toByteArray()) } }
    test("cover cleaning refuses existing appended video") { reject { Jpeg.clean(jpg.readBytes()+mp4.readBytes()) } }
    test("APP length uint16 overflow rejected") { reject { Jpeg.app(0xe1,ByteArray(65534)) } }
    test("MPF uint32 overflow rejected") { reject { Jpeg.mpf(0x1_0000_0000) } }
    val id="0123456789abcdef0123456789ab"
    val meta=Vivo.metadata(id,LivePhotoProfile.VIVO_PAIR_2104,false,0)
    val tail=Vivo.tail(meta)
    test("vivo UTF-8 JSON byte lengths roundtrip") {
        val special=meta+mapOf("note" to "测试 cameralbum! 字段")
        check(Vivo.parse(Vivo.tail(special)).fields==special)
    }
    test("vivo bad signature rejected") { val b=tail.clone(); b[b.lastIndex]=0; reject { Vivo.parse(b) } }
    test("vivo bad JSON-length word rejected") {
        val b=tail.clone(); val j=FlatJson.encode(meta).toByteArray().size; b[4+j+3]=(b[4+j+3]+1).toByte(); reject { Vivo.parse(b) }
    }
    test("vivo binary ID conflict rejected") { val b=tail.clone(); b[b.size-15-28]='f'.code.toByte(); reject { Vivo.parse(b) } }
    test("vivo truncated tail rejected") { reject { Vivo.parse(tail.dropLast(3).toByteArray()) } }
    test("duplicate JSON binding key rejected") { reject { FlatJson.parse("{\"version\":2104,\"version\":2200}") } }
    test("JSON trailing data rejected") { reject { FlatJson.parse("{\"a\":1}ignored") } }
    test("JSON integer overflow rejected") { reject { FlatJson.parse("{\"a\":999999999999999999999999}") } }
    test("JSON leading zeros rejected") { reject { FlatJson.parse("{\"a\":01}") } }
    test("JSON nested values rejected") { reject { FlatJson.parse("{\"a\":{\"b\":1}}") } }
    val xml=Xmp.build(LivePhotoProfile.GOOGLE,mp4.length(),mp4.length(),0,0)
    test("XMP namespace aliases accepted") { check(Xmp.inspect(xml.replace("GCamera:","Camera:").replace("xmlns:GCamera=","xmlns:Camera=")).extent==mp4.length()) }
    test("XMP disabled flag rejected") { reject { Xmp.inspect(xml.replace("MotionPhoto=\"1\"","MotionPhoto=\"0\"")) } }
    test("XMP conflicting property element rejected") { reject { Xmp.inspect(xml.replace("</rdf:Description>","<GCamera:MotionPhoto>0</GCamera:MotionPhoto></rdf:Description>")) } }
    test("XMP DTD and entities rejected") { reject { Xmp.inspect("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>"+xml) } }
    test("XMP negative video extent rejected") { reject { Xmp.inspect(xml.replace("Item:Length=\"${mp4.length()}\"","Item:Length=\"-1\"")) } }
    test("Google profile omits video padding") { check(!Xmp.inspect(xml).secondaryPadding) }
    test("Oplus reports its nonstandard video padding") { check(assets.getValue(LivePhotoProfile.OPLUS).report.googleLayoutChecksPassed==false) }
    test("vivo single reports its nonstandard video padding") { check(assets.getValue(LivePhotoProfile.VIVO_SINGLE).report.googleLayoutChecksPassed==false) }
    test("Samsung 107 preserves distinct video/extent/append sizes") {
        val a=assets.getValue(LivePhotoProfile.SAMSUNG_SEF_107)
        check(a.report.motionExtentBytes==mp4.length()+75)
        check(a.image.file.length()-a.report.jpegBytes==mp4.length()+99)
        check(a.report.googleLayoutChecksPassed==false)
    }
    test("Samsung 106 append length") {
        val a=assets.getValue(LivePhotoProfile.SAMSUNG_SEF_106)
        check(a.image.file.length()-a.report.jpegBytes==mp4.length()+56)
    }
    test("Samsung rejects bare V in XMP extent") {
        val a=assets.getValue(LivePhotoProfile.SAMSUNG_SEF_107)
        val bad=File(out,"samsung-bad-xmp.jpg")
        val raw=a.image.file.readBytes(); val text=raw.toString(Charsets.ISO_8859_1)
        val wrong=text.replace("Item:Length=\"${a.report.motionExtentBytes}\"","Item:Length=\"${mp4.length()}\"")
        check(wrong!=text && wrong.length==text.length)
        bad.writeBytes(wrong.toByteArray(Charsets.ISO_8859_1))
        reject { LivePhotoVerifier.inspect(bad,null,a.profile,a.cover,null) }
    }
    test("Samsung rejects corrupt SEFT index size") {
        val a=assets.getValue(LivePhotoProfile.SAMSUNG_SEF_107)
        val bad=File(out,"samsung-index.jpg"); bad.writeBytes(a.image.file.readBytes())
        RandomAccessFile(bad,"rw").use { it.seek(it.length()-8);it.write(le32(0xffffffff)) }
        reject { Samsung.inspect(bad,true) }
    }
    test("Samsung rejects wrong record name") {
        val a=assets.getValue(LivePhotoProfile.SAMSUNG_SEF_107)
        val bad=File(out,"samsung-name.jpg"); bad.writeBytes(a.image.file.readBytes())
        RandomAccessFile(bad,"rw").use { it.seek(a.report.jpegBytes+8);it.writeByte('X'.code) }
        reject { Samsung.inspect(bad,true) }
    }
    test("Honor rejects oversized cover field instead of truncating") { reject { Honor.tail(mp4.length(),CoverInfo(5_000_000,150,180,6_000_000)) } }
    test("Honor LIVE_ is V plus 20") { check(Honor.tail(mp4.length(),cover).copyOfRange(40,60).toString(Charsets.US_ASCII).trim()=="LIVE_${mp4.length()+20}") }
    test("Oplus rejects stale MPEntry JPEG size") {
        val a=assets.getValue(LivePhotoProfile.OPLUS); val bytes=a.image.file.readBytes(); val j=Jpeg.inspect(bytes)
        val s=j.segments.single { it.marker==0xe2 && bytes.startsAt(ascii("MPF\u0000"),it.payload) }
        val t=s.payload+4; val entry=t+50
        be32(j.end-1L).copyInto(bytes,entry+4)
        val f=File(out,"oplus-stale.jpg"); f.writeBytes(bytes)
        reject { LivePhotoVerifier.inspect(f,null,a.profile,a.cover,null) }
    }
    test("vivo pair cross-file mismatch rejected") {
        val a=assets.getValue(LivePhotoProfile.VIVO_PAIR_2104)
        val b=pack(LivePhotoProfile.VIVO_PAIR_2104,"other-pair")
        reject { LivePhotoVerifier.inspect(a.image.file,b.companionVideo!!.file,a.profile,a.cover,a.bindingId) }
    }
    test("output mutation detected before extraction") {
        val a=assets.getValue(LivePhotoProfile.GOOGLE)
        val b=File(out,"changed.jpg"); b.writeBytes(a.image.file.readBytes()+byteArrayOf(1))
        val changed=a.copy(image=a.image.copy(file=b))
        reject { LivePhotoVerifier.verify(changed) }
    }
    val pair=assets.getValue(LivePhotoProfile.VIVO_PAIR_2104)
    fun coordinator(name:String,sink:FakeSink)=PublicationCoordinator(File(out,"journal-$name"),sink)
    test("publication writes both pending rows before visibility") {
        val sink=FakeSink(); val p=coordinator("success",sink)
        val result=p.publish(pair)
        check(result.ids.size==2 && sink.rows.values.all { !it.pending && it.bytes.isNotEmpty() }); check(!result.galleryConfirmed)
    }
    test("publication retry is idempotent") {
        val sink=FakeSink(); val p=coordinator("idempotent",sink)
        val first=p.publish(pair); val second=p.publish(pair)
        check(first.ids==second.ids && sink.inserts==2 && sink.copies==2)
    }
    test("publication rejects traversal path") { val sink=FakeSink(); reject { coordinator("traversal",sink).publish(pair,"DCIM/../Camera/") }; check(sink.rows.isEmpty()) }
    test("publication insert failure rolls back first row") {
        val sink=FakeSink().apply { failInsertAt=2 }; reject { coordinator("insert-fail",sink).publish(pair) }; check(sink.rows.isEmpty())
    }
    test("publication copy failure rolls back all rows") {
        val sink=FakeSink().apply { failCopyAt=2 }; reject { coordinator("copy-fail",sink).publish(pair) }; check(sink.rows.isEmpty())
    }
    test("publication partial visibility failure rolls back both rows") {
        val sink=FakeSink().apply { failPublishAt=2 }; reject { coordinator("visible-fail",sink).publish(pair) }; check(sink.rows.isEmpty())
    }
    test("insert-to-journal crash window is closed by exact-name lookup") {
        val sink=FakeSink().apply { throwAfterInsert=true }; reject { coordinator("insert-window",sink).publish(pair) }; check(sink.rows.isEmpty())
    }
    test("publication cancellation rolls back rows") {
        val sink=FakeSink(); val p=coordinator("cancel",sink)
        reject { p.publish(pair) { if(sink.inserts>0)throw CancellationException() } }; check(sink.rows.isEmpty())
    }
    test("failed rollback retains journal then recovery retries") {
        val sink=FakeSink().apply { failCopyAt=2;failDelete=true }; val p=coordinator("retry-cleanup",sink)
        reject { p.publish(pair) }; check(sink.rows.isNotEmpty())
        sink.failDelete=false
        val recovery=p.recoverInterrupted(); check(recovery.rolledBack==listOf(pair.id) && recovery.failures.isEmpty() && sink.rows.isEmpty())
    }
    test("recovery never deletes committed output") {
        val sink=FakeSink();val p=coordinator("retain",sink);p.publish(pair)
        val recovery=p.recoverInterrupted();check(recovery.retainedCommitted==1 && sink.rows.size==2)
    }
    test("recovery cleans a journaled pre-Uri crash") {
        val sink=FakeSink(); val directory=File(out,"journal-crash"); directory.mkdirs()
        for(part in pair.parts)sink.insertPending(part.file.name,part.mimeType,"DCIM/Filmroom/")
        val props=Properties().apply {
            setProperty("id",pair.id);setProperty("state","PLANNED");setProperty("path","DCIM/Filmroom/");setProperty("count","2")
            for((i,p) in pair.parts.withIndex()) { setProperty("name.$i",p.file.name);setProperty("mime.$i",p.mimeType);setProperty("hash.$i",p.sha256);setProperty("row.$i","") }
        }
        File(directory,"${pair.id}.properties").outputStream().use { props.store(it,"") }
        val recovery=PublicationCoordinator(directory,sink).recoverInterrupted();check(recovery.rolledBack.size==1 && sink.rows.isEmpty())
    }
    test("publication refuses to silently duplicate a deleted committed row") {
        val sink=FakeSink();val p=coordinator("deleted",sink);p.publish(pair);sink.rows.clear()
        reject { p.publish(pair) };check(sink.inserts==2)
    }
    test("publication verifies artifact hash before inserting anything") {
        val sink=FakeSink(); val bad=pair.copy(image=pair.image.copy(sha256="bad"))
        reject { coordinator("hash",sink).publish(bad) }; check(sink.inserts==0)
    }
    test("random corrupt tail inputs terminate without acceptance") {
        val random=java.util.Random(1847)
        repeat(250) { val b=ByteArray(random.nextInt(1024)); random.nextBytes(b); reject { Vivo.parse(b) } }
    }
    test("random corrupt JPEG inputs terminate without acceptance") {
        val random=java.util.Random(8471)
        repeat(250) { val b=ByteArray(random.nextInt(1024)); random.nextBytes(b); reject { Jpeg.inspect(b) } }
    }
    test("standard profile supports actual nonzero cover PTS") {
        val a=LivePhotoPackager.packageTo(PackageRequest(jpg,mp4,LivePhotoProfile.GOOGLE,cover.copy(timestampUs=500_000,frameIndex=15)),File(out,"nonzero-pts"))
        check(LivePhotoVerifier.verify(a).profile==LivePhotoProfile.GOOGLE)
        // Core verifies metadata, not semantic pixel/PTS correspondence. Android creates the matching cover.
    }
    fun q(s:String)=FlatJson.encode(mapOf("s" to s)).removePrefix("{\"s\":").removeSuffix("}")
    val artifacts=assets.entries.joinToString(",\n","[\n","\n]") { (p,a) ->
        "{\"profile\":${q(p.name)},\"image\":${q(a.image.file.absolutePath)},\"companion\":${a.companionVideo?.let { q(it.file.absolutePath) } ?: "null"},\"videoOffset\":${a.videoRange.offset},\"videoLength\":${a.videoRange.length},\"jpegBytes\":${a.report.jpegBytes},\"motionExtentBytes\":${a.report.motionExtentBytes}}"
    }
    File(out,"artifacts.json").writeText(artifacts)
    val json=checks.joinToString(",\n","[\n","\n]") { (name,ok,error)->"{\"name\":${q(name)},\"passed\":$ok,\"error\":${q(error)}}" }
    File(out,"results.json").writeText(json)
    val failures=checks.count { !it.second }
    fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;")
    File(out,"junit.xml").writeText("<testsuite name=\"core-selftest\" tests=\"${checks.size}\" failures=\"$failures\">"+checks.joinToString("") { (n,ok,e)->"<testcase name=\"${esc(n)}\">"+(if(ok)"" else "<failure>${esc(e)}</failure>")+"</testcase>" }+"</testsuite>")
    println("RESULT ${checks.size-failures}/${checks.size}")
    check(failures==0) { "$failures checks failed" }
}
