#!/usr/bin/env python3
"""Apply the two reviewed fixes only to the verified finishing branch tree."""
from pathlib import Path
import hashlib
import subprocess

EXPECTED = {
    'app/src/main/java/dev/filmroom/lifecycle/PrivateWorkRecovery.kt': 'ec67476afa5263faccd667100df91836edc4b321',
    'app/src/main/java/dev/filmroom/export/ExportQueue.kt': '31541990b82c9fda26e5670c6a7a1012ce04e80c',
    'app/src/main/java/dev/filmroom/engine/ExportPlanning.kt': '88e901f82a4a28c3bd6bfff190b0a897f1f816fb',
    'app/src/main/java/dev/filmroom/engine/FilmEngine.kt': '413ef1485c17664cf29062643cc056074b7b892c',
    'app/src/main/java/dev/filmroom/export/ScanPreviewStore.kt': 'd5d07cbb67a25592278ac9f3eca332ca180a1fd3',
    'native/crates/film-engine/src/mobile.rs': 'ade37d4c628096d33f38e7d97c7f5d5c5090f838',
    'native/crates/film-engine/src/scan_preview.rs': '16b3a6a00c51b8a1c224386ebc7e1decec235a48',
    '.github/workflows/finishing-performance.yml': '0d87120423502625de0eb5be83873abd7c32d0d2',
    'app/src/test/java/dev/filmroom/export/ScanPreviewStoreHostTest.kt': '0d558753f679c6672bc4c23030c4611d3c20538d',
}
texts = {}
for name, sha in EXPECTED.items():
    data = Path(name).read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    assert actual == sha, (name, actual, sha)
    texts[name] = data.decode()

def replace(name, before, after, count=1):
    assert texts[name].count(before) == count, (name, before, texts[name].count(before))
    texts[name] = texts[name].replace(before, after)

def add(name, content):
    assert not Path(name).exists(), name
    texts[name] = content

p = 'app/src/main/java/dev/filmroom/lifecycle/PrivateWorkRecovery.kt'
replace(p, r'Regex("film-result-$uuid\\.rgb"),', r'Regex("film-result-$uuid\\.rgb(?:\\.finishing\\.part)?"),')
replace(p, r'Regex("film-input-[A-Za-z0-9]+\\.rgb(?:\\.output)?"),', r'Regex("film-input-[A-Za-z0-9]+\\.rgb(?:\\.output)?(?:\\.finishing\\.part)?"),')
replace(p, '    private val nativeScratchPattern=', '''    private val finishingTemporaryPattern=Regex("film-result-$uuid\\\\.rgb\\\\.finishing\\\\.part")
    private val disposableResultPattern=Regex("film-result-$uuid\\\\.rgb(?:\\\\.part|\\\\.finishing\\\\.part)?")
    /** Retry may discard only job-owned results, never source or checkpoint files. */
    internal fun isDisposableExportResult(name:String)=disposableResultPattern.matches(name)
    private val nativeScratchPattern=''')
replace(p, '        val sources=File(cacheDir,"photo-sources")', '''        // No workers exist yet. A killed finishing pass cannot run its Rust drop
        // guard. Inspect only direct files in UUID-owned jobs; preserve resumable
        // input/checkpoints and never traverse a job directory symlink.
        val jobs=File(filesDir,"export-jobs")
        if(Files.isDirectory(jobs.toPath(),NOFOLLOW_LINKS)) {
            val entries=jobs.listFiles()
            if(entries==null)failed++
            entries?.filter { it.name.matches(Regex(uuid)) && Files.isDirectory(it.toPath(),NOFOLLOW_LINKS) }?.forEach { job ->
                val files=job.listFiles()
                if(files==null)failed++
                files?.filter { finishingTemporaryPattern.matches(it.name) && Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS) }
                    ?.forEach { deleteKnown(it,job) }
            }
        }
        val sources=File(cacheDir,"photo-sources")''')

p = 'app/src/main/java/dev/filmroom/engine/ExportPlanning.kt'
replace(p, '    val scanPreview:java.io.File?=null) {', '''    val scanPreview:java.io.File?=null,
    /** True only for a committed post-finishing source, not a polled scan. */
    val scanPreviewFinal:Boolean=false) {''')
