// Optional host-only test adapter. Requires a separately obtained official Adobe
// DNG SDK; neither the SDK nor this adapter is linked into Filmroom. Emits the
// SDK's actual integer Stage1 image after executing the file's entire List1.
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
int main(int argc, char **argv) {
  try {
    if (argc != 2) return 1;
    dng_host host;
    dng_file_stream stream(argv[1]);
    dng_info info;
    info.Parse(host, stream);
    info.PostParse(host);
    if (!info.IsValidDNG()) return 2;
    AutoPtr<dng_negative> negative(host.Make_dng_negative());
    negative->Parse(host, stream, info);
    negative->PostParse(host, stream, info);
    negative->ReadStage1Image(host, stream, info);
    AutoPtr<dng_image> image(negative->Stage1Image()->Clone());
    host.ApplyOpcodeList(negative->OpcodeList1(), *negative, image);
    const auto bounds = image->Bounds();
    if (image->PixelType() != ttShort || image->Planes() != 1 || bounds.l || bounds.t) return 3;
    fprintf(stderr, "SDK Stage1 after List1: %d x %d\n", bounds.W(), bounds.H());
    std::vector<uint16> row(bounds.W());
    for (int y = 0; y < bounds.H(); ++y) {
      dng_pixel_buffer buffer;
      buffer.fArea = dng_rect(y, 0, y + 1, bounds.W());
      buffer.fPlane = 0; buffer.fPlanes = 1;
      buffer.fRowStep = bounds.W(); buffer.fColStep = 1; buffer.fPlaneStep = 1;
      buffer.fPixelType = ttShort; buffer.fPixelSize = 2; buffer.fData = row.data();
      image->Get(buffer);
      if (fwrite(row.data(), 2, row.size(), stdout) != row.size()) return 4;
    }
    return fclose(stdout) ? 5 : 0;
  } catch (const dng_exception &e) {
    fprintf(stderr, "SDK error %u\n", e.ErrorCode()); return 9;
  } catch (...) { return 9; }
}
