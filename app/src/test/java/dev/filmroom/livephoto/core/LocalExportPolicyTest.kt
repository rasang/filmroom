package dev.filmroom.livephoto.core

import dev.filmroom.effects.EffectLivePhotoExporter
import org.junit.Assert.*
import org.junit.Test

class LocalExportPolicyTest {
    private fun env(manufacturer: String, sdk: Int) = TargetEnvironment(
        manufacturer, manufacturer, "test", "test-build", sdk, null, null, "test")
    private class Store : CompatibilityStore {
        override fun read(environmentKey: String, profile: LivePhotoProfile): CompatibilityRecord? = null
        override fun write(record: CompatibilityRecord) {}
        override fun remove(environmentKey: String, profile: LivePhotoProfile) {}
        override fun clear() {}
    }
    private val resolver = ProfileResolver(Store())

    @Test fun oplusUsesFeatureNotAndroidVersion() {
        for (maker in listOf("OPPO", "oppo", "OnePlus", "REALME")) {
            for (sdk in listOf(29, 33, 34, 37)) {
                assertNull(LocalExportPolicy.blockedReason(TargetBrand.OPLUS, env(maker, sdk).copy(oplusOliveFeature = true)))
                val rejected = resolver.resolve(TargetBrand.OPLUS, env(maker, sdk).copy(oplusOliveFeature = false))
                assertNull(rejected.selectedProfile)
                assertTrue(EffectLivePhotoExporter.Preflight(rejected).deviceUnsupported)
                assertNull(EffectLivePhotoExporter.forDirectAttempt(rejected).selectedProfile)
            }
        }
        assertNotNull(LocalExportPolicy.blockedReason(TargetBrand.OPLUS, env("OPLUS", 37).copy(oplusOliveFeature = true)))
        assertNull(LocalExportPolicy.blockedReason(TargetBrand.OPLUS, env("OPPO", 37).copy(oplusOliveFeature = null)))
    }
    @Test fun oplusFeatureQueryPreservesUnknownErrors() {
        assertEquals(false, LocalExportPolicy.readOplusFeature { assertEquals("oplus.software.gallery.olive", it); false })
        assertEquals(true, LocalExportPolicy.readOplusFeature { true })
        assertNull(LocalExportPolicy.readOplusFeature { throw SecurityException() })
        assertNull(LocalExportPolicy.readOplusFeature { throw NoClassDefFoundError() })
    }
    @Test fun android14BoundaryAndNewerSystems() {
        for ((brand, manufacturer) in listOf(TargetBrand.HONOR to "HONOR", TargetBrand.SAMSUNG to "samsung")) {
            for (sdk in listOf(29, 32, 33)) assertNotNull(LocalExportPolicy.blockedReason(brand, env(manufacturer, sdk)))
            for (sdk in listOf(34, 35, 37)) assertNull(LocalExportPolicy.blockedReason(brand, env(manufacturer, sdk)))
        }
    }
    @Test fun honorRequiresExactManufacturerSamsungIgnoresCase() {
        for (name in listOf("honor", "Honor", "HONOR ", "HUAWEI", "unknown"))
            assertNotNull(LocalExportPolicy.blockedReason(TargetBrand.HONOR, env(name, 34)))
        for (name in listOf("samsung", "Samsung", "SAMSUNG"))
            assertNull(LocalExportPolicy.blockedReason(TargetBrand.SAMSUNG, env(name, 34)))
        for (name in listOf("samsung ", "not-samsung", "unknown"))
            assertNotNull(LocalExportPolicy.blockedReason(TargetBrand.SAMSUNG, env(name, 34)))
    }
    @Test fun remoteTargetsAndOtherBrandsDoNotBorrowTheseRules() {
        for (brand in TargetBrand.values()) assertNull(LocalExportPolicy.blockedReason(brand, null))
        for (brand in TargetBrand.values().filter { it !in listOf(TargetBrand.OPLUS, TargetBrand.HONOR, TargetBrand.SAMSUNG) })
            assertNull(LocalExportPolicy.blockedReason(brand, env("unknown", 29)))
    }
    @Test fun rejectionSurvivesManualProfileAndDirectAttempt() {
        for ((brand, maker) in listOf(TargetBrand.HONOR to "HONOR", TargetBrand.SAMSUNG to "samsung")) {
            for (manual in listOf(null, LivePhotoProfile.GOOGLE)) {
                val result = resolver.resolve(brand, env(maker, 33), manual)
                assertNull(result.selectedProfile)
                assertEquals(SupportStatus.UNSUPPORTED, result.status)
                assertTrue(EffectLivePhotoExporter.Preflight(result).deviceUnsupported)
                assertNull(EffectLivePhotoExporter.forDirectAttempt(result).selectedProfile)
            }
        }
    }
    @Test fun admittedDevicesRemainUnconfirmedAndKeepExistingFormats() {
        for ((brand, maker, profile) in listOf(
            Triple(TargetBrand.HONOR, "HONOR", LivePhotoProfile.HONOR_LEGACY),
            Triple(TargetBrand.SAMSUNG, "samsung", LivePhotoProfile.SAMSUNG_SEF_107))) {
            val result = resolver.resolve(brand, env(maker, 34))
            assertEquals(SupportStatus.UNKNOWN, result.status)
            assertEquals(profile, result.selectedProfile)
            assertNull(result.deviceExportBlockedReason)
            assertFalse(EffectLivePhotoExporter.Preflight(result).deviceUnsupported)
        }
    }
}