replace(p, 'internal fun scanPreviewForOutput(output:java.io.File,workspace:java.io.File?):java.io.File? {', 'internal fun scanPreviewForOutput(output:java.io.File,workspace:java.io.File?,finished:Boolean=false):java.io.File? {')
replace(p, '    return java.io.File(workspace,"scan-source-${match.groupValues[1]}.png")', '''    val suffix=if(finished)"-finished" else ""
    return java.io.File(workspace,"scan-source-${match.groupValues[1]}$suffix.png")''')

p = 'app/src/main/java/dev/filmroom/engine/FilmEngine.kt'
replace(p, '''        // A short stage or checkpoint restore can finish between400ms polls. No new render.
        if(onProgress!=null && JSONObject(result).optBoolean("ok") && scanPreview!=null && scanPreview.isFile && scanPreview.length()>0){
            val ready=(latest ?: EngineProgress(1,1,dev.filmroom.diagnostics.NativePhase.PRINTING_SCANNING,1,1)).copy(display=ExportDisplayProgress(6,1,1),scanPreview=scanPreview)''', '''        // Finishing commits to a distinct source name. A missing/failed optional
        // final thumbnail must never relabel the earlier ungraded scan as final.
        val finalPreview=if(parameters.hasFinishing)scanPreviewForOutput(output,workspace,finished=true)else scanPreview
        // A short stage or checkpoint restore can finish between400ms polls. No new render.
        if(onProgress!=null && JSONObject(result).optBoolean("ok") && finalPreview!=null && finalPreview.isFile && finalPreview.length()>0){
            val ready=(latest ?: EngineProgress(1,1,dev.filmroom.diagnostics.NativePhase.PRINTING_SCANNING,1,1)).copy(display=ExportDisplayProgress(6,1,1),scanPreview=finalPreview,scanPreviewFinal=parameters.hasFinishing)''')

add('app/src/main/java/dev/filmroom/export/ScanPreviewPublicationGate.kt', '''package dev.filmroom.export

/** Per-attempt publication. Final finishing may replace an early scan once.
 * FilmEngine joins its polling worker before delivering the committed final.
 * Claims are attempts, so an optional JPEG failure cannot spin on every poll.
 */
internal class ScanPreviewPublicationGate {
    private var intermediateAttempted=false
    private var finalAttempted=false
    fun claim(final:Boolean):Boolean {
        if(finalAttempted || (!final && intermediateAttempted))return false
        if(final)finalAttempted=true else intermediateAttempted=true
        return true
    }
}
''')
p = 'app/src/main/java/dev/filmroom/export/ExportQueue.kt'
replace(p, 'var previewAttempted=false', 'val previewPublication=ScanPreviewPublicationGate()')
replace(p, r'it.name.matches(Regex("film-result-[0-9a-f-]{36}\\.rgb(\\.part)?"))', 'dev.filmroom.lifecycle.PrivateWorkRecovery.isDisposableExportResult(it.name)')
replace(p, '''                    if(!previewAttempted && p.display.stage==6 && p.scanPreview!=null && previewAttempt!=null){
                        previewAttempted=true
                        try {BorderMemoryAdmission.withBudget(ScanPreviewStore.WORKING_BYTES,{NativeWorkReservations.snapshot(context)}){
                            ScanPreviewStore.publish(dir,checkNotNull(previewAttempt),p.scanPreview,initial,borderProvider){previewContext.ensureActive()}''', '''                    if(p.display.stage==6 && p.scanPreview!=null && previewAttempt!=null && previewPublication.claim(p.scanPreviewFinal)){
                        try {BorderMemoryAdmission.withBudget(ScanPreviewStore.WORKING_BYTES,{NativeWorkReservations.snapshot(context)}){
                            if(p.scanPreviewFinal)ScanPreviewStore.publishFinal(dir,checkNotNull(previewAttempt),p.scanPreview,initial,borderProvider){previewContext.ensureActive()}
                            else ScanPreviewStore.publish(dir,checkNotNull(previewAttempt),p.scanPreview,initial,borderProvider){previewContext.ensureActive()}''')

