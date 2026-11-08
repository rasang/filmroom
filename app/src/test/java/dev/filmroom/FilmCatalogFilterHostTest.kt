package dev.filmroom
import dev.filmroom.ui.FilmCatalogFilter as F
import org.junit.Assert.*
import org.junit.Test
class FilmCatalogFilterHostTest {
 @Test fun categoriesOverlapAndNoneStaysInAll() {
  assertTrue(F.matches("kodak_trix","reversal",F.Category.BW,null))
  assertTrue(F.matches("kodak_trix","reversal",F.Category.REVERSAL,null))
  assertTrue(F.matches("kodak_trix","reversal",F.Category.MOVIE,null))
  assertFalse(F.matches("kodak_trix","reversal",F.Category.COLOR,null))
  assertTrue(F.matches("kodak_vision3_500t","negative",F.Category.COLOR,null))
  assertTrue(F.matches("kodak_vision3_500t","negative",F.Category.MOVIE,null))
  assertTrue(F.matches("fujifilm_provia_100f","reversal",F.Category.COLOR,null))
  assertFalse(F.matches("kodak_portra_400","negative",F.Category.MOVIE,null))
  for(c in F.Category.values()) assertEquals(c==F.Category.ALL,F.matches("none","none",c,null))
  assertFalse(F.matches("none","none",F.Category.ALL,"kodak"))
  assertFalse(F.matches("kodak_2383","cine_print",F.Category.MOVIE,null))
 }
 @Test fun brandsIntersectAndUnknownStateIsSafe() {
  assertTrue(F.matches("ilford_hp5_plus_400","negative",F.Category.BW,"ilford"))
  assertFalse(F.matches("kodak_tmax_100","negative",F.Category.BW,"ilford"))
  assertEquals(F.Category.ALL,F.restoreCategory("bad"));assertNull(F.restoreBrand("bad"))
  for(lang in listOf("zh","en","ja","ko")) for(c in F.Category.values())assertTrue(F.label(c.name,lang).isNotBlank())
 }
}
