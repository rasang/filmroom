package dev.filmroom

import dev.filmroom.border.BorderCatalog
import dev.filmroom.border.FrameProfileCatalog
import dev.filmroom.border.MediumFormat
import dev.filmroom.border.MediumFormatStocks
import dev.filmroom.model.EditorState
import dev.filmroom.model.FrameFormatCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FrameFormatCapabilitiesHostTest {
    @Test fun allFourCapabilityCombinationsOfferOnlySupportedFormats() {
        for(has135 in listOf(false,true))for(has120 in listOf(false,true)) {
            val policy=FrameFormatCapabilities(has135,has120)
            assertEquals(has135 || has120,policy.available)
            assertEquals(has135,null in policy.formats)
            assertEquals(if(has120)MediumFormat.entries.toList()else emptyList<MediumFormat>(),policy.formats.filterNotNull())
            assertEquals(if(!has135 && has120)MediumFormat.MF66 else null,policy.formatForEnable(null))
            for(format in MediumFormat.entries)assertEquals(if(has120)format else null,policy.formatForEnable(format))
        }
    }
    @Test fun existing135AndDualFormatStocksRetainTheirPresentationPreferences() {
        for(stock in FrameProfileCatalog.kinds.keys.filter{BorderCatalog.available(it,null)}) {
            val p=EditorState(filmStock=stock,filmExposure=1.2f,filmFormatMm=60f)
            assertEquals(p.copy(filmBorder=true),p.selectFilmBorder(true))
            if(MediumFormatStocks.available(stock))for(format in MediumFormat.entries) {
                val medium=p.selectMediumFormat(format,true)
                assertEquals(medium.copy(filmBorder=true),medium.selectFilmBorder(true))
                assertEquals(medium,medium.selectFilmBorder(true).selectFilmBorder(false))
            }
        }
        val unavailable=EditorState(filmStock="unknown",filmExposure=1.2f)
        assertEquals(unavailable,unavailable.selectFilmBorder(true))
        assertEquals(unavailable,unavailable.copy(filmBorder=true).selectFilmBorder(false))
    }
    @Test fun everyRegistered120OnlyStockDefaultsTo66AndCannotSelectMissing135() {
        val stocks=MediumFormatStocks.profiles.keys.filterNot{BorderCatalog.available(it,null)}
        for(stock in stocks) {
            val p=EditorState(filmStock=stock,filmExposure=1.2f,filmFormatMm=60f)
            assertFalse(p.frameFormats.has135);assertTrue(p.frameFormats.has120)
            assertFalse(null in p.frameFormats.formats)
            val framed=p.selectFilmBorder(true)
            assertTrue(framed.filmBorder);assertEquals(MediumFormat.MF66,framed.effectiveMediumFormat)
            assertEquals(1.0,framed.composition.targetAspect!!,0.0)
            assertEquals(p.json(includePresentation=false),framed.json(includePresentation=false))
            assertFalse(framed.negativeWhiteHolesSupported)
            assertEquals(framed,EditorState.fromJson(framed.json()))
            assertThrows(IllegalArgumentException::class.java){framed.selectMediumFormat(null,false)}
            for(format in MediumFormat.entries) {
                val custom=p.selectMediumFormat(format,true)
                assertEquals(custom.copy(filmBorder=true),custom.selectFilmBorder(true))
            }
            val switched=EditorState(filmBorder=true).selectFilm(stock,p.paperStock,"print",p.paperStock)
            assertTrue(switched.filmBorder);assertEquals(MediumFormat.MF66,switched.effectiveMediumFormat)
        }
    }
}
