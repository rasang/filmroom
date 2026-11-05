package dev.filmroom.engine

/** Separate diagnostic ABI. Loading this bridge does not create a GPU device. */
object NativeGpuProbeBridge {
    /** Closed numeric schema only; caller uses the bounded lifecycle recorder parser. */
    external fun nativeGpuPhaseSnapshot(token: Long): String
    // Do not initialize NativeFilmBridge/L10n/Diagnostics in the helper process.
    init { System.loadLibrary("film_engine") }
    external fun nativePlanGpuDiffusionProbe(snapshotDir: String, token: Long): String
    external fun nativeRunGpuDiffusionProbe(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuDiffusionProbe(token: Long)
    external fun nativePlanGpuOpticalStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuOpticalStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuOpticalStage(token: Long)
    external fun nativePlanGpuPrintScan(snapshotDir: String, token: Long): String
    external fun nativeRunGpuPrintScan(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuPrintScan(token: Long)
    external fun nativePlanGpuFilmOpticsStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuFilmOpticsStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuFilmOpticsStage(token: Long)
    external fun nativePlanGpuFilmExposureStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuFilmExposureStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuFilmExposureStage(token: Long)
    external fun nativePlanGpuFilmDirStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuFilmDirStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuFilmDirStage(token: Long)
    external fun nativePlanGpuExposureLogDirStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuExposureLogDirStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuExposureLogDirStage(token: Long)
    external fun nativePlanGpuAutoExposureStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuAutoExposureStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuAutoExposureStage(token: Long)
    external fun nativePlanGpuHighlightBoostStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuHighlightBoostStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuHighlightBoostStage(token: Long)
    external fun nativePlanGpuExposureLogStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuExposureLogStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuExposureLogStage(token: Long)
    external fun nativePlanGpuFilmGrainStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuFilmGrainStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuFilmGrainStage(token: Long)
    external fun nativePlanGpuPrintExposureStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuPrintExposureStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuPrintExposureStage(token: Long)
    external fun nativePlanGpuPrintDiffusionStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuPrintDiffusionStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuPrintDiffusionStage(token: Long)
    external fun nativePlanGpuScanTailStage(snapshotDir: String, token: Long): String
    external fun nativeRunGpuScanTailStage(snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuScanTailStage(token: Long)
    external fun nativePlanGpuFinishTailStage(snapshotDir:String,token:Long):String
    external fun nativeRunGpuFinishTailStage(snapshotDir:String,budgetBytes:Long,token:Long,manifestSha256:String):String
    external fun nativeCancelGpuFinishTailStage(token:Long)






    external fun nativePlanGpuStreamingStage(operation: String, snapshotDir: String, token: Long): String
    external fun nativeRunGpuStreamingStage(operation: String, snapshotDir: String, budgetBytes: Long, token: Long, manifestSha256: String): String
    external fun nativeCancelGpuStreamingStage(token: Long)

}
