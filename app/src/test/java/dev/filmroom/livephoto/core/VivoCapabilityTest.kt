package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test

class VivoCapabilityTest {
    private fun capability(value: String?, model: String? = null) =
        VivoCapability.read { if (it == VivoCapability.SUPPORT_PROPERTY) value else model }
    private class Store : CompatibilityStore {
        override fun read(environmentKey: String, profile: LivePhotoProfile): CompatibilityRecord? = null
        override fun write(record: CompatibilityRecord) {}
        override fun remove(environmentKey: String, profile: LivePhotoProfile) {}
        override fun clear() {}
    }
    @Test fun explicitPropertyWinsOverWhitelist() {
        assertEquals(VivoCapability.Evidence.DECLARED_SUPPORTED, capability("1", "unknown").evidence)
        assertTrue(capability("2", "PD2405").blocksExport)
        assertEquals(SupportStatus.UNSUPPORTED, capability("2").status)
        assertFalse(capability("1").blocksExport)
    }
    @Test fun missingInvalidAndUnknownValuesRemainUnknownUnlessWhitelisted() {
        for (value in listOf(null, "", "UNSET", "0", "-1", "3", "2147483648")) {
            assertEquals(SupportStatus.UNKNOWN, capability(value, "unknown").status)
            assertFalse(capability(value).blocksExport)
            assertEquals(VivoCapability.Evidence.SDK_MODEL_MATCH, capability(value, "PD2405").evidence)
        }
        assertEquals(VivoCapability.ReadState.MISSING, capability(null).readState)
        assertEquals(VivoCapability.ReadState.INVALID, capability("UNSET").readState)
    }
    @Test fun whitelistUsesExactModelNotPrefixOrMarketingName() {
        assertEquals(36, VivoCapability.SDK_MODELS.size)
        VivoCapability.SDK_MODELS.forEach { assertEquals(SupportStatus.SUPPORTED, capability(null,it).status) }
        for(model in listOf("PD2405A", "PD2405 ", "X200", "iQOO 15"))
            assertEquals(SupportStatus.UNKNOWN, capability(null,model).status)
    }
    @Test fun whitelistNormalizesCaseBeforeMatching() {
        for (model in listOf("PD2405", "pd2405", "Pd2405", "pd2324ha", "Pd2339Fa")) {
            val result = capability(null, model)
            assertEquals(VivoCapability.Evidence.SDK_MODEL_MATCH, result.evidence)
            assertEquals(SupportStatus.SUPPORTED, result.status)
        }
        // Explicit capability still wins over a case-normalized model match.
        assertTrue(capability("2", "pd2405").blocksExport)
    }
    @Test fun deniedOrUnavailablePropertiesDoNotBecomeUnsupported() {
        for(error in listOf(SecurityException(), NoClassDefFoundError())) {
            val result=VivoCapability.read { throw error }
            assertEquals(VivoCapability.ReadState.UNREADABLE,result.readState)
            assertEquals(SupportStatus.UNKNOWN,result.status)
            assertFalse(result.blocksExport)
        }
        val fallback=VivoCapability.read {
            if(it==VivoCapability.SUPPORT_PROPERTY) throw SecurityException() else "PD2405"
        }
        assertEquals(VivoCapability.Evidence.SDK_MODEL_MATCH,fallback.evidence)
    }
    @Test fun capabilityDoesNotClaimOurFileWasAcceptedAndDoesNotSelectFormat() {
        val resolver=ProfileResolver(Store())
        for(cap in listOf(capability("1"),capability(null,"PD2405"),capability(null))) {
            for(version in listOf("0","1")) {
                val selection=resolver.resolve(TargetBrand.VIVO, vivoCapability=cap,
                    vivoVersion=VivoVersionObservation.read { version })
                assertEquals(SupportStatus.UNKNOWN,selection.status)
                assertEquals(if(version=="1") LivePhotoProfile.VIVO_SINGLE else LivePhotoProfile.VIVO_PAIR_2014,selection.selectedProfile)
            }
        }
    }
    @Test fun explicitNoSupportBlocksEvenManualFormatButNotOtherBrands() {
        val resolver=ProfileResolver(Store())
        val cap=capability("2")
        for(manual in listOf(null,LivePhotoProfile.VIVO_SINGLE,LivePhotoProfile.GOOGLE)) {
            val selection=resolver.resolve(TargetBrand.VIVO,manual=manual,vivoCapability=cap)
            assertNull(selection.selectedProfile)
            assertEquals(SupportStatus.UNSUPPORTED,selection.status)
        }
        for(brand in TargetBrand.values().filter { it != TargetBrand.VIVO }) {
            val selected=resolver.resolve(brand,vivoCapability=cap)
            assertNull(selected.vivoCapability)
            assertFalse(selected.warnings.any { it.contains("vivo capability") })
        }
    }
}
