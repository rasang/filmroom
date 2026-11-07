package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import androidx.activity.ComponentActivity
import dev.filmroom.border.MediumFormat
import dev.filmroom.border.MediumFormatStocks
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.model.FilmProfileCapabilities
import dev.filmroom.presets.SavedPresetDialogs
import dev.filmroom.presets.SavedPresetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Integration-only: named presets + SpektraLab science IDs + reviewed 135+120 frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w400dp-h800dp-mdpi")
class SavedPresetIntegratedProfilesHostTest {
    private val silver="ilford_multigrade_iv_rc"
    private val reviewedDual=FilmProfileCapabilities.spektraLabFilms
    private fun settings(stock:String)=EditorState(filmStock=stock,paperStock=silver,
        outputRoute="direct",preferredOutputRoute="print",filmFormatMm=60f,
        saturation=1.7f,dirInterLayer=.7f,filmDevelopmentTime=9f,paperDevelopmentTime=6f,
        neutralFiltersFromDatabase=false,neutralCyan=12f,neutralMagenta=23f,neutralYellow=45f,
        printExposureEv=.75f,printYellow=3f,printMagenta=-2f,negativeWhiteHoles=false)
    private fun store():SavedPresetStore {
        val file=File(RuntimeEnvironment.getApplication().filesDir,"saved-presets.json")
        file.delete();File(file.path+".bak").delete()
        return SavedPresetStore(file)
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun await(done:()->Boolean) {
        repeat(1000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));if(done())return;Thread.sleep(5)}
        fail("Preset integration timed out")
    }

    @Test fun allNewFilmsSilverPaperAndAll120OrientationsRoundTripWithoutResettingHiddenValues() {
        val store=store()
        for(stock in FilmProfileCapabilities.spektraLabFilms) {
            val original=settings(stock)
            assertEquals(original,store.save(stock,original).parameters())
            if(stock in reviewedDual) for(format in MediumFormat.entries) for(portrait in listOf(false,true)) {
                val framed=original.selectMediumFormat(format,portrait).selectFilmBorder(true)
                val restored=store.save("$stock ${format.name} $portrait",framed).parameters()
                assertEquals(framed,restored)
                assertTrue(restored.frameFormats.has135);assertTrue(restored.frameFormats.has120)
                assertEquals(listOf(null)+MediumFormat.entries.toList(),restored.frameFormats.formats)
                assertEquals(format,restored.effectiveMediumFormat);assertEquals(portrait,restored.effectiveMediumPortrait)
                assertFalse(restored.negativeWhiteHolesSupported)
                assertEquals(original.json(includePresentation=false),restored.json(includePresentation=false))
            }
        }
    }

    @Test fun latent120OrientationSurvivesSaveWhileNewDefault135KeepsScience() {
        val store=store()
        for(stock in reviewedDual) {
            val original=settings(stock).copy(filmBorder=false,mediumFormat=null,mediumPortrait=true)
            val restored=store.save(stock,original).parameters()
            assertEquals(original,restored)
            val enabled=restored.selectFilmBorder(true)
            assertTrue(enabled.filmBorder);assertNull(enabled.effectiveMediumFormat)
            assertTrue(enabled.mediumPortrait);assertFalse(enabled.effectiveMediumPortrait)
            assertEquals(original.json(includePresentation=false),enabled.json(includePresentation=false))
        }
    }

    @Test fun actualLoadDialogAcceptsEveryNewFilmAndSilverPaperThroughPackagedCatalog() {
        val store=store()
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
            val activity=host.get()
            val photo=PhotoSource(File(activity.cacheDir,"integrated-preset-fixture"),"fixture","image/png",96,64,1)
            var current=EditorUiState(photo=photo,parameters=EditorState(brightness=.5f))
            var applications=0
            val dialogs=SavedPresetDialogs(activity,{current},{key,before,next ->
                assertEquals(photo.cacheKey,key);assertEquals(current.parameters,before)
                current=current.copy(parameters=next);applications++;true
            },{it.show()})
            for(stock in FilmProfileCapabilities.spektraLabFilms) {
                val expected=if(MediumFormatStocks.available(stock))settings(stock).selectMediumFormat(MediumFormat.MF617,true).selectFilmBorder(true)else settings(stock)
                val saved=store.save(stock,expected)
                dialogs.open();val dialog=ShadowAlertDialog.getLatestAlertDialog()
                val list=all(dialog.window!!.decorView).filterIsInstance<ListView>().single()
                await{list.adapter?.count==1}
                val before=current.parameters;val count=applications
                list.performItemClick(list.adapter.getView(0,null,list),0,0L)
                assertEquals(before,current.parameters)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                await{!dialog.isShowing}
                assertFalse("$stock and silver paper should pass catalog validation",dialog.isShowing)
                assertEquals(count+1,applications);assertEquals(expected,current.parameters)
                assertTrue(store.delete(saved.id));dialogs.close()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            }
        }
    }
}
