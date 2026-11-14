// Optional host-only SDK Stage2 oracle. Build with a separately obtained Adobe
// SDK; never linked into the app. Emits active Stage2 pixels as native float32,
// with dimensions/planes on stderr. SDK integer backing is converted by Get.
#include "dng_host.h"
#include "dng_info.h"
#include "dng_negative.h"
#include "dng_file_stream.h"
#include "dng_image.h"
#include "dng_pixel_buffer.h"
#include "dng_tag_types.h"
#include "dng_exceptions.h"
#include <cstdio>
#include <vector>
int main(int n,char**v){try{if(n!=2)return 1;dng_host host;dng_file_stream stream(v[1]);dng_info info;info.Parse(host,stream);info.PostParse(host);if(!info.IsValidDNG())return 2;AutoPtr<dng_negative> neg(host.Make_dng_negative());neg->Parse(host,stream,info);neg->PostParse(host,stream,info);neg->ReadStage1Image(host,stream,info);neg->BuildStage2Image(host);const dng_image*im=neg->Stage2Image();auto b=im->Bounds();auto p=im->Planes();fprintf(stderr,"stage2 %d %d planes=%u type=%u origin=%d,%d\n",b.W(),b.H(),p,im->PixelType(),b.l,b.t);std::vector<float> row(b.W()*p);for(int y=b.t;y<b.b;y++){dng_pixel_buffer z;z.fArea=dng_rect(y,b.l,y+1,b.r);z.fPlane=0;z.fPlanes=p;z.fRowStep=b.W()*p;z.fColStep=p;z.fPlaneStep=1;z.fPixelType=ttFloat;z.fPixelSize=4;z.fData=row.data();im->Get(z);if(fwrite(row.data(),4,row.size(),stdout)!=row.size())return 4;}return fclose(stdout)?5:0;}catch(const dng_exception&e){fprintf(stderr,"SDK error %u\n",e.ErrorCode());return 9;}catch(...){return 9;}}