p = 'app/src/main/java/dev/filmroom/export/ScanPreviewStore.kt'
replace(p, r'private val source=Regex("scan-source-([0-9a-f-]{36})\\.png")', r'private val source=Regex("scan-source-([0-9a-f-]{36})(-finished)?\\.png")')
replace(p, '''        return File(dir,"scan-preview-$id.jpg").takeIf{it.isFile&&it.length() in 1..MAX_FILE_BYTES && token(dir)==id}''', '''        // Distinct identity invalidates UI bitmap caches even within the same
        // millisecond/byte length. A late intermediate can never supersede final.
        return listOf("scan-preview-$id-final.jpg","scan-preview-$id.jpg")
            .map{File(dir,it)}.firstOrNull{it.isFile&&it.length() in 1..MAX_FILE_BYTES && token(dir)==id}''')
replace(p, '''    fun publish(dir:File,id:String,input:File,job:ExportJob,provider:BorderProvider,checkActive:()->Unit) {
        checkActive();require(uuid.matches(id)&&token(dir)==id)''', '''    fun publish(dir:File,id:String,input:File,job:ExportJob,provider:BorderProvider,checkActive:()->Unit) =
        publishInternal(dir,id,input,job,provider,false,checkActive)
    fun publishFinal(dir:File,id:String,input:File,job:ExportJob,provider:BorderProvider,checkActive:()->Unit) =
        publishInternal(dir,id,input,job,provider,true,checkActive)
    private fun publishInternal(dir:File,id:String,input:File,job:ExportJob,provider:BorderProvider,final:Boolean,checkActive:()->Unit) {
        checkActive();require(uuid.matches(id)&&token(dir)==id)''')
replace(p, '        val match=source.matchEntire(input.name)?:error("Unexpected scan source name")', '''        val match=source.matchEntire(input.name)?:error("Unexpected scan source name")
        require(!final || !job.parameters.hasFinishing || match.groupValues[2]=="-finished") { "Final finishing source is not committed" }''')
replace(p, '        val part=File(dir,"scan-preview-$id.jpg.part")', '''        val target=File(dir,if(final)"scan-preview-$id-final.jpg" else "scan-preview-$id.jpg")
        val part=File(dir,target.name+".part")''')
replace(p, 'Files.move(part.toPath(),File(dir,"scan-preview-$id.jpg").toPath(),', 'Files.move(part.toPath(),target.toPath(),')

p = 'native/crates/film-engine/src/scan_preview.rs'
replace(p, '\npub struct Sampler {', '''
/// A successful post-finishing thumbnail has its own immutable identity. Failure
/// leaves this absent, rather than exposing an ungraded PNG under a final label.
pub fn finished_path(output: &Path) -> Option<PathBuf> {
    let ordinary = path(output)?;
    let stem = ordinary.file_stem()?.to_str()?;
    Some(ordinary.with_file_name(format!("{stem}-finished.png")))
}

pub struct Sampler {''')
replace(p, '''        let Some(target) = path(output) else { return Ok(None); };
        if self.failed''', '''        let Some(target) = path(output) else { return Ok(None); };
        self.publish_target(target, cancel)
    }
    fn publish_target(&self, target: PathBuf, cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
        active(cancel)?;
        if self.failed''')
replace(p, '''    pub fn publish(&self, output: &Path, cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
        active(cancel)?;
        let Some(target) = path(output) else { return Ok(None); };''', '''    pub fn publish(&self, output: &Path, cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
        let Some(target) = path(output) else { active(cancel)?; return Ok(None); };''')
replace(p, '''pub fn publish_restored(output: &Path, width: u32, height: u32, space: &str,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    active(cancel)?;
    if path(output).is_none() { return Ok(None); }''', '''pub fn publish_restored(output: &Path, width: u32, height: u32, space: &str,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    publish_restored_kind(output, width, height, space, false, cancel)
}
pub fn publish_finished(output: &Path, width: u32, height: u32, space: &str,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    publish_restored_kind(output, width, height, space, true, cancel)
}
fn publish_restored_kind(output: &Path, width: u32, height: u32, space: &str, finished: bool,
    cancel: &mut impl FnMut() -> bool) -> io::Result<Option<PathBuf>> {
    active(cancel)?;
    let target = if finished { finished_path(output) } else { path(output) };
    let Some(target) = target else { return Ok(None); };''')
