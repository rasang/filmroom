#ifndef FILMROOM_RESEARCH_NIKON_HE_ROWS_H
#define FILMROOM_RESEARCH_NIKON_HE_ROWS_H
#include "nikon_he_decode.h"
namespace nikon_he {
// Synchronous contiguous u16 rows, exact sensor order, borrowed pointer.
// Callback owns cancellation/output failure policy. False makes decode fail.
// No normalization, crop, WB, gamma, demosaic or resampling here.
using RowSink = bool (*)(void* context, const uint16_t* pixels,
                        int first_row, int row_count, int width);
HeDecodeResult decode_nikon_he_rows(const uint8_t* strip, size_t strip_size,
                                   int image_width, int image_height,
                                   RowSink sink, void* sink_context);
}
#endif
