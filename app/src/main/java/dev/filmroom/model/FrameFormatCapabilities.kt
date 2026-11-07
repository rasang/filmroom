package dev.filmroom.model

import dev.filmroom.border.MediumFormat

/** Frame choices depend on reviewed frame-strip artwork, not selector packaging renders. */
internal data class FrameFormatCapabilities(val has135:Boolean,val has120:Boolean) {
    val formats:List<MediumFormat?> get()=(if(has135)listOf(null)else emptyList()) +
        (if(has120)MediumFormat.entries else emptyList())
    val available get()=has135 || has120
    fun formatForEnable(selected:MediumFormat?):MediumFormat? = when {
        has120 && selected!=null -> selected
        has135 -> null
        has120 -> MediumFormat.MF66
        else -> null
    }
}
