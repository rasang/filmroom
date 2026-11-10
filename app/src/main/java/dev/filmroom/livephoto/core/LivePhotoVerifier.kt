package dev.filmroom.livephoto.core

import java.io.*
import java.nio.file.Files

/** Validator for SDK-produced profiles, not an importer for every historical OEM format. */
object LivePhotoVerifier {
    @JvmStatic @JvmOverloads
    fun verify(asset: PackagedAsset, checkActive: () -> Unit = {}): VerificationReport {
        for(part in asset.parts) demand(FileDigests.sha256(part.file,checkActive) == part.sha256,ErrorCode.VERIFICATION_FAILED,"Output changed since packaging: ${part.file.name}")
        return inspect(asset.image.file,asset.companionVideo?.file,asset.profile,asset.cover,asset.bindingId,checkActive)
    }
    @JvmStatic @JvmOverloads
    fun extractVideo(asset: PackagedAsset, output: File, checkActive: () -> Unit = {}): File {
        val report=verify(asset,checkActive)
        val input=asset.companionVideo?.file ?: asset.image.file
        output.parentFile?.let { Files.createDirectories(it.toPath()) }
        Files.createFile(output.toPath())
        try {
            RandomAccessFile(input,"r").use { source ->
                source.seek(report.videoRange.offset); var remaining=report.videoRange.length
                FileOutputStream(output).use { target ->
                    val b=ByteArray(SdkLimits.BUFFER_BYTES)
                    while(remaining>0) { checkActive(); val n=source.read(b,0,minOf(b.size.toLong(),remaining).toInt()); if(n<0)throw EOFException(); target.write(b,0,n); remaining-=n }
                    target.fd.sync()
                }
            }
            return output
        } catch(t:Throwable) { output.delete(); throw t }
    }
    internal fun inspect(image:File,video:File?,profile:LivePhotoProfile,cover:CoverInfo,id:String?,checkActive:()->Unit={}):VerificationReport {
        demand(image.isFile && image.length() <= SdkLimits.MAX_JPEG_BYTES + SdkLimits.MAX_VIDEO_BYTES + 65536,ErrorCode.RESOURCE_LIMIT,"Output size invalid")
        val prefix=RandomAccessFile(image,"r").use { it.readAt(0,minOf(it.length(),SdkLimits.MAX_JPEG_BYTES).toInt()) }
        val jpeg=Jpeg.inspect(prefix); val imageBytes=prefix.copyOfRange(0,jpeg.end)
        val packets=Jpeg.xmpPackets(imageBytes,jpeg)
        val warnings=mutableListOf("目标相册未验收；结构验证不能替代真机播放。")
        var google:Boolean?=null
        val range:VideoRange; val extent:Long
        checkExif(imageBytes,jpeg,profile)
        if(profile.paired) {
            demand(video != null && video.isFile && id != null,ErrorCode.INVALID_METADATA,"Missing companion video or ID")
            demand(packets.isEmpty(),ErrorCode.INVALID_METADATA,"Unexpected XMP in pair profile")
            val imageTail=RandomAccessFile(image,"r").use { f ->
                val n=f.length()-jpeg.end; demand(n in 1..16384,ErrorCode.INVALID_METADATA,"Invalid image binding size"); f.readAt(jpeg.end.toLong(),n.toInt())
            }
            val img=Vivo.parse(imageTail)
            val boxes=Mp4.validate(video!!)
            val last=boxes.last()
            demand(last.type=="uuid" && last.header==8 && last.size<=16408,ErrorCode.INVALID_METADATA,"Missing terminal vivo UUID")
            val vid=RandomAccessFile(video,"r").use { f ->
                demand(f.readAt(last.start+8,16).contentEquals(Vivo.uuidType),ErrorCode.INVALID_METADATA,"Wrong UUID user type")
                Vivo.parse(f.readAt(last.start+24,(last.size-24).toInt()))
            }
            demand(img.id==id && vid.id==id,ErrorCode.INVALID_METADATA,"Cross-file ID mismatch")
            // Schema validation uses typed values; duplicated JSON keys fail in the parser.
            val expectedImageVersion=when(profile) { LivePhotoProfile.VIVO_PAIR_2014 -> 2014L; LivePhotoProfile.VIVO_PAIR_2104 -> 2104L; else -> 2200L }
            val expectedVideoVersion=when(profile) { LivePhotoProfile.VIVO_PAIR_2014 -> 2014L; LivePhotoProfile.VIVO_PAIR_2104 -> 2104L; else -> 2016L }
            demand(img.fields["version"]==expectedImageVersion && vid.fields["version"]==expectedVideoVersion,ErrorCode.INVALID_METADATA,"Wrong vivo versions")
            if(profile==LivePhotoProfile.VIVO_PAIR_2014) {
                val keys = setOf(Vivo.ID_KEY, "version")
                demand(img.fields.keys==keys && vid.fields.keys==keys, ErrorCode.INVALID_METADATA, "Unexpected 2014 cover or source fields")
                warnings += "2014 双文件不写封面时间；非首帧封面与播放衔接需真机验收。"
            } else if(profile==LivePhotoProfile.VIVO_PAIR_2104) demand(vid.fields["com.android.camera.imageTime"]==cover.frameIndex.toLong(),ErrorCode.INVALID_METADATA,"Wrong vivo frame index")
            else {
                demand(cover.frameIndex==0 && vid.fields["com.vivo.gallery.livePhoto.newCoverTime"]==0L,ErrorCode.INVALID_METADATA,"Legacy vivo supports first cover only")
                demand(img.fields["com.vivo.gallery.livephoto.source"]==4L && img.fields["com.vivo.gallery.livePhoto.rotationOffset"]==0L && img.fields["com.vivo.gallery.livePhoto.rotationCheck"]==3L,ErrorCode.INVALID_METADATA,"Missing legacy image flags")
            }
            range=VideoRange(0,last.start); extent=range.length
            warnings += "双文件需要原厂相册实际关联；相同文件名并非配对成功证据。"
        } else if(profile==LivePhotoProfile.HONOR_LEGACY) {
            demand(video==null && packets.isEmpty(),ErrorCode.INVALID_METADATA,"Unexpected companion or XMP")
            val tail=RandomAccessFile(image,"r").use { it.readAt(it.length()-60,60) }
            fun value(p:Int,n:Int)=tail.copyOfRange(p,p+n).toString(Charsets.US_ASCII).trimEnd(' ')
            demand(value(0,20)=="v2_f00" && value(20,20)=="0:1000",ErrorCode.INVALID_METADATA,"Honor v2 defaults changed")
            val live=value(40,20)
            demand(live.startsWith("LIVE_"),ErrorCode.INVALID_METADATA,"Missing LIVE_ marker")
            val l=live.substring(5).toLongOrNull() ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"Invalid LIVE_ length")
            demand(l>20 && l<=image.length()-40,ErrorCode.INVALID_METADATA,"LIVE_ range invalid")
            range=VideoRange(image.length()-l-40,l-20); extent=range.length
            demand(range.offset==jpeg.end.toLong(),ErrorCode.INVALID_METADATA,"LIVE_ does not start after JPEG")
            warnings += "荣耀尾标按已核实的 Mimo v2 默认值写入（v2_f00、0:1000）；不是官方 MediaData Kit，尚未经目标相册实机验证。"
        } else {
            demand(video==null && packets.size==1,ErrorCode.INVALID_METADATA,"Expected one XMP packet and no companion")
            val x=Xmp.inspect(packets.single())
            demand(x.pts==cover.timestampUs,ErrorCode.INVALID_METADATA,"XMP cover PTS mismatch")
            demand(x.extent <= image.length(),ErrorCode.INVALID_METADATA,"XMP extent outside file")
            val start=image.length()-x.extent
            demand(start==jpeg.end.toLong()+x.padding,ErrorCode.INVALID_METADATA,"XMP directory disagrees with JPEG boundary")
            if(profile==LivePhotoProfile.SAMSUNG_SEF_107 || profile==LivePhotoProfile.SAMSUNG_SEF_106) {
                val sef=Samsung.inspect(image,profile==LivePhotoProfile.SAMSUNG_SEF_107)
                demand(sef.firstRecord==jpeg.end.toLong() && sef.range.offset==start && sef.extent==x.extent,ErrorCode.INVALID_METADATA,"XMP and SEF disagree")
                range=sef.range; extent=sef.extent; google=false
                warnings += "三星 XMP 区域含 SEF 尾部；裸 MP4 与目录长度不同，不标为严格 Google MP4 容器。"
            } else {
                demand(x.padding==0,ErrorCode.INVALID_METADATA,"Unexpected padding")
                range=VideoRange(start, if(profile == LivePhotoProfile.VIVO_SINGLE) Vivo.inspectSingleExtension(image, VideoRange(start,x.extent)) else x.extent); extent=x.extent
                google=!x.secondaryPadding
                if(x.secondaryPadding) warnings += "厂商候选在非主项写 Padding=0；不标为严格 Google 规范通过。"
            }
            when(profile) {
                LivePhotoProfile.GOOGLE -> demand(!x.secondaryPadding,ErrorCode.INVALID_METADATA,"Google profile cannot put Padding on video")
                LivePhotoProfile.XIAOMI_COMPAT -> {
                    demand(x.fields[Xmp.G to "MicroVideo"]=="1" && x.fields[Xmp.G to "MicroVideoVersion"]=="1" && x.fields[Xmp.G to "MicroVideoOffset"]==extent.toString() && x.fields[Xmp.G to "MicroVideoPresentationTimestampUs"]==cover.timestampUs.toString(),ErrorCode.INVALID_METADATA,"Legacy/current Xiaomi pointers or PTS conflict")
                    warnings += "使用自写标准＋旧 MicroVideo 兼容字段，未集成小米授权 SDK。"
                }
                LivePhotoProfile.OPLUS -> {
                    demand(x.fields[Xmp.O to "MotionPhotoOwner"]?.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))==true && x.fields[Xmp.O to "OLivePhotoVersion"]=="1" && x.fields[Xmp.O to "VideoLength"]=="0" && x.fields[Xmp.O to "MotionPhotoFeatureFlag"]==null && x.fields[Xmp.O to "MotionPhotoPrimaryPresentationTimestampUs"]==cover.timestampUs.toString(),ErrorCode.INVALID_METADATA,"Oplus fields conflict")
                    checkMpf(imageBytes,jpeg)
                }
                LivePhotoProfile.VIVO_SINGLE -> demand(x.fields[Xmp.V to "VMotionPhotoVersion"]=="1",ErrorCode.INVALID_METADATA,"Missing vivo version")
                else -> Unit
            }
        }
        checkActive()
        val source=video ?: image
        Mp4.validate(source,range)
        return VerificationReport(profile,jpeg.end.toLong(),range,extent,FileDigests.range(source,range,checkActive),warnings.toList(),google)
    }
    private fun checkMpf(b:ByteArray,j:Jpeg.Layout) {
        val segments=j.segments.filter { it.marker==0xe2 && b.startsAt(ascii("MPF\u0000"),it.payload) }
        demand(segments.size==1,ErrorCode.INVALID_METADATA,"Expected exactly one MPF")
        val s=segments.single(); val t=s.payload+4
        demand(b.startsAt(ascii("MM"),t) && u16(b,t+2)==42,ErrorCode.INVALID_METADATA,"Invalid MPF TIFF")
        val ifd=t+u32(b,t+4).toInt()
        demand(ifd>=t && ifd<=s.end-6,ErrorCode.INVALID_METADATA,"Bad MPF IFD")
        val n=u16(b,ifd); demand(n==3 && ifd+2+12*n+4<=s.end,ErrorCode.INVALID_METADATA,"Unexpected MPF entries")
        val entries=(0 until n).associate { val p=ifd+2+12*it; u16(b,p) to p }
        val ver=entries[0xb000] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No MPF version")
        val count=entries[0xb001] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No MPF count")
        val entry=entries[0xb002] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No MPEntry")
        demand(b.startsAt(ascii("0100"),ver+8) && u32(b,count+8)==1L && u32(b,entry+4)==16L,ErrorCode.INVALID_METADATA,"Invalid MPF fields")
        val e=t+u32(b,entry+8).toInt()
        demand(e>=t && e<=s.end-16 && u32(b,e)==0x30000L && u32(b,e+4)==j.end.toLong() && u32(b,e+8)==0L,ErrorCode.INVALID_METADATA,"MPEntry does not describe final JPEG")
    }
    private fun checkExif(b:ByteArray,j:Jpeg.Layout,profile:LivePhotoProfile) {
        val list=j.segments.filter { it.marker==0xe1 && b.startsAt(ascii("Exif\u0000\u0000"),it.payload) }
        demand(list.size==1,ErrorCode.INVALID_METADATA,"Expected a fresh EXIF block")
        val s=list.single(); val t=s.payload+6
        demand(b.startsAt(ascii("II*\u0000"),t),ErrorCode.INVALID_METADATA,"Invalid generated EXIF")
        fun little16(p:Int):Int { demand(p>=0 && p<=b.size-2,ErrorCode.INVALID_METADATA,"Short EXIF integer"); return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) }
        fun ifd(offset:Long):Map<Int,Int> {
            demand(offset in 8..(s.end-t-2).toLong(),ErrorCode.INVALID_METADATA,"Bad EXIF IFD offset")
            val p=t+offset.toInt(); val n=little16(p)
            demand(n<=128 && p+2+12*n+4<=s.end,ErrorCode.INVALID_METADATA,"EXIF IFD out of range")
            return (0 until n).associate { val e=p+2+12*it; little16(e) to e }
        }
        val primary=ifd(u32(b,t+4,true)); val orientation=primary[0x112]
        demand(orientation!=null && little16(orientation+8)==1,ErrorCode.INVALID_METADATA,"Cover pixels must already be upright")
        val expected=when(profile) {
            LivePhotoProfile.OPLUS -> ascii("ASCII\u0000\u0000\u0000oplus_8388608")
            LivePhotoProfile.VIVO_SINGLE -> ascii("multi-frame: 1;\nispap:1;\nmodule: photo;\u0000")
            else -> null
        }
        if(profile==LivePhotoProfile.XIAOMI_COMPAT) {
            val pointer=primary[0x8769] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No Xiaomi EXIF sub-IFD")
            val flag=ifd(u32(b,pointer+8,true))[0x9a01] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"Missing Xiaomi livePhoto flag")
            demand(little16(flag+2)==1 && u32(b,flag+4,true)==1L && b[flag+8]==1.toByte(),
                ErrorCode.INVALID_METADATA,"Invalid Xiaomi livePhoto flag")
        }
        if(expected!=null) {
            val pointer=primary[0x8769] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No EXIF sub-IFD")
            val user=ifd(u32(b,pointer+8,true))[0x9286] ?: throw LivePhotoException(ErrorCode.INVALID_METADATA,"No UserComment")
            val count=u32(b,user+4,true); val offset=u32(b,user+8,true)
            demand(count==expected.size.toLong() && offset>=0 && offset+count<=s.end-t && b.startsAt(expected,t+offset.toInt()),ErrorCode.INVALID_METADATA,"Wrong vendor UserComment")
        }
    }
}

