package dev.filmroom

import dev.filmroom.lifecycle.PrivateWorkRecovery
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrivateWorkRecoveryTest {
 @Test fun recoversOnlyOwnedWorkAndPreservesCurrentSourceAndSharedDiagnostics(){
  val root=Files.createTempDirectory("recovery-test").toFile()
  try {
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val id="12345678-abcd-4321-abcd-123456789abc"
   val owned=listOf("original-input-$id.rgb","film-result-$id.rgb","film-input-1234.rgb","film-input-1234.rgb.output","original-spool-42.part").map{File(cache,it).apply{writeText("owned")}}
   val retained=File(cache,"photo-sources/source-123.image").apply{parentFile.mkdirs();writeText("photo")}
   val orphan=File(retained.parentFile,"source-456.image").apply{writeText("orphan")}
   val share=File(cache,"diagnostic-shares/diagnostics.zip").apply{parentFile.mkdirs();writeText("share")}
   val unrelated=File(cache,"not-our-file.rgb").apply{writeText("keep")}
   val report=PrivateWorkRecovery.recover(cache,files,retained.name)
   assertEquals(6,report.removed);assertEquals(0,report.failed)
   assertTrue(owned.all{!it.exists()});assertFalse(orphan.exists());assertTrue(retained.exists());assertTrue(share.exists());assertTrue(unrelated.exists())
   assertEquals(0,PrivateWorkRecovery.recover(cache,files,retained.name).removed)
  } finally{root.deleteRecursively()}
 }
 @Test fun invalidRetainedKeyCannotPointOutsideSourceDirectory(){
  val root=Files.createTempDirectory("recovery-path").toFile()
  try{val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()};val external=File(root,"outside.image").apply{writeText("safe")};PrivateWorkRecovery.recover(cache,files,"../../outside.image");assertTrue(external.exists())}finally{root.deleteRecursively()}
 }
 @Test fun v5ScratchRecoveryDoesNotFollowLinksOrDeleteSimilarUnownedNames(){
  val root=Files.createTempDirectory("recovery-v5").toFile()
  try {
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val outside=File(root,"outside").apply{mkdirs()};val sentinel=File(outside,"density.bin").apply{writeText("keep")}
   val scratch=File(cache,"film-native-v5-1-1793376000000000000-42").apply{mkdirs()}
   File(scratch,"density-pass.bin").writeText("partial")
   Files.createSymbolicLink(File(scratch,"external-link").toPath(),outside.toPath())
   val rootLink=File(cache,"film-native-v5-2-1793376000000000000-43")
   Files.createSymbolicLink(rootLink.toPath(),outside.toPath())
   val similar=File(cache,"film-native-v5-not-a-job").apply{mkdirs()};File(similar,"keep").writeText("unrelated")
   val r=PrivateWorkRecovery.recover(cache,files,null)
   assertEquals(0,r.failed);assertFalse(scratch.exists());assertFalse(Files.exists(rootLink.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
   assertTrue(sentinel.exists());assertTrue(similar.exists())
  }finally{root.deleteRecursively()}
 }
 @Test fun recoveryRejectsSymlinkedSourceAndEngineDataRoots(){
  val root=Files.createTempDirectory("recovery-roots").toFile()
  try {
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val outside=File(root,"outside").apply{mkdirs()}
   val source=File(outside,"source-123.image").apply{writeText("keep")};val asset=File(outside,"asset-123.tmp").apply{writeText("keep")}
   Files.createSymbolicLink(File(cache,"photo-sources").toPath(),outside.toPath())
   Files.createSymbolicLink(File(files,"engine-data").toPath(),outside.toPath())
   PrivateWorkRecovery.recover(cache,files,null)
   assertTrue(source.exists());assertTrue(asset.exists())
  }finally{root.deleteRecursively()}
 }
 @Test fun retainedRawIsPreservedButOrphanCodecFilesAreRecovered(){
  val root=Files.createTempDirectory("recovery-raw").toFile()
  try{
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val sources=File(cache,"photo-sources").apply{mkdirs()}
   val id="12345678-abcd-4321-abcd-123456789abc"
   val retained=File(sources,"raw-source-$id.rgb").apply{writeText("keep")}
   val orphan=File(sources,"raw-source-12345678-abcd-4321-abcd-123456789abd.rgb").apply{writeText("delete")}
   val encoded=File(cache,"codec-output-$id.tif").apply{writeText("delete")}
   val report=PrivateWorkRecovery.recover(cache,files,"source-123.image",retained.name)
   assertEquals(2,report.removed);assertTrue(retained.exists());assertFalse(orphan.exists());assertFalse(encoded.exists())
  }finally{root.deleteRecursively()}
 }

 @Test fun cacheEnumerationFailureIsNotReportedAsSuccessfulRecovery(){
  val root=Files.createTempDirectory("recovery-enumeration").toFile()
  try {
   val cache=object:File(root,"cache") { override fun listFiles():Array<File>?=null }
   cache.mkdirs()
   val files=File(root,"files").apply{mkdirs()}
   val report=PrivateWorkRecovery.recover(cache,files,null)
   assertEquals(0,report.removed);assertEquals(1,report.failed)
  }finally{root.deleteRecursively()}
 }

 @Test fun retainedHeicLinearSourceSurvivesAndOrphanIsRecovered(){
  val root=Files.createTempDirectory("recovery-heic").toFile()
  try{
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val sources=File(cache,"photo-sources").apply{mkdirs()}
   val retained=File(sources,"heic-source-12345678-abcd-4321-abcd-123456789abc.rgb").apply{writeText("keep")}
   val orphan=File(sources,"heic-source-12345678-abcd-4321-abcd-123456789abd.rgb").apply{writeText("partial")}
   val report=PrivateWorkRecovery.recover(cache,files,"source-123.image",retained.name)
   assertEquals(1,report.removed);assertTrue(retained.exists());assertFalse(orphan.exists())
  }finally{root.deleteRecursively()}
 }

 @Test fun orphanHeicPrimaryViewIsRecoveredWithoutTouchingUnrelatedFiles(){
  val root=Files.createTempDirectory("recovery-heic-primary").toFile()
  try {
   val cache=File(root,"cache").apply{mkdirs()};val files=File(root,"files").apply{mkdirs()}
   val sources=File(cache,"photo-sources").apply{mkdirs()}
   val orphan=File(sources,"heic-primary-12345678.heic").apply{writeText("private view")}
   val retained=File(sources,"source-123.image").apply{writeText("original")}
   val unrelated=File(sources,"holiday.heic").apply{writeText("keep")}
   val similar=File(sources,"heic-primary-not-owned.heic").apply{writeText("keep")}
   val report=PrivateWorkRecovery.recover(cache,files,retained.name)
   assertEquals(1,report.removed);assertEquals(0,report.failed)
   assertFalse(orphan.exists());assertTrue(retained.exists());assertTrue(unrelated.exists());assertTrue(similar.exists())
  }finally{root.deleteRecursively()}
 }

}
