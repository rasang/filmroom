package dev.filmroom.imprint

import android.app.Application
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ImprintEntryLabelHostTest {
    @Test fun shortenedChineseEntryKeepsOtherLanguagesAndControlsUnchanged() {
        val app=RuntimeEnvironment.getApplication()
        val expected=mapOf("zh" to "日期参数","en" to "Date & data imprint",
            "ja" to "日付・撮影データの印字","ko" to "날짜·촬영 정보 인쇄")
        try {expected.forEach { (tag,text)->
            L10n.choose(app,tag)
            assertEquals(text,ImprintText.SETTINGS.text(app))
        }}finally{L10n.choose(app,"zh")}
        // This request renames the entry, not the editor title or date controls.
        assertEquals("日期和参数",ImprintText.TITLE.zh)
        assertEquals("日期印字",ImprintText.DATE_DIALOG.zh)
        assertEquals("日期和时间",ImprintText.DATE_TIME.zh)
    }
}
