package dev.filmroom.ui

import android.graphics.Color

/** UI chrome only. Never use these colors for image processing or film/frame rendering.
 * Keep background/surface in sync with values/colors.xml for the pre-Activity window. */
object FilmroomPalette {
    val background = Color.rgb(24, 24, 24) // #181818, deliberately not pure black
    val surface = Color.rgb(34, 34, 34) // #222222
    val raised = Color.rgb(44, 44, 44) // #2C2C2C
    val inset = Color.rgb(20, 20, 20) // #141414
    val ink = Color.rgb(241, 241, 241) // #F1F1F1
    val muted = Color.rgb(184, 184, 184) // #B8B8B8
    val ruler = Color.rgb(220, 220, 220) // #DCDCDC
    val divider = Color.rgb(59, 59, 59) // #3B3B3B, decorative only
    val track = Color.rgb(70, 70, 70) // #464646
    val accent = Color.rgb(229, 163, 93) // #E5A35D, retained Filmroom orange
    val onAccent = Color.rgb(24, 24, 24) // #181818
}