replace(p, '    sampler.publish(output, cancel)', '    sampler.publish_target(target, cancel)')
replace('native/crates/film-engine/src/mobile.rs', 'crate::scan_preview::publish_restored(&output,width as u32,height as u32,&params.output_color_space', 'crate::scan_preview::publish_finished(&output,width as u32,height as u32,&params.output_color_space')

p = 'native/crates/film-engine/src/scan_preview.rs'
replace(p, ' #[test]fn chunk_sampling_is_read_only_and_srgb_is_encoded_once(){', ''' #[test]fn finishing_publication_has_distinct_identity_and_graded_pixels(){
  let t=temp();let out=output(&t);let values=vec![0.18f32;96];
  fs::write(&out,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let early=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();let before=fs::read(&early).unwrap();
  let grade=crate::finishing::Finishing{exposure_ev:1.,..Default::default()};
  grade.apply_spool(&out,32,"sRGB",||false).unwrap();let final_source=publish_finished(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();
  assert_ne!(early,final_source);assert_eq!(Some(final_source.clone()),finished_path(&out));
  assert_eq!(fs::read(&early).unwrap(),before);
  assert_eq!(fs::read(final_source).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![162;96]));
 }
 #[test]fn finishing_cancel_or_bad_spool_never_publishes_an_ungraded_final(){
  let t=temp();let out=output(&t);fs::write(&out,(0..96).flat_map(|_|0.18f32.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let early=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();let before=fs::read(&early).unwrap();
  assert!(publish_finished(&out,8,4,"sRGB",&mut||true).is_err());
  assert!(!finished_path(&out).unwrap().exists());
  fs::write(&out,b"incomplete").unwrap();assert!(publish_finished(&out,8,4,"sRGB",&mut||false).is_err());
  assert!(!finished_path(&out).unwrap().exists());assert_eq!(fs::read(early).unwrap(),before);
 }
 #[test]fn chunk_sampling_is_read_only_and_srgb_is_encoded_once(){''')

add('app/src/test/java/dev/filmroom/lifecycle/FinishingRecoveryHostTest.kt', r'''package dev.filmroom.lifecycle

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FinishingRecoveryHostTest {
    @get:Rule val temporary=TemporaryFolder()
    private val id="12345678-1234-1234-1234-123456789abc"
    private val name="film-result-$id.rgb.finishing.part"
    private fun touch(parent:File,name:String)=File(parent,name).apply{writeText("owned data")}

    @Test fun processRestartReclaimsOnlyFinishingPartialsAndPreservesCheckpointLinks() {
        val cache=temporary.newFolder("cache");val files=temporary.newFolder("files")
        val job=File(files,"export-jobs/$id").apply{mkdirs()}
        val partial=touch(job,name);val cachePartial=touch(cache,name)
        val legacy=touch(cache,"film-input-Ab12.rgb.output.finishing.part")
        val input=touch(job,"input.rgb");val result=touch(job,"film-result-$id.rgb")
        val checkpoint=File(job,"checkpoint").apply{mkdirs()};val saved=touch(checkpoint,"density.rgb")
        val nested=touch(checkpoint,name)
        val link=File(job,"saved-link.rgb");Files.createLink(link.toPath(),saved.toPath())
        val unknown=touch(job,"photo.rgb.finishing.part")
        val other=File(files,"export-jobs/not-a-job").apply{mkdirs()};val otherPartial=touch(other,name)
        val report=PrivateWorkRecovery.recover(cache,files,null)
        assertEquals(0,report.failed);assertEquals(3,report.removed)
        for(f in listOf(partial,cachePartial,legacy))assertFalse(f.exists())
        for(f in listOf(input,result,saved,nested,link,unknown,otherPartial))assertTrue(f.path,f.exists())
        assertEquals("owned data",saved.readText());assertEquals("owned data",link.readText())
        assertEquals(0,PrivateWorkRecovery.recover(cache,files,null).removed)
    }
    @Test fun startupDoesNotFollowJobRootJobDirectoryOrTemporarySymlinks() {
        val cache=temporary.newFolder("cache");val files=temporary.newFolder("files");val outside=temporary.newFolder("outside")
        val victim=touch(outside,name)
        val jobs=File(files,"export-jobs").apply{mkdirs()}
        Files.createSymbolicLink(File(jobs,id).toPath(),outside.toPath())
        val real=File(jobs,"22345678-1234-1234-1234-123456789abc").apply{mkdirs()}
        Files.createSymbolicLink(File(real,name).toPath(),victim.toPath())
        assertEquals(0,PrivateWorkRecovery.recover(cache,files,null).removed);assertTrue(victim.exists())
        val otherFiles=temporary.newFolder("other-files")
        Files.createSymbolicLink(File(otherFiles,"export-jobs").toPath(),jobs.toPath())
        assertEquals(0,PrivateWorkRecovery.recover(cache,otherFiles,null).removed);assertTrue(victim.exists())
    }
    @Test fun retryRecognizesKilledFinishingOutputButNotSourcesOrArbitraryFiles() {
        for(n in listOf("film-result-$id.rgb","film-result-$id.rgb.part",name))
            assertTrue(n,PrivateWorkRecovery.isDisposableExportResult(n))
        for(n in listOf("input.rgb","source.rgb","checkpoint.rgb", "film-result-not-a-uuid.rgb.finishing.part",name+".bak","photo.jpg"))
            assertFalse(n,PrivateWorkRecovery.isDisposableExportResult(n))
        val job=temporary.newFolder("retry");val stale=touch(job,name);val input=touch(job,"input.rgb")
        job.listFiles()!!.filter{it.isFile&&PrivateWorkRecovery.isDisposableExportResult(it.name)}.forEach{assertTrue(it.delete())}
        assertFalse(stale.exists());assertTrue(input.exists())
    }
}
''')

