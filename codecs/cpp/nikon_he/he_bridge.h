#ifndef FILMROOM_HE_BRIDGE_V1
#define FILMROOM_HE_BRIDGE_V1
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Borrowed pointers; synchronous callbacks; callbacks MUST NOT unwind/panic. */
typedef int32_t (*fr_he_sink)(void*,const uint16_t*,uint32_t,uint32_t,uint32_t);
typedef int32_t (*fr_he_cancel)(void*);
enum {FR_HE_OK=0,FR_HE_UNSUPPORTED=1,FR_HE_INVALID=2,FR_HE_CANCELLED=3,FR_HE_SINK=4,FR_HE_OOM=5,FR_HE_INTERNAL=6};
typedef struct {uint32_t abi_version;uint32_t width;uint32_t height;uint32_t rows_written;int32_t status;} fr_he_result;
int32_t fr_he_probe_v1(const uint8_t*,size_t,uint32_t,uint32_t);
int32_t fr_he_decode_v1(const uint8_t* input,size_t bytes,uint32_t width,uint32_t height,
 fr_he_sink sink,void* sink_context,fr_he_cancel cancel,void* cancel_context,fr_he_result* result);
#ifdef __cplusplus
}
#endif
#endif
