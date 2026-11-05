package dev.filmroom.engine

/** Session ABI only; loading the library does not create a device. */
internal object NativeGpuSessionBridge {
    init { System.loadLibrary("film_engine") }
    external fun nativeOpenGpuSession(config: String): String
    external fun nativeBeginGpuSessionFrame(epoch: Long, generation: Long): String
    external fun nativeRunGpuSessionStage(epoch: Long, generation: Long, sequence: Long, operation: String, directory: String, budget: Long, token: Long, sha: String): String
    external fun nativeSealGpuSessionFrame(epoch: Long, generation: Long): String
    external fun nativeRetireGpuSession(epoch: Long)
}