p = 'app/src/test/java/dev/filmroom/export/ScanPreviewStoreHostTest.kt'
replace(p, ' @Test fun onlyMatchingAttemptAtStageSixSelectsRealColorAndFinalStillWins(){', ''' @Test fun finalFinishingReplacesIntermediateOnceAndChangesDisplayIdentity(){
  val d=dir();try{
   val id=ScanPreviewStore.begin(d);val j=job(EditorState(finishExposureEv=1f));val gate=ScanPreviewPublicationGate()
   val early=source(d);assertTrue(gate.claim(false));ScanPreviewStore.publish(d,id,early,j,provider){}
   val old=checkNotNull(ScanPreviewStore.select(d));val oldBytes=old.readBytes();assertFalse(gate.claim(false))
   val b=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GREEN)
   val final=File(d,"scan-source-${UUID.randomUUID()}-finished.png")
   try{final.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{b.recycle()}
   assertTrue(gate.claim(true));ScanPreviewStore.publishFinal(d,id,final,j,provider){}
   val selected=checkNotNull(ScanPreviewStore.select(d));assertNotEquals(old.name,selected.name)
   assertArrayEquals(oldBytes,old.readBytes())
   val bitmap=BitmapFactory.decodeFile(selected.path);try{assertTrue(Color.green(bitmap.getPixel(120,80))>240)}finally{bitmap.recycle()}
   assertEquals(selected,ExportPreviewSelection.select(d,ExportJobState.RUNNING,7).file)
   assertFalse(gate.claim(true));assertFalse(gate.claim(false))
   ScanPreviewStore.publish(d,id,early,j,provider){};assertEquals(selected,ScanPreviewStore.select(d))
   val next=ScanPreviewStore.begin(d);assertNotEquals(id,next);assertNull(ScanPreviewStore.select(d));assertFalse(selected.exists())
  }finally{d.deleteRecursively()}
 }
 @Test fun missingMalformedCancelledOrStaleFinalNeverReplacesIntermediate(){
  val d=dir();try{
   val id=ScanPreviewStore.begin(d);val j=job(EditorState(finishSaturation=.5f));val early=source(d)
   ScanPreviewStore.publish(d,id,early,j,provider){};val selected=checkNotNull(ScanPreviewStore.select(d));val before=selected.readBytes()
   try{ScanPreviewStore.publishFinal(d,id,early,j,provider){};fail("Ungraded source cannot be final") }catch(_:IllegalArgumentException){}
   val bad=File(d,"scan-source-${UUID.randomUUID()}-finished.png").apply{writeText("broken")}
   try{ScanPreviewStore.publishFinal(d,id,bad,j,provider){};fail()}catch(_:IllegalArgumentException){}
   val final=File(d,"scan-source-${UUID.randomUUID()}-finished.png");early.copyTo(final)
   try{ScanPreviewStore.publishFinal(d,id,final,j,provider){throw CancellationException()};fail()}catch(_:CancellationException){}
   assertEquals(selected,ScanPreviewStore.select(d));assertArrayEquals(before,selected.readBytes())
   val next=ScanPreviewStore.begin(d)
   try{ScanPreviewStore.publishFinal(d,id,final,j,provider){};fail()}catch(_:IllegalArgumentException){}
   assertNotEquals(id,next);assertNull(ScanPreviewStore.select(d))
  }finally{d.deleteRecursively()}
 }
 @Test fun fastFinalWithoutIntermediateIsPublishedAndDuplicateCallbacksAreIgnored(){
  val gate=ScanPreviewPublicationGate();assertTrue(gate.claim(true));assertFalse(gate.claim(false));assertFalse(gate.claim(true))
  val retry=ScanPreviewPublicationGate();assertTrue(retry.claim(false));assertTrue(retry.claim(true))
 }
 @Test fun onlyMatchingAttemptAtStageSixSelectsRealColorAndFinalStillWins(){''')

