package dev.filmroom.livephoto.core

import java.io.*
import java.nio.file.Files
import java.util.UUID

/** Synchronous, platform-free packager. Call off the Android main thread. Inputs are never modified. */
object LivePhotoPackager {
    @JvmStatic @JvmOverloads
    fun packageTo(request: PackageRequest, outputDirectory: File, checkActive: () -> Unit = {}): PackagedAsset {
        checkActive()
        demand(request.profile == LivePhotoProfile.GOOGLE || request.allowExperimentalProfiles, ErrorCode.UNSUPPORTED_PROFILE, "OEM profiles are experimental: explicitly opt in after choosing the receiving gallery")
        demand(!outputDirectory.exists(), ErrorCode.OUTPUT_EXISTS, "Output job directory must not already exist")
        demand(request.videoMp4.length() in 16..SdkLimits.MAX_VIDEO_BYTES, ErrorCode.RESOURCE_LIMIT, "Video is empty or larger than 256 MiB")
        if(request.profile == LivePhotoProfile.VIVO_PAIR_LEGACY) demand(request.cover.frameIndex == 0, ErrorCode.UNSUPPORTED_PROFILE, "Legacy vivo profile only supports the first frame; newCoverTime units have not been verified")
        val clean = Jpeg.clean(readBounded(request.coverJpeg,SdkLimits.MAX_JPEG_BYTES))
        Mp4.validate(request.videoMp4)
        val sourceHash = FileDigests.sha256(request.videoMp4,checkActive)
        val v = request.videoMp4.length(); val profile=request.profile
        val id = if(profile.paired) request.bindingId ?: randomBindingId() else null
        if(id != null) demand(id.matches(Regex("[0-9a-f]{28}")),ErrorCode.INVALID_REQUEST,"Invalid vivo ID")
        val samsung = when(profile) {
            LivePhotoProfile.SAMSUNG_SEF_107 -> Samsung.plan(v,true)
            LivePhotoProfile.SAMSUNG_SEF_106 -> Samsung.plan(v,false)
            else -> null
        }
        val prefix = samsung?.prefix ?: byteArrayOf()
        val suffix = when {
            samsung != null -> samsung.suffix
            profile == LivePhotoProfile.HONOR_LEGACY -> Honor.tail(v,request.cover)
            else -> byteArrayOf()
        }
        val noXmp = profile.paired || profile == LivePhotoProfile.HONOR_LEGACY
        val extent = v + suffix.size
        val xmp = if(noXmp) byteArrayOf() else Jpeg.xmp(Xmp.build(profile,v,extent,prefix.size,request.cover.timestampUs))
        val comment = when(profile) {
            LivePhotoProfile.OPLUS -> ascii("ASCII\u0000\u0000\u0000Oplus_8388608")
            LivePhotoProfile.VIVO_SINGLE -> ascii("multi-frame: 1;\nispap:1;\nmodule: photo;\u0000")
            else -> null
        }
        val base = Jpeg.insert(clean,Jpeg.exif(comment) + xmp)
        val jpeg = if(profile == LivePhotoProfile.OPLUS) {
            val mpfSize = Jpeg.mpf(1).size
            Jpeg.insert(base,Jpeg.mpf(base.size.toLong() + mpfSize))
        } else base
        demand(jpeg.size <= SdkLimits.MAX_JPEG_BYTES,ErrorCode.RESOURCE_LIMIT,"Final cover exceeds JPEG limit")
        val token = UUID.randomUUID().toString(); val stem="Filmroom_${token.replace("-", "")}_MP"
        val imageFile=File(outputDirectory,"$stem.jpg")
        val videoFile=if(profile.paired) File(outputDirectory,"$stem.mp4") else null
        outputDirectory.parentFile?.let { Files.createDirectories(it.toPath()) }
        // Atomic creation means failure cleanup cannot remove a pre-existing output directory.
        Files.createDirectory(outputDirectory.toPath())
        try {
            checkActive()
            FileOutputStream(imageFile).use { output ->
                output.write(jpeg)
                if(profile.paired) {
                    output.write(Vivo.tail(Vivo.metadata(id!!,profile,false,request.cover.frameIndex)))
                } else {
                    output.write(prefix)
                    FileInputStream(request.videoMp4).use { streamCopy(it,output,v,checkActive) }
                    output.write(suffix)
                }
                output.fd.sync()
            }
            if(videoFile != null) {
                FileOutputStream(videoFile).use { output ->
                    FileInputStream(request.videoMp4).use { streamCopy(it,output,v,checkActive) }
                    output.write(Vivo.uuid(Vivo.tail(Vivo.metadata(id!!,profile,true,request.cover.frameIndex))))
                    output.fd.sync()
                }
            }
            val report=LivePhotoVerifier.inspect(imageFile,videoFile,profile,request.cover,id,checkActive)
            demand(report.videoSha256 == sourceHash,ErrorCode.VERIFICATION_FAILED,"Embedded video differs from input")
            checkActive()
            return PackagedAsset(token,profile,
                AssetPart(imageFile,"image/jpeg",FileDigests.sha256(imageFile,checkActive)),
                videoFile?.let { AssetPart(it,"video/mp4",FileDigests.sha256(it,checkActive)) },
                request.cover,report.videoRange,id,report)
        } catch(t: Throwable) {
            // The SDK created this directory, so all its contents belong to this failed operation.
            outputDirectory.deleteRecursively()
            throw t
        }
    }
}
