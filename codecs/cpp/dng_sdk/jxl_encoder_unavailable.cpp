// Filmroom only *reads* DNG files. The Adobe DNG SDK also contains a JPEG XL
// writer (used when saving DNGs), but Filmroom links the decoder-only build of
// the pinned libjxl 0.12. These definitions satisfy the linker for that unused
// writer path and fail closed: any attempt to encode returns an error, which
// the SDK reports as dng_error_jxl_encoder. No pixel path calls them.

#include <jxl/encode.h>

#include <cstring>

extern "C" {

JxlEncoder *JxlEncoderCreate(const JxlMemoryManager *) { return nullptr; }
void JxlEncoderDestroy(JxlEncoder *) {}
JxlEncoderStatus JxlEncoderAddBox(JxlEncoder *, const JxlBoxType, const uint8_t *, size_t, JXL_BOOL) {
	return JXL_ENC_ERROR;
}
JxlEncoderStatus JxlEncoderAddChunkedFrame(const JxlEncoderFrameSettings *, JXL_BOOL,
										   struct JxlChunkedFrameInputSource) {
	return JXL_ENC_ERROR;
}
JxlEncoderStatus JxlEncoderAddImageFrame(const JxlEncoderFrameSettings *, const JxlPixelFormat *,
										 const void *, size_t) {
	return JXL_ENC_ERROR;
}
void JxlEncoderCloseBoxes(JxlEncoder *) {}
void JxlEncoderCloseInput(JxlEncoder *) {}
JxlEncoderFrameSettings *JxlEncoderFrameSettingsCreate(JxlEncoder *, const JxlEncoderFrameSettings *) {
	return nullptr;
}
JxlEncoderStatus JxlEncoderFrameSettingsSetOption(JxlEncoderFrameSettings *, JxlEncoderFrameSettingId,
												  int64_t) {
	return JXL_ENC_ERROR;
}
void JxlEncoderInitBasicInfo(JxlBasicInfo *info) {
	if (info) std::memset(info, 0, sizeof(*info));
}
JxlEncoderStatus JxlEncoderSetBasicInfo(JxlEncoder *, const JxlBasicInfo *) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetCodestreamLevel(JxlEncoder *, int) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetColorEncoding(JxlEncoder *, const JxlColorEncoding *) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetFrameDistance(JxlEncoderFrameSettings *, float) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetFrameLossless(JxlEncoderFrameSettings *, JXL_BOOL) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetFrameName(JxlEncoderFrameSettings *, const char *) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetICCProfile(JxlEncoder *, const uint8_t *, size_t) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderSetOutputProcessor(JxlEncoder *, struct JxlEncoderOutputProcessor) {
	return JXL_ENC_ERROR;
}
JxlEncoderStatus JxlEncoderSetParallelRunner(JxlEncoder *, JxlParallelRunner, void *) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderUseBoxes(JxlEncoder *) { return JXL_ENC_ERROR; }
JxlEncoderStatus JxlEncoderUseContainer(JxlEncoder *, JXL_BOOL) { return JXL_ENC_ERROR; }

}  // extern "C"