p='.github/workflows/finishing-performance.yml'
replace(p, "paths: ['app/**', 'native/**', 'codecs/**', 'docs/**', 'tools/**']", "paths: ['app/**', 'native/**', 'codecs/**', 'docs/**', 'tools/**', '.github/workflows/finishing-performance.yml']")
replace(p, '          cargo test --locked -p film-engine --features precision-f64 --lib finishing -- --test-threads=1', '''          cargo test --locked -p film-engine --features precision-f64 --lib finishing -- --test-threads=1
          cargo test --locked -p film-engine --features precision-f64 --lib scan_preview::tests -- --test-threads=1''')
replace(p, '--tests dev.filmroom.presets.PresetQrCodecHostTest', '--tests dev.filmroom.presets.PresetQrCodecHostTest --tests dev.filmroom.lifecycle.FinishingRecoveryHostTest --tests dev.filmroom.export.ScanPreviewStoreHostTest')

add('docs/FINISHING_REVIEW_FIXES.md', '''# Finishing branch review fixes

Review base: `711e09213bfc6aaff4a5de104ce6669a576a792c`.

- Recover `film-result-UUID.rgb.finishing.part` at process startup in the private
  cache and direct UUID export-job directories. Retry removes this result type
  before disk admission. Source, checkpoint, arbitrary files and symlinked job
  trees are not traversed or deleted. Legacy direct-cache finishing files also
  have a narrow allowlist. Normal cancellation retains the existing Rust guard.
- Keep intermediate and post-finishing preview identities separate end to end:
  native `scan-source-UUID-finished.png`, final-ready callback, one intermediate
  plus one final publication, and `scan-preview-ATTEMPT-final.jpg`. The final
  is preferred and has a new UI file identity. Late intermediate callbacks,
  cancelled/invalid final images and stale attempts cannot replace it.
- A failed optional final thumbnail leaves only the intermediate available;
  its mere existence can never be mistaken for a committed finishing result.
  Actual exported pixels, darkroom processing and checkpoint data are unchanged.

Regressions are wired into the read-only finishing-performance workflow:
`FinishingRecoveryHostTest`, `ScanPreviewStoreHostTest`, native `finishing`
and `scan_preview::tests`, alongside the existing cache/GPU/codec checks.
These are host checks; they do not establish phone performance or a device
force-stop test result. No APK or main/dev merge is included.
''')

for name, text in texts.items():
    path=Path(name);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
subprocess.run(['git','diff','--check'],check=True)
print('Applied verified review fixes:', len(texts), 'files')
