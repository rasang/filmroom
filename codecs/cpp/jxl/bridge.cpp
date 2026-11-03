// Narrow storage-only JPEG XL bridge. No CMS, preview, tone map, or RGB profile conversion.
#include <jxl/decode.h>
#include <jxl/color_encoding.h>
#include <cstdint>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <limits>
#include <unistd.h>
#include <new>

namespace {
constexpr size_t INPUT_BYTES = 1024 * 1024;
using Cancel = int (*)(const void*);
struct alignas(std::max_align_t) Allocation { size_t bytes; };
struct Context {
 size_t used=0, peak=0, limit=0;
 bool denied=false;
 int error=0, os_error=0, output=-1;
 uint32_t width=0,height=0;
 uint64_t pixels=0, output_base=0;
 Cancel cancel=nullptr; const void* cancel_data=nullptr;
 bool cancelled() const { return cancel && cancel(cancel_data); }
};
void* allocate(void* opaque,size_t n) {
 auto& c=*static_cast<Context*>(opaque);
 if(c.cancelled()){c.error=4;return nullptr;}
 if(n>std::numeric_limits<size_t>::max()-sizeof(Allocation) || n+sizeof(Allocation)>c.limit-c.used){c.denied=true;return nullptr;}
 const size_t bytes=n+sizeof(Allocation);auto* a=static_cast<Allocation*>(std::malloc(bytes));
 if(!a){c.denied=true;return nullptr;}a->bytes=bytes;c.used+=bytes;if(c.used>c.peak)c.peak=c.used;return a+1;
}
void release(void* opaque,void* p) {
 if(!p)return;
 auto&c=*static_cast<Context*>(opaque);auto*a=static_cast<Allocation*>(p)-1;c.used-=a->bytes;std::free(a);
}
void pixels(void* opaque,size_t x,size_t y,size_t n,const void* data) {
 auto&c=*static_cast<Context*>(opaque);if(c.error)return;
 if(c.cancelled()){c.error=4;return;}
 if(y>=c.height || x>=c.width || n==0 || n>c.width-x || n>uint64_t(c.width)*c.height-c.pixels){c.error=1;return;}
 c.pixels+=n;
 auto*p=static_cast<const uint8_t*>(data);size_t bytes=n*6;
 uint64_t at=c.output_base+(uint64_t(y)*c.width+x)*6;
 while(bytes){ssize_t k=pwrite(c.output,p,bytes,static_cast<off_t>(at));if(k<0&&errno==EINTR)continue;if(k<=0){c.error=5;c.os_error=k<0?errno:EIO;return;}p+=k;bytes-=k;at+=k;}
}
bool same_profile(const JxlColorEncoding& a,const JxlColorEncoding& b) {
 if(a.color_space!=JXL_COLOR_SPACE_RGB || a.color_space!=b.color_space || a.white_point!=b.white_point || a.primaries!=b.primaries || a.transfer_function!=b.transfer_function || a.rendering_intent!=b.rendering_intent)return false;
 if(a.white_point==JXL_WHITE_POINT_CUSTOM && (a.white_point_xy[0]!=b.white_point_xy[0] || a.white_point_xy[1]!=b.white_point_xy[1]))return false;
 if(a.primaries==JXL_PRIMARIES_CUSTOM)for(int i=0;i<2;++i)if(a.primaries_red_xy[i]!=b.primaries_red_xy[i] || a.primaries_green_xy[i]!=b.primaries_green_xy[i] || a.primaries_blue_xy[i]!=b.primaries_blue_xy[i])return false;
 return a.transfer_function!=JXL_TRANSFER_FUNCTION_GAMMA || a.gamma==b.gamma;
}
int run(Context& c,int input,uint64_t offset,uint64_t length,bool header_only) {
 JxlMemoryManager mm{&c,allocate,release};JxlDecoder* dec=JxlDecoderCreate(&mm);if(!dec)return 3;
 struct Guard{JxlDecoder*d;~Guard(){JxlDecoderDestroy(d);}} guard{dec};
 if(JxlDecoderSetKeepOrientation(dec,JXL_TRUE)!=JXL_DEC_SUCCESS ||
    JxlDecoderSetUnpremultiplyAlpha(dec,JXL_FALSE)!=JXL_DEC_SUCCESS ||
    JxlDecoderSetRenderSpotcolors(dec,JXL_FALSE)!=JXL_DEC_SUCCESS ||
    JxlDecoderSubscribeEvents(dec,JXL_DEC_BASIC_INFO|JXL_DEC_COLOR_ENCODING|JXL_DEC_FRAME|JXL_DEC_FULL_IMAGE)!=JXL_DEC_SUCCESS)return 1;
 auto* buffer=static_cast<uint8_t*>(allocate(&c,INPUT_BYTES));if(!buffer)return 3;
 struct BufferGuard{Context*c;void*p;JxlDecoder*d;~BufferGuard(){JxlDecoderReleaseInput(d);release(c,p);}} bg{&c,buffer,dec};
 uint64_t supplied=0;size_t valid=0;bool input_set=false,closed=false,basic=false,color=false;unsigned frames=0,full=0;
 while(true){
  if(c.cancelled())return 4;
  auto status=JxlDecoderProcessInput(dec);if(c.error)return c.error;if(c.denied)return 3;
  if(status==JXL_DEC_NEED_MORE_INPUT){
   if(closed)return 1;
   size_t remain=input_set?JxlDecoderReleaseInput(dec):0;
   if(remain>valid || remain>=INPUT_BYTES)return 1;
   if(remain)std::memmove(buffer,buffer+valid-remain,remain);
   size_t want=static_cast<size_t>(length-supplied);if(want>INPUT_BYTES-remain)want=INPUT_BYTES-remain;
   if(!want)return 1;
   size_t got=0;while(got<want){ssize_t n=pread(input,buffer+remain+got,want-got,static_cast<off_t>(offset+supplied+got));if(n<0&&errno==EINTR)continue;if(n<=0){c.os_error=n<0?errno:0;return n<0?5:1;}got+=n;}
   supplied+=got;valid=remain+got;
   if(JxlDecoderSetInput(dec,buffer,valid)!=JXL_DEC_SUCCESS)return 1;
   input_set=true;
   if(supplied==length){JxlDecoderCloseInput(dec);closed=true;}
  }else if(status==JXL_DEC_BASIC_INFO){
   JxlBasicInfo b{};if(basic || JxlDecoderGetBasicInfo(dec,&b)!=JXL_DEC_SUCCESS)return 1;
   if(b.xsize!=c.width || b.ysize!=c.height)return 1;
   if(b.bits_per_sample!=16 || b.exponent_bits_per_sample!=0 || !b.uses_original_profile || b.num_color_channels!=3 || b.num_extra_channels!=0 || b.alpha_bits!=0 || b.have_animation || b.have_preview || b.orientation!=JXL_ORIENT_IDENTITY)return 2;
   basic=true;
  }else if(status==JXL_DEC_COLOR_ENCODING){
   if(!basic || color)return 1;
   JxlColorEncoding original{}, output{};
   if(JxlDecoderGetColorAsEncodedProfile(dec,JXL_COLOR_PROFILE_TARGET_ORIGINAL,&original)!=JXL_DEC_SUCCESS || JxlDecoderGetColorAsEncodedProfile(dec,JXL_COLOR_PROFILE_TARGET_DATA,&output)!=JXL_DEC_SUCCESS || !same_profile(original,output))return 2;
   color=true;if(header_only)return 0;
  }else if(status==JXL_DEC_FRAME){
   JxlFrameHeader frame{};if(!color || ++frames!=1 || JxlDecoderGetFrameHeader(dec,&frame)!=JXL_DEC_SUCCESS || !frame.is_last)return 2;
  }else if(status==JXL_DEC_NEED_IMAGE_OUT_BUFFER){
   if(!basic || !color || frames!=1)return 1;
   JxlPixelFormat format{3,JXL_TYPE_UINT16,JXL_LITTLE_ENDIAN,0};JxlBitDepth depth{JXL_BIT_DEPTH_FROM_CODESTREAM,0,0};
   if(JxlDecoderSetImageOutCallback(dec,&format,pixels,&c)!=JXL_DEC_SUCCESS || JxlDecoderSetImageOutBitDepth(dec,&depth)!=JXL_DEC_SUCCESS)return 1;
  }else if(status==JXL_DEC_FULL_IMAGE){if(++full!=1)return 2;}
  else if(status==JXL_DEC_SUCCESS){return basic&&color&&frames==1&&full==1&&c.pixels==uint64_t(c.width)*c.height?0:1;}
  else return 1;
 }
}
}
// return: 0 success,1 malformed,2 unsupported semantic,3 budget,4 cancelled,5 IO.
extern "C" int film_jxl_decode(int input,uint64_t offset,uint64_t length,int output,uint64_t output_base,uint32_t width,uint32_t height,size_t budget,Cancel cancel,const void* cancel_data,uint64_t* peak,int* os_error) noexcept {
 Context c;c.limit=budget;c.output=output;c.output_base=output_base;c.width=width;c.height=height;c.cancel=cancel;c.cancel_data=cancel_data;
 int rc=1;
 try {if(width&&height&&length&&length<=uint64_t(INT64_MAX)&&offset<=uint64_t(INT64_MAX)-length&&output_base<=uint64_t(INT64_MAX)-uint64_t(width)*height*6)rc=run(c,input,offset,length,output<0);}
 catch(const std::bad_alloc&){rc=3;}catch(...){rc=1;}
 *peak=c.peak;*os_error=c.os_error;if(c.error)rc=c.error;else if(c.denied)rc=3;return rc;
}
