#pragma once
#include "nikon_he_tile.h"
#include "nikon_he_rows.h"
namespace nikon_he {
TileDecodeResult decode_tile_cancel(
    const uint8_t* const* precinct_data,
    const size_t* precinct_sizes,
    const PictureHeader& header,
    const SubbandConfig config[26],
    PrecinctPredecessorState& pred_state,
    const uint8_t* predict_lut,
    int32_t* tile_coeff_buf,
    int32_t* overflow_carry,
    bool is_first_tile,
    int precinct_count, bool (*cancel)(void*), void* context);

HeDecodeResult decode_rows_cancel(const uint8_t*,size_t,int,int,RowSink,void*,bool (*)(void*),void*);
}
