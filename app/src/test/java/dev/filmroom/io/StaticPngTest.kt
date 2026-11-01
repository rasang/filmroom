package dev.filmroom.io
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Test
import org.junit.Assert.*
class StaticPngTest {
 @Test fun rejectsAnimationBeforeImageDataWithoutLoadingLargeChunks(){
  val f=kotlin.io.path.createTempFile("apng-test").toFile()
  try{DataOutputStream(f.outputStream()).use{it.write(ByteArray(8));it.writeInt(8);it.writeBytes("acTL");it.write(ByteArray(12))}
   assertThrows(PhotoException::class.java){StaticPng.requireStill(f,EmptyCoroutineContext)}
  }finally{f.delete()}
 }
 @Test fun acceptsStillHeaderAndRejectsOutOfBoundsChunk(){
  val f=kotlin.io.path.createTempFile("png-header-test").toFile()
  try{DataOutputStream(f.outputStream()).use{it.write(ByteArray(8));it.writeInt(0);it.writeBytes("IDAT");it.writeInt(0)}
   StaticPng.requireStill(f,EmptyCoroutineContext)
   DataOutputStream(f.outputStream()).use{it.write(ByteArray(8));it.writeInt(Int.MAX_VALUE);it.writeBytes("sRGB");it.writeInt(0)}
   assertThrows(PhotoException::class.java){StaticPng.requireStill(f,EmptyCoroutineContext)}
  }finally{f.delete()}
 }
}
