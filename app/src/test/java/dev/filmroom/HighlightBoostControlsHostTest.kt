package dev.filmroom

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HighlightBoostControlsHostTest {
    @Before fun setup() {
        resetAndroidViewModelFactoryForHostTest()
        dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(), "zh")
    }

    private fun controls(activity: MainActivity): Map<String, Any> {
        MainActivity::class.java.getDeclaredField("group").apply { isAccessible = true }.set(activity, "film")
        val controls = MainActivity::class.java.getDeclaredMethod("extraControls").apply { isAccessible = true }.invoke(activity) as List<*>
        return controls.filterNotNull().associateBy { field(it, "id") as String }
    }
    private fun field(control: Any, name: String): Any = control.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(control)
    @Suppress("UNCHECKED_CAST")
    private fun active(control: Any, state: EditorState) = (field(control, "active") as (EditorState) -> Boolean)(state)
    @Suppress("UNCHECKED_CAST")
    private fun write(control: Any, state: EditorState, value: Float) = (field(control, "write") as (EditorState, Float) -> EditorState)(state, value)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun strengthTogglesBothDependentsWithoutChangingSavedValuesOrRenderParameters() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val controls = controls(activity)
        val strength = controls.getValue("highlightBoostEv")
        val original = EditorState(highlightBoostEv = 1.5f, highlightBoostRange = 0.7f, highlightProtectEv = 2.4f)
        val off = write(strength, original, 0f)
        assertEquals(original.copy(highlightBoostEv = 0f), off)
        val restored = EditorState.fromJson(off.json().toString())
        assertEquals(off, restored)
        assertEquals(original, write(strength, restored, 1.5f))
        for (id in listOf("highlightBoostRange", "highlightProtectEv")) {
            val control = controls.getValue(id)
            assertFalse(active(control, EditorState()))
            assertFalse(active(control, restored))
            assertTrue(active(control, original))
            assertTrue(active(control, original.copy(highlightBoostEv = 0.015f)))
        }
        assertTrue(active(strength, off))
    }

    @Test fun realSlidersAndResetButtonsDimAndDisableThenReenableInAllLanguages() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        for (language in listOf("zh", "en", "ja", "ko")) {
            dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(), language)
            val controls = controls(activity)
            val labels = when (language) {
                "zh" -> listOf("高光提升强度", "高光提升范围", "高光提升阈值")
                "ja" -> listOf("ハイライト増強の強度", "ハイライト増強の範囲", "ハイライト増強のしきい値")
                "ko" -> listOf("하이라이트 강화 강도", "하이라이트 강화 범위", "하이라이트 강화 임계값")
                else -> listOf("Highlight boost strength", "Highlight boost range", "Highlight boost threshold")
            }
            for ((index, id) in listOf("highlightBoostEv", "highlightBoostRange", "highlightProtectEv").withIndex()) {
                val control = controls.getValue(id)
                assertEquals(labels[index], field(control, "label"))
                for (strength in listOf(0f, 1f, 0f, 2f)) {
                    val state = EditorState(highlightBoostEv = strength, highlightBoostRange = 0.7f, highlightProtectEv = 2.4f)
                    val target = LinearLayout(activity)
                    MainActivity::class.java.getDeclaredMethod("singleSlider", control.javaClass, EditorState::class.java, Boolean::class.javaPrimitiveType, LinearLayout::class.java).apply { isAccessible = true }.invoke(activity, control, state, true, target)
                    val seek = descendants(target).filterIsInstance<SeekBar>().single()
                    val reset = (seek.parent as ViewGroup).getChildAt(0)
                    val enabled = id == "highlightBoostEv" || strength > 0f
                    assertEquals(enabled, seek.isEnabled)
                    assertEquals(enabled, reset.isEnabled)
                    assertEquals(if (enabled) 1f else 0.38f, seek.alpha, 0f)
                    assertEquals(if (enabled) 1f else 0.38f, reset.alpha, 0f)
                    if (id == "highlightBoostRange") assertEquals(140, seek.progress)
                    if (id == "highlightProtectEv") assertEquals(120, seek.progress)
                }
            }
        }
    }
}
