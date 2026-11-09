package dev.filmroom.livephoto.importer

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class MotionPhotoExtractorRestoreTest {
    private fun box(type:String,body:ByteArray)=ByteBuffer.allocate(8+body.size).putInt(8+body.size).put(type.toByteArray()).put(body).array()
    private fun movie():ByteArray {
        val handler=box("hdlr",ByteArray(8)+"vide".toByteArray())
        return box("ftyp","isom0000".toByteArray())+box("moov",box("trak",box("mdia",handler)))+box("mdat",byteArrayOf(1,2,3))
    }
    private fun jpeg(flag:Boolean):ByteArray {
        fun segment(marker:Int,p:ByteArray)=byteArrayOf(-1,marker.toByte(),((p.size+2) shr 8).toByte(),(p.size+2).toByte())+p
        return byteArrayOf(-1,-40)+segment(225,(if(flag) "GCamera:MotionPhoto=\"1\"" else "plain").toByteArray())+
            segment(192,byteArrayOf(8,0,2,0,2,1,1,17,0))+segment(218,byteArrayOf(1,1,0,0,63,0))+byteArrayOf(1,-1,-39)
    }
    private fun temp(bytes:ByteArray)=File.createTempFile("motion-restore-",".jpg").apply{writeBytes(bytes);deleteOnExit()}
    @Test fun extractsOnlyValidatedMovieAfterPrimaryJpeg() {
        val cover=jpeg(true);val video=movie();val file=temp(cover+video)
        val source=requireNotNull(MotionPhotoExtractor.inspect(file))
        assertEquals(cover.size.toLong(),source.offset);assertEquals(video.size.toLong(),source.length)
        val out=File(file.parentFile,file.name+".mp4")
        try{MotionPhotoExtractor.extract(source,out);assertArrayEquals(video,out.readBytes())}finally{out.delete();file.delete()}
    }
    @Test fun ordinaryJpegAndUnflaggedAppendedVideoAreRejected() {
        for (bytes in listOf(jpeg(true),jpeg(false)+movie(),byteArrayOf(1,2,3,4))) {val f=temp(bytes);try{assertNull(MotionPhotoExtractor.inspect(f))}finally{f.delete()}}
    }
    @Test fun ambiguousMultipleMoviesAndTruncatedMovieAreRejected() {
        val v=movie();for(bytes in listOf(jpeg(true)+v+v,jpeg(true)+v.copyOf(v.size-1))){val f=temp(bytes);try{assertNull(MotionPhotoExtractor.inspect(f))}finally{f.delete()}}
    }
    @Test fun cancellationDeletesPartialOutputAndExistingOutputIsPreserved() {
        val f=temp(movie());val source=MotionPhotoSource(f,0,f.length(),"test");val out=File(f.parentFile,f.name+".mp4")
        try {
            assertThrows(IllegalStateException::class.java){MotionPhotoExtractor.extract(source,out){throw IllegalStateException("cancel")}}
            assertFalse(out.exists());out.writeText("keep")
            assertThrows(IllegalStateException::class.java){MotionPhotoExtractor.extract(source,out)}
            assertEquals("keep",out.readText())
        } finally {f.delete();out.delete()}
    }
    @Test fun preventsSourceOverwriteAndInvalidRanges() {
        val f=temp(movie())
        try {
            assertThrows(IllegalArgumentException::class.java){MotionPhotoExtractor.extract(MotionPhotoSource(f,0,f.length(),"test"),f)}
            assertThrows(IllegalArgumentException::class.java){MotionPhotoExtractor.extract(MotionPhotoSource(f,-1,f.length(),"test"),File(f.parentFile,f.name+".out"))}
        }finally{f.delete()}
    }
}
