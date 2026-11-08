package dev.filmroom.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArchivedPhotoGrantTransactionHostTest {
    @Test fun falseResultReleasesNewGrant() {
        var releases=0
        val result=runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{false},{false},{releases++}) }
        assertFalse(result);assertEquals(1,releases)
    }
    @Test fun exceptionBeforeCommitReleasesNewGrantAndPropagates() {
        var releases=0
        try {
            runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{false},{throw IllegalStateException("test")},{releases++}) }
            fail("Expected callback exception")
        } catch(_:IllegalStateException) {}
        assertEquals(1,releases)
    }
    @Test fun cancellationBeforeCommitReleasesNewGrantAndPropagates() {
        var releases=0
        try {
            runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{false},{throw CancellationException("test")},{releases++}) }
            fail("Expected cancellation")
        } catch(_:CancellationException) {}
        assertEquals(1,releases)
    }
    @Test fun cancellationAfterDurableCommitKeepsGrant() {
        var linked=false;var releases=0
        try {
            runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{linked},{linked=true;throw CancellationException("after atomic write")},{releases++}) }
            fail("Expected cancellation")
        } catch(_:CancellationException) {}
        assertTrue(linked);assertEquals(0,releases)
    }
    @Test fun existingGrantAndSuccessfulCommitNeverRevoke() {
        var releases=0
        assertFalse(runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(true,{false},{false},{releases++}) })
        assertTrue(runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{false},{true},{releases++}) })
        assertEquals(0,releases)
    }
    @Test fun unreadableDurableStateAndReleaseFailureDoNotMaskCancellation() {
        var releases=0
        try {
            runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{error("read failed")},{throw CancellationException("cancelled")},{releases++}) }
        } catch(_:CancellationException) {}
        assertEquals(0,releases)
        try {
            runBlocking { ArchivedPhotoAccessUi.finishConfirmedRelink(false,{false},{throw CancellationException("cancelled")},{error("release failed")}) }
            fail("Expected cancellation")
        } catch(_:CancellationException) {}
    }
}
