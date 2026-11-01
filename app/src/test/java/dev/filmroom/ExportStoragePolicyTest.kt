package dev.filmroom

import dev.filmroom.engine.ExportStoragePolicy
import dev.filmroom.engine.NativeExportPlan
import org.junit.Assert.*
import org.junit.Test

class ExportStoragePolicyTest {
    private fun plan(w:Int,h:Int):NativeExportPlan {val n=w.toLong()*h;return NativeExportPlan(w,h,n*12,n*12,n*128+64L*1024*1024,n*152+64L*1024*1024)}
    @Test fun fiftyMegapixelsUseLongArithmeticAndDoNotDoubleCountCopiedSource() {
        val p=plan(8192,6144);val expected=p.pixels*155+128L*1024*1024
        assertEquals(7_935_623_168L,expected)
        assertEquals(expected,ExportStoragePolicy.beforeInput(p))
        assertEquals(expected-p.pixels*12,ExportStoragePolicy.afterInput(p))
        assertFalse(ExportStoragePolicy.hasSpace(expected-1,expected));assertTrue(ExportStoragePolicy.hasSpace(expected,expected))
    }
    @Test fun largestAdmittedSourceDoesNotOverflowInt() {val p=plan(15000,10000);assertTrue(ExportStoragePolicy.beforeInput(p)>Int.MAX_VALUE)}
    @Test fun malformedPlanCannotUnderstateInputOutputOrTotal() {
        listOf({NativeExportPlan(4000,3000,1,144_000_000,0,144_000_001)}, {NativeExportPlan(4000,3000,144_000_000,144_000_000,10,288_000_000)}, {NativeExportPlan(Int.MAX_VALUE,2,0,0,0,0)}).forEach { block ->try{block();fail("invalid plan accepted")}catch(_:IllegalArgumentException){} }
    }
}
