package dev.filmroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,31,35],application=android.app.Application::class)
class ExportBackgroundGuidanceHostTest {
    private lateinit var activity:Activity
    private var state=ExportBackgroundStatus.UNKNOWN
    private var launches=0
    private var continuations=0
    private var verifiedContinuations=0
    private var settingsAvailable=true
    private lateinit var guidance:ExportBackgroundGuidance
    @Before fun setup() {
        activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        activity.getSharedPreferences("export-background-guidance",Context.MODE_PRIVATE).edit().clear().commit()
        L10n.initialize(activity)
        ShadowToast.reset()
        guidance=newGuidance()
    }
    private fun newGuidance()=ExportBackgroundGuidance(activity,{state},{launches++;settingsAvailable},{continuations++},{verifiedContinuations++})
    private fun dialog()=ShadowAlertDialog.getLatestAlertDialog()
    private fun press(id:Int) {
        dialog().window!!.decorView.findViewWithTag<Button>(id).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun standardSignalsAreNotAnOemAllEnabledBoolean() {
        assertEquals(ExportBackgroundStatus.STANDARD_ALLOWED,ExportBackgroundPolicy.assess(false,true))
        for(exempt in listOf(true,false,null))assertEquals(ExportBackgroundStatus.RESTRICTED,ExportBackgroundPolicy.assess(true,exempt))
        for(pair in listOf(false to false,false to null,null to true,null to false,null to null))
            assertEquals(ExportBackgroundStatus.UNKNOWN,ExportBackgroundPolicy.assess(pair.first,pair.second))
    }
    @Test fun publicProbeRunsOnEverySupportedApi() {
        assertNotNull(ExportBackgroundPolicy.read(activity))
    }
    @Test fun allowedContinuesImmediatelyAndRepeatedClicksDoNotReenter() {
        state=ExportBackgroundStatus.STANDARD_ALLOWED
        guidance.offer();guidance.offer();guidance.onResume()
        assertEquals(1,continuations);assertEquals(0,launches)
        guidance.finish();guidance.offer();assertEquals(2,continuations)
    }
    @Test fun cancelNeverContinuesAndAnotherExplicitClickCanRetry() {
        guidance.offer();val first=dialog();guidance.offer();assertSame(first,dialog())
        first.cancel();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0,continuations)
        guidance.offer();assertNotSame(first,dialog());assertTrue(dialog().isShowing)
    }
    @Test fun ignoreIsOnlyForThisRequest() {
        guidance.offer();press(R.string.export_background_ignore)
        assertEquals(1,continuations)
        guidance.finish();guidance.offer();assertTrue(dialog().isShowing)
    }
    @Test fun alwaysIgnorePersistsAcrossControllerAndStatusChanges() {
        guidance.offer();press(R.string.export_background_always_ignore)
        assertEquals(1,continuations)
        state=ExportBackgroundStatus.RESTRICTED
        guidance=newGuidance();guidance.offer()
        assertEquals(2,continuations);assertEquals(0,launches)
    }
    @Test fun unknownSettingsReturnNeverGrantsOrAutomaticallyContinues() {
        guidance.offer();press(R.string.export_background_open)
        guidance.offer();assertEquals(1,launches);assertEquals(0,continuations)
        guidance.onResume();val confirmation=dialog();guidance.onResume();assertSame(confirmation,dialog())
        assertEquals(0,continuations)
        confirmation.window!!.decorView.findViewWithTag<Button>(R.string.export_background_continue).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        guidance.onResume();assertEquals(1,continuations)
    }
    @Test fun verifiedSettingsReturnShowsShortToastAndOpensOptionsExactlyOnceWithoutSecondModal() {
        guidance.offer();val warning=dialog();press(R.string.export_background_open)
        state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.onResume()
        assertEquals(0,continuations);assertEquals(1,verifiedContinuations)
        assertFalse(warning.isShowing);assertSame(warning,dialog())
        assertEquals(L10n.text(R.string.export_background_verified_toast),ShadowToast.getTextOfLatestToast())
        assertEquals(android.widget.Toast.LENGTH_SHORT,ShadowToast.getLatestToast().duration)
        guidance.onResume();guidance.onResume();guidance.offer()
        assertEquals(1,verifiedContinuations);assertEquals(1,ShadowToast.shownToastCount())
    }
    @Test fun normalResumeAndInitiallyAllowedNeverShowSuccessToast() {
        state=ExportBackgroundStatus.STANDARD_ALLOWED
        guidance.onResume();guidance.onResume();assertEquals(0,verifiedContinuations)
        guidance.offer();assertEquals(1,continuations)
        assertEquals(0,verifiedContinuations);assertEquals(0,ShadowToast.shownToastCount())
    }
    @Test fun pendingSettingsSurvivesRecreationButConsumedReturnNeverReplays() {
        guidance.offer();press(R.string.export_background_open)
        val pending=Bundle();guidance.save(pending);guidance.close()
        guidance=newGuidance();guidance.restore(pending)
        state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.onResume()
        assertEquals(1,verifiedContinuations);assertEquals(1,launches)
        val consumed=Bundle();guidance.save(consumed);guidance.close()
        guidance=newGuidance();guidance.restore(consumed);guidance.onResume()
        assertEquals(1,verifiedContinuations);assertEquals(1,ShadowToast.shownToastCount())
    }
    @Test fun unknownAndRestrictedReturnNeverEmitSuccessOrBypassConfirmation() {
        for(status in listOf(ExportBackgroundStatus.UNKNOWN,ExportBackgroundStatus.RESTRICTED)) {
            guidance=newGuidance();state=status
            guidance.offer();press(R.string.export_background_open);guidance.onResume()
            assertTrue(dialog().isShowing);assertEquals(0,verifiedContinuations)
            assertEquals(0,ShadowToast.shownToastCount())
            press(R.string.export_background_cancel);state=ExportBackgroundStatus.STANDARD_ALLOWED
            guidance.onResume();assertEquals(0,verifiedContinuations)
        }
    }
    @Test fun failedSettingsLaunchCannotBecomeAutomaticSuccessOnRestore() {
        settingsAvailable=false;guidance.offer();press(R.string.export_background_open)
        val saved=Bundle();guidance.save(saved);guidance.close();ShadowToast.reset()
        state=ExportBackgroundStatus.STANDARD_ALLOWED
        guidance=newGuidance();guidance.restore(saved);guidance.onResume()
        assertTrue(dialog().isShowing);assertEquals(0,verifiedContinuations)
        assertEquals(0,ShadowToast.shownToastCount())
    }
    @Test fun cancelledWarningAndLegacyReturnBundleCannotAutoContinue() {
        guidance.offer();dialog().cancel();shadowOf(Looper.getMainLooper()).idle()
        state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.onResume()
        assertEquals(0,verifiedContinuations);assertEquals(0,continuations)
        val old=Bundle().apply { putString(ExportBackgroundGuidance.STATE_KEY,"RETURN_CONFIRM") }
        guidance=newGuidance();guidance.restore(old);guidance.onResume()
        assertTrue(dialog().isShowing);assertEquals(0,verifiedContinuations)
        assertEquals(0,ShadowToast.shownToastCount())
    }
    @Test fun repeatedOpenTapsNeverLaunchSettingsOrContinueTwice() {
        guidance.offer()
        val button=dialog().window!!.decorView.findViewWithTag<Button>(R.string.export_background_open)
        button.performClick();button.performClick();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,launches);assertEquals(0,verifiedContinuations)
        state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.onResume();guidance.onResume()
        assertEquals(1,verifiedContinuations)
    }
    @Test fun verifiedToastIsLocalizedInAllFourLanguages() {
        val messages=mutableSetOf<String>()
        for(language in listOf("zh","en","ja","ko")) {
            L10n.choose(activity,language);state=ExportBackgroundStatus.UNKNOWN;guidance=newGuidance()
            guidance.offer();press(R.string.export_background_open)
            state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.onResume()
            val message=ShadowToast.getTextOfLatestToast().toString()
            assertEquals(L10n.text(R.string.export_background_verified_toast),message)
            assertTrue(message.isNotBlank());messages+=message
        }
        assertEquals(4,messages.size);assertEquals(4,verifiedContinuations)
    }
    @Test fun absentSettingsShowsExplicitContinuationWithoutExport() {
        settingsAvailable=false;guidance.offer();press(R.string.export_background_open)
        assertEquals(0,continuations);assertTrue(dialog().isShowing)
        assertNotNull(dialog().window!!.decorView.findViewWithTag<Button>(R.string.export_background_continue))
    }
    @Test fun warningAndSettingsReturnSurviveRecreationWithoutReentry() {
        guidance.offer();val warning=Bundle();guidance.save(warning);guidance.close()
        guidance=newGuidance();guidance.restore(warning);guidance.onResume()
        assertTrue(dialog().isShowing);assertEquals(0,continuations)
        press(R.string.export_background_open)
        val pending=Bundle();guidance.save(pending);guidance.close()
        guidance=newGuidance();guidance.restore(pending);guidance.onResume()
        assertTrue(dialog().isShowing);assertEquals(1,launches);assertEquals(0,continuations)
        dialog().cancel();shadowOf(Looper.getMainLooper()).idle()
        val cancelled=Bundle();guidance.save(cancelled)
        assertEquals("IDLE",cancelled.getString(ExportBackgroundGuidance.STATE_KEY))
    }
    @Test fun downstreamContinuationIsNeverReplayedAfterRecreation() {
        state=ExportBackgroundStatus.STANDARD_ALLOWED;guidance.offer()
        val saved=Bundle();guidance.save(saved);guidance.close()
        guidance=newGuidance();guidance.restore(saved);guidance.onResume()
        assertEquals(1,continuations)
    }
    @Test fun missingOrProtectedSettingsRoutesFallBackWithoutThrowing() {
        val visited=mutableListOf<String>()
        assertTrue(ExportBackgroundSettings.open("dev.filmroom",{true}) { intent ->
            visited+=intent.action!!
            when(intent.action) {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> throw SecurityException("OEM protected")
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> throw ActivityNotFoundException()
            }
        })
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,Settings.ACTION_SETTINGS),visited)
        assertFalse(ExportBackgroundSettings.open("dev.filmroom",{false}) { fail("Unresolved route launched") })
        assertFalse(ExportBackgroundSettings.open("dev.filmroom",{throw SecurityException()}) { fail("Restricted resolver launched") })
    }
    @Test fun fakeOemsOnlyChangeInstructionsNeverPermissionPolicy() {
        for(name in listOf("vivo","iQOO"))assertEquals(R.string.export_background_help_vivo,ExportBackgroundPolicy.helpResource(name,"generic"))
        for(name in listOf("OPPO","realme","OnePlus"))assertEquals(R.string.export_background_help_oppo,ExportBackgroundPolicy.helpResource("generic",name))
        for(name in listOf("Xiaomi","Redmi","POCO"))assertEquals(R.string.export_background_help_xiaomi,ExportBackgroundPolicy.helpResource(name,"generic"))
        assertEquals(R.string.export_background_help_generic,ExportBackgroundPolicy.helpResource("fake OEM","unknown"))
    }
    @Test fun fourLanguageActionsAreStackedAndPrimaryUsesBrandOrange() {
        for(language in listOf("zh","en","ja","ko")) {
            L10n.choose(activity,language);guidance=newGuidance();guidance.offer()
            val open=dialog().window!!.decorView.findViewWithTag<Button>(R.string.export_background_open)
            val ignore=dialog().window!!.decorView.findViewWithTag<Button>(R.string.export_background_ignore)
            val always=dialog().window!!.decorView.findViewWithTag<Button>(R.string.export_background_always_ignore)
            assertTrue(open.text.isNotBlank());assertTrue(ignore.text.isNotBlank());assertTrue(always.text.isNotBlank())
            assertEquals(android.graphics.Color.rgb(229,163,93),(open.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            assertEquals(android.widget.LinearLayout.VERTICAL,(open.parent as android.widget.LinearLayout).orientation)
            assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT,open.layoutParams.width)
            guidance.close()
        }
    }
}
