package dev.filmroom.diagnostics

/** Protocol tokens only. Never retain native messages, paths or unknown strings. */
enum class NativeEngineCode {
    GPU_COMPLETION_UNCERTAIN, GPU_UNAVAILABLE, GPU_EXECUTION_FAILED, GPU_BINDING_LIMIT, CANCELLED, ENGINE_BUSY, ENGINE_PANIC, EXPORT_PATH_POLICY, IMAGE_TOO_LARGE,
    INTERNAL_ERROR, INVALID_BUDGET, INVALID_BUFFER, INVALID_DIMENSIONS, INVALID_HANDLE,
    INVALID_INPUT_FILE, INVALID_OUTPUT_SHAPE, INVALID_PARAMETERS, INVALID_PARAMS,
    INVALID_PATH, INVALID_PIXELS, IO_ERROR, JNI_ERROR, MEMORY_BUDGET_TOO_SMALL,
    MISSING_DATA, NONFINITE_OUTPUT, PIPELINE_ERROR, PROFILE_ERROR,
    UNSUPPORTED_EXPORT_OPTIONS, UNSUPPORTED_PROFILE, UNSUPPORTED_SCHEMA, CHECKPOINT_INVALID, UNKNOWN;
    companion object { fun fromId(id:String)=entries.firstOrNull{it.name==id}?:UNKNOWN }
}
enum class NativeEngineStage(val id:String) {
    VALIDATE_REQUEST("validate_request"), ACQUIRE_HANDLE("acquire_handle"),
    VALIDATE_PATHS_AND_EXPORT("validate_paths_and_export"), VALIDATE_BUFFERS("validate_buffers"),
    RENDER_PIPELINE("render_pipeline"), SCIENTIFIC_PIPELINE("scientific_pipeline"), COMMIT_OUTPUT("commit_output"),
    SETUP("setup"), AUTO_EXPOSURE("auto_exposure"), HIGHLIGHT_BOOST("highlight_boost"),
    CAMERA_BLUR("camera_blur"), SCAN_BLUR("scan_blur"), SCAN_GLARE("scan_glare"),
    GRAIN_MICROSTRUCTURE("grain_microstructure"), PRINT_DIFFUSION("print_diffusion"),
    CAMERA_DIFFUSION("camera_diffusion"), FILM_EXPOSURE("film_exposure"), FILM_SCATTER("film_scatter"),
    FILM_HALATION("film_halation"), FILM_LOG("film_log"), FILM_DENSITY("film_density"),
    DIR_DIFFUSION("dir_diffusion"), DIR_DEVELOP("dir_develop"), GRAIN_SAMPLING("grain_sampling"),
    GRAIN_CLOUD("grain_cloud"), GRAIN_BLUR("grain_blur"), PRINTING_SCANNING("printing_scanning"),
    SCAN_SHARPEN("scan_sharpen"), COMPLETE("complete"), UNKNOWN("unknown");
    companion object { fun fromId(id:String)=entries.firstOrNull{it.id==id}?:UNKNOWN }
}
