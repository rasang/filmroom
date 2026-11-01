package dev.filmroom.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater

/** Fixed trusted build asset, not a general decoder. Restores original NPY bits before use. */
internal object PackedSpectralLut {
    const val ORIGINAL_NAME = "irradiance_xy_tc.npy"
    const val PACKED_NAME = "irradiance_xy_tc.frlut"
    const val ASSET = "engine-data/luts/spectral_upsampling/$PACKED_NAME"
    const val ORIGINAL_BYTES = 5_972_096
    private const val HEADER_BYTES = 128
    private const val STRIDE = 81
    private const val PREFIX_BYTES = 184
    private const val MAX_PACKED_BYTES = 3_000_000
    const val SHA256 = "52f90c724811f5fab80769f8548bf24d08a521eb09e65ab6e016893258d2a783"
    private val magic = "FRLUT001".toByteArray(Charsets.US_ASCII)
    private val header = Base64.getDecoder().decode("k05VTVBZAQB2AHsnZGVzY3InOiAnPGYyJywgJ2ZvcnRyYW5fb3JkZXInOiBGYWxzZSwgJ3NoYXBlJzogKDE5MiwgMTkyLCA4MSksIH0gICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIAo=")
    private val expectedSha = SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun reject(): Nothing = throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_236748b3f384))
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)

    fun decode(input:InputStream, checkCancelled:()->Unit={}):ByteArray {
        // Fixed cap; never trust a length supplied by the compressed stream.
        val storage=ByteArray(MAX_PACKED_BYTES+1);var used=0
        while(used<storage.size){checkCancelled();val n=input.read(storage,used,minOf(65536,storage.size-used));if(n<0)break;if(n==0)reject();used+=n}
        if(used<=PREFIX_BYTES || used>MAX_PACKED_BYTES)reject()
        if(!storage.copyOfRange(0,8).contentEquals(magic))reject()
        val fields=ByteBuffer.wrap(storage,8,16).order(ByteOrder.LITTLE_ENDIAN)
        if(fields.int!=1 || fields.int!=ORIGINAL_BYTES || fields.int!=HEADER_BYTES || fields.int!=STRIDE)reject()
        if(!storage.copyOfRange(24,152).contentEquals(header) || !storage.copyOfRange(152,184).contentEquals(expectedSha))reject()
        val shuffled=ByteArray(ORIGINAL_BYTES-HEADER_BYTES);val inflater=Inflater()
        try {
            inflater.setInput(storage,PREFIX_BYTES,used-PREFIX_BYTES);var done=0
            while(done<shuffled.size){checkCancelled();val n=inflater.inflate(shuffled,done,minOf(65536,shuffled.size-done));if(n==0)reject();done+=n}
            // Must finish exactly here, including checksum; cannot emit one extra byte.
            val extra=ByteArray(1)
            if(inflater.inflate(extra)!=0 || !inflater.finished() || inflater.remaining!=0)reject()
        }catch(e:java.util.zip.DataFormatException){reject()}finally{inflater.end()}
        val restored=ByteArray(ORIGINAL_BYTES);header.copyInto(restored);val count=shuffled.size/2;val previous=IntArray(STRIDE)
        for(i in 0 until count){if(i%32768==0)checkCancelled();val delta=(shuffled[i].toInt()and 255) or ((shuffled[count+i].toInt()and 255) shl 8);val lane=i%STRIDE;val word=(delta+previous[lane]) and 65535;previous[lane]=word;restored[HEADER_BYTES+2*i]=word.toByte();restored[HEADER_BYTES+2*i+1]=(word ushr 8).toByte()}
        checkCancelled();if(!digest(restored).contentEquals(expectedSha))reject();return restored
    }

    private fun matchesExpected(file:File,checkCancelled:()->Unit):Boolean {
        if(!file.isFile || file.length()!=ORIGINAL_BYTES.toLong())return false
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source -> val b=ByteArray(65536);while(true){checkCancelled();val n=source.read(b);if(n<0)break;md.update(b,0,n)} }
        return md.digest().contentEquals(expectedSha)
    }

    fun install(input:InputStream,destination:File,trustedRoot:File,checkCancelled:()->Unit={},replace:(File,File)->Boolean={a,b->a.renameTo(b)}):File {
        // Even when an old copy exists, validate the current asset: don't mask corrupt updates.
        val suppliedRoot=trustedRoot.absoluteFile.toPath().normalize()
        val suppliedDestination=destination.absoluteFile.toPath().normalize()
        if(!suppliedDestination.startsWith(suppliedRoot) || suppliedDestination==suppliedRoot)throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb3e34642ce2))
        val relative=suppliedRoot.relativize(suppliedDestination)
        var checked=trustedRoot.canonicalFile.toPath()
        for(index in 0 until relative.nameCount){
            checked=checked.resolve(relative.getName(index))
            if(java.nio.file.Files.isSymbolicLink(checked))throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb3e34642ce2))
            if(index<relative.nameCount-1 && java.nio.file.Files.exists(checked) && !java.nio.file.Files.isDirectory(checked))throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bb3e34642ce2))
        }
        val target=checked.toFile()
        val restored=decode(input,checkCancelled);checkCancelled()
        if(matchesExpected(target,checkCancelled))return target
        val parent=target.parentFile ?: reject();if(!parent.isDirectory && !parent.mkdirs())throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_837fdb22a021))
        val temporary=File.createTempFile("asset-", ".tmp",parent)
        try {
            FileOutputStream(temporary).use { stream -> var offset=0;while(offset<restored.size){checkCancelled();val n=minOf(65536,restored.size-offset);stream.write(restored,offset,n);offset+=n};stream.fd.sync() }
            checkCancelled();if(!matchesExpected(temporary,checkCancelled))reject();checkCancelled();if(!replace(temporary,target))throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8d11010b3a7b))
            return target
        }finally{temporary.delete()}
    }
}
