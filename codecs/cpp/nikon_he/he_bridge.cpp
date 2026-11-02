#include "he_bridge.h"
#include "he_cancel_internal.h"
#include <new>
namespace {
struct Context {fr_he_sink sink;void* sink_ctx;fr_he_cancel cancel;void* cancel_ctx;uint32_t width,height,rows=0;int32_t error=0;};
bool stopped(void* p){auto& c=*static_cast<Context*>(p);if(c.error)return true;if(c.cancel&&c.cancel(c.cancel_ctx)){c.error=FR_HE_CANCELLED;return true;}return false;}
bool emit(void* p,const uint16_t* data,int first,int count,int width){auto&c=*static_cast<Context*>(p);if(stopped(p))return false;
 if(first<0||count<1||count>64||uint32_t(first)!=c.rows||uint32_t(width)!=c.width||uint32_t(count)>c.height-c.rows){c.error=FR_HE_INTERNAL;return false;}
 if(c.sink(c.sink_ctx,data,first,count,width)!=0){c.error=FR_HE_SINK;return false;}c.rows+=count;return true;}
}
extern "C" int32_t fr_he_decode_v1(const uint8_t* input,size_t bytes,uint32_t w,uint32_t h,fr_he_sink sink,void* sc,fr_he_cancel cancel,void* cc,fr_he_result* out){
 if(!out)return FR_HE_INVALID;
 *out={1,w,h,0,FR_HE_INVALID};
 if(!input||!sink||bytes<128||bytes>32u*1024*1024||!w||!h||uint64_t(w)*h>52000000)return out->status;
 Context c{sink,sc,cancel,cc,w,h};
 try{
  if(stopped(&c))return out->status=c.error;
  nikon_he::PictureHeader ph;
  if(!nikon_he::parse_picture_header(input,bytes,ph))return out->status=FR_HE_INVALID;
  if(!nikon_he::is_supported_picture_header(ph,bytes))return out->status=FR_HE_UNSUPPORTED;
  if(w!=ph.hdr_width||h!=ph.hdr_height)return out->status=FR_HE_INVALID;
  auto r=nikon_he::decode_rows_cancel(input,bytes,w,h,emit,&c,stopped,&c);
  out->rows_written=c.rows;
  return out->status=c.error?c.error:(r.success&&c.rows==h?FR_HE_OK:FR_HE_INVALID);
 }catch(const std::bad_alloc&){out->status=FR_HE_OOM;}catch(...){out->status=FR_HE_INTERNAL;}
 out->rows_written=c.rows;return out->status;
}

extern "C" int32_t fr_he_probe_v1(const uint8_t* p,size_t n,uint32_t w,uint32_t h){
 if(!p||n<128||n>32u*1024*1024||!w||!h||uint64_t(w)*h>52000000)return FR_HE_INVALID;
 try{nikon_he::PictureHeader ph;if(!nikon_he::parse_picture_header(p,n,ph))return FR_HE_INVALID;
 if(!nikon_he::is_supported_picture_header(ph,n))return FR_HE_UNSUPPORTED;
 return ph.hdr_width==w&&ph.hdr_height==h?FR_HE_OK:FR_HE_INVALID;
 }catch(const std::bad_alloc&){return FR_HE_OOM;}catch(...){return FR_HE_INTERNAL;}
}
