# Real native persistent-queue regression (Linux host only)

`RealNativeExportQueueHostTest` deliberately uses **production** `ExportQueue`,
`FilmEngine`, `PhotoRepository`, `PhotoCodecs`, JNI bridges and Rust libraries.
Android platform services (memory/storage estimates and the MediaStore provider)
are host fixtures. The separate HEIC test also uses an explicitly fake HEVC pixel
backend: its real HEIC container is parsed, but Android HEVC pixels are NOT decoded
on Linux. It is not an Android device/real MediaStore test.

Build the existing Linux release libraries with the repository's native build
instructions, then place symlinks to both `libfilm_engine.so` and
`libfilm_codecs.so` in one directory. Run this test alone in its JVM: loading
native libraries from multiple Robolectric SDK sandbox classloaders is not
supported by the JVM.

```sh
export FILMROOM_HOST_JNI_DIR=/absolute/path/to/host-library-directory
# In restricted execution, point user.home at a writable pre-populated Maven cache:
export JAVA_TOOL_OPTIONS="-Duser.home=/absolute/path/to/writable-test-home"
gradle --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -I tools/host-jni/real-queue.init.gradle :app:testDebugUnitTest \
  --tests dev.filmroom.export.RealNativeExportQueueHostTest
```

Without `FILMROOM_HOST_JNI_DIR`, this opt-in test is explicitly skipped. Normal
unit tests need not install or load Linux native libraries.

Coverage uses a reproducible 32×24 linear RGB fixture, copied through the queue's
normal intake. Engine data is copied from the actual packaged assets. Assertions
cover real JPEG/TIFF decoding via the host JDK ImageIO implementation (loaded
reflectively because Android's compile API excludes `java.desktop`), dimensions,
ICC presence, fake-provider pending→published state, private spool cleanup,
retry of a real completed render checkpoint after an encoding-stage failure and
queue reconstruction, crash-after-publication deduplication, exact old cache-root
`INVALID_PATH`, cross-job/invalid-job/input/output/job-symlink rejection,
repository-level lexical symlink preservation, a real nonfinite-pixel encode
failure with no provider insertion or partial output, native sticky cancellation,
and cancellation of a waiting queue item.

Cancellation coverage here does not simulate process death during an in-flight
native write or actual Android foreground-service lifecycle events. Existing
unit/native cancellation suites cover those separate contracts.

## HEIC canonical-source regression

A second method in the same SDK 29/JNI sandbox imports the real 96×64 CC0 HEIC
container via `PhotoRepository`, with an explicitly injected marker-gradient
`HeicBackend`. It verifies the exact canonical float RGB bytes, immutable original
HEIC, reopen without a second decode, and real engine/codec queued exports:
original-size `LINEAR` JPEG, 48×32 `LINEAR_SOURCE` TIFF, and a persisted legacy
`RAW` source resumed after queue reconstruction/retry. ImageIO dimensions and
JPEG/TIFF ICC tags, finalized provider rows, spool/partial cleanup and source
ownership are asserted. This is integration coverage of the canonical source
contract, not evidence of device HEVC decoding, memory peaks or displayed colors.
