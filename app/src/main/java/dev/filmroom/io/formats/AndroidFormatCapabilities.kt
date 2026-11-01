package dev.filmroom.io.formats

import android.graphics.ImageDecoder
import android.os.Build

/** Query on the actual device. This neither decodes a user photo nor uploads anything. */
object AndroidFormatCapabilities {
    fun inspect(
        integrations: CodecIntegrations = CodecIntegrations(),
        native: NativeCodecAvailability = NativeCodecAvailability(),
    ): FormatCapabilitySnapshot = FormatCapabilities.inspect(
        Build.VERSION.SDK_INT, ImageDecoder::isMimeTypeSupported, integrations, native,
    )
}
