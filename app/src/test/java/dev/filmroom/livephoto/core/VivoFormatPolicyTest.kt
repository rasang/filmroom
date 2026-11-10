package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test

class VivoFormatPolicyTest {
    private class Store: CompatibilityStore {
        val records=mutableListOf<CompatibilityRecord>()
        override fun read(environmentKey:String,profile:LivePhotoProfile)=records.find { it.environmentKey==environmentKey && it.profile==profile }
        override fun write(record:CompatibilityRecord) { records.removeAll { it.profile==record.profile }; records.add(record) }
        override fun remove(environmentKey:String,profile:LivePhotoProfile) { records.removeAll { it.environmentKey==environmentKey && it.profile==profile } }
        override fun clear() { records.clear() }
    }
    @Test fun mirrorsV2VersionBoundaryAndDefaultZeroWithoutInventingAnObservation() {
        for (value in listOf("1","2","2147483647")) assertEquals(LivePhotoProfile.VIVO_SINGLE,VivoVersionObservation.read { value }.preferredProfile)
        for (value in listOf("0","-1","-2147483648",null,"","UNSET","1.0","2147483648")) assertEquals(LivePhotoProfile.VIVO_PAIR_2014,VivoVersionObservation.read { value }.preferredProfile)
        assertEquals(VivoVersionObservation.State.MISSING,VivoVersionObservation.read { "" }.state)
        assertEquals(VivoVersionObservation.State.INVALID,VivoVersionObservation.read { "UNSET" }.state)
        val denied=VivoVersionObservation.read { throw SecurityException("denied") }
        assertEquals(VivoVersionObservation.State.UNREADABLE,denied.state)
        assertNull(denied.value); assertEquals(0,denied.effectiveVersion)
        assertTrue(denied.diagnostic.contains("not an observed"))
    }
    @Test fun versionControlsCandidatesNotCompatibilityOrManualOverride() {
        val resolver=ProfileResolver(Store())
        val observation=VivoVersionObservation.read { "" }
        val selected=resolver.resolve(TargetBrand.VIVO,firstFrame=false,vivoVersion=observation)
        assertEquals(LivePhotoProfile.VIVO_PAIR_2014,selected.selectedProfile)
        assertEquals(SupportStatus.UNKNOWN,selected.status)
        assertEquals(SelectionReason.CANDIDATE,selected.reason)
        assertFalse(selected.candidates.any { it.profile==LivePhotoProfile.VIVO_PAIR_LEGACY })
        assertTrue(selected.candidates.any { it.profile==LivePhotoProfile.VIVO_PAIR_2104 })
        assertEquals(LivePhotoProfile.GOOGLE,resolver.resolve(TargetBrand.VIVO,manual=LivePhotoProfile.GOOGLE,vivoVersion=observation).selectedProfile)
        for (brand in TargetBrand.values().filter { it!=TargetBrand.VIVO }) {
            assertEquals(ProfileResolver.candidates(brand),ProfileResolver.candidates(brand,vivoVersion=observation))
        }
        // No local-device observation is available for an explicit remote target.
        assertEquals(LivePhotoProfile.VIVO_SINGLE,ProfileResolver.candidates(TargetBrand.VIVO).first())
    }
    @Test fun explicitGalleryFeedbackStillWinsAndFailedProfilesAreSkipped() {
        val store=Store(); val resolver=ProfileResolver(store) { 100L }
        val env=TargetEnvironment("vivo","vivo","phone","build",35,"gallery",1,"settings")
        val observation=VivoVersionObservation.read { "0" }
        store.write(CompatibilityRecord(env.key,LivePhotoProfile.VIVO_PAIR_2104,SupportStatus.SUPPORTED,50,"0".repeat(64)))
        assertEquals(LivePhotoProfile.VIVO_PAIR_2104,resolver.resolve(TargetBrand.VIVO,env,vivoVersion=observation).selectedProfile)
        store.clear()
        store.write(CompatibilityRecord(env.key,LivePhotoProfile.VIVO_PAIR_2014,SupportStatus.UNSUPPORTED,50,"0".repeat(64)))
        assertEquals(LivePhotoProfile.VIVO_SINGLE,resolver.resolve(TargetBrand.VIVO,env,vivoVersion=observation).selectedProfile)
    }
}
