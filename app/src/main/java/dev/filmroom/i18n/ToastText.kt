package dev.filmroom.i18n

import dev.filmroom.R

/** Toast-only summaries. Never renders message arguments, raw errors or diagnostic text.
 * Detailed UiMessage/state/queue text remains unchanged. Unknown future keys fail closed. */
object ToastText {
    fun forMessage(message:UiMessage?):String = L10n.text(
        if(message!=null && ResourceKeys.arities[message.key]==message.args.size)resourceForKey(message.key) else R.string.toast_failed)
    internal fun resourceForKey(key:String?):Int = when(key) {
        "text_2673ef9ba447" -> R.string.toast_file_limit
        "text_5c55db3ba035" -> R.string.toast_raw_pixel_limit
        "text_d591741a5a6e" -> R.string.toast_raster_pixel_limit
        "text_d6bc69a0a6a8","text_ff27ed115be8" -> R.string.toast_export_pixel_limit
        "text_5c2432fd4e78","text_660bbeffe8d6" -> R.string.toast_memory
        "image_edge_limit_30000" -> R.string.toast_edge_limit_30000
        "image_edge_limit_32768" -> R.string.toast_edge_limit_32768
        "text_56e47360aaca" -> R.string.toast_raw_pixel_limit
        "text_9ff6012fdde9" -> R.string.toast_memory
        "text_1351a2d16cbe" -> R.string.toast_metadata_limit
        "camera_raw_variant_unsupported" -> R.string.toast_format
        "camera_raw_profile_unsupported" -> R.string.toast_profile
        // Known HEIC failure categories must not collapse to generic "operation failed".
        // Keep short localized summaries; detailed technical subcodes remain in share logs.
        "text_58a07dbdf8d7","text_986742c74398" -> R.string.toast_format
        "text_8b96428c13f4","text_1e6cd3983af1" -> R.string.toast_read
        "text_dfe135051811" -> R.string.toast_format
        "text_bb8990972dce" -> R.string.toast_queued
        "text_834295cc897b","text_420e35e0302a" -> R.string.toast_queued_check
        "text_afb4ebeb2c42" -> R.string.toast_cancel_requested
        "text_f9957f03a610" -> R.string.toast_background
        "text_9f9e68d602d6" -> R.string.toast_uncertain
        "text_2416623f6d39" -> R.string.toast_imported_cancel
        "text_d630bd89f6dd" -> R.string.toast_import_cancel
        "text_6f25582e0d79" -> R.string.toast_preview_cancel
        "text_cd6fac59a752" -> R.string.toast_cleanup
        "text_063189730acc","text_075e5b2e2761","text_a064cfbab736","text_b769fd8412bb","text_9c508b3acf89","text_d408527bac5d","text_63b74410c9d6","text_312bb591804d","text_52dca03550a9","text_2f6241f58a5d","text_662817bc355b" -> R.string.toast_reimport
        "text_3052d04d78c2","text_acd49b3daba5","text_df30ae362f2b","text_93313da164ac","text_d171c3322a9c","text_022fe563baa4","text_f1632bf9ae5b","text_389695202ee7","text_5e9174903275" -> R.string.toast_memory
        "text_368306bc6068","text_9392b8f7c99e","text_276ad5585012","text_01b0b11fd332","text_6bceec6d29d6","text_ba94a97c604b" -> R.string.toast_storage
        "text_d5113f613aad" -> R.string.toast_size
        "text_f6e0e231129a","text_3bf690c56856","text_dac02f5d66dd","text_d3826ba783c7","text_0f079bb732f5","text_0105eb1c06a7","text_46ac8768982e","text_c946e8a02212","text_e92a0c3cbaef","text_56858cdae70f" -> R.string.toast_format
        "text_c976e3b99689","text_a428b047ad8e","text_e9d5f1386eb5","text_cab3449611ff","text_f515de6af876","text_0e98e94d0aff","text_5f37317ec18e","text_199d9706e7a1" -> R.string.toast_read
        "text_bc37a5375a5d" -> R.string.toast_preview
        "text_aeb3f82f0b35","text_11ee5fdc4a0b" -> R.string.toast_add_failed
        "text_0a9765e80ef5","text_a4ca69371996","text_2c28d802618b","text_41db58446aa4" -> R.string.toast_engine
        "text_6c0813b39bb5" -> R.string.toast_cancelled
        "text_e46692a12a54" -> R.string.toast_profile
        "text_fda570c695c8" -> R.string.toast_parameters
        "text_8bbca1842388" -> R.string.toast_budget
        "text_77f7a824adc1","text_0c0a9b25a114" -> R.string.toast_incomplete
        "text_9695c3a1a61a","text_b64913db4f1d" -> R.string.toast_workdir
        else -> R.string.toast_failed
    }
}
