package com.dmer.neoreaderrecords

import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRefreshReasonPolicyTest {
    @Test
    fun onlyNetworkSafeEventsTriggerRemoteRefresh() {
        assertTrue(AutoRefreshReasonPolicy.isRemoteTrigger("screen_on_prewarm"))
        assertTrue(AutoRefreshReasonPolicy.isRemoteTrigger("user_present_prewarm"))
        assertTrue(AutoRefreshReasonPolicy.isRemoteTrigger("daily_alarm"))
        assertFalse(AutoRefreshReasonPolicy.isRemoteTrigger("book_content_changed"))
        assertFalse(AutoRefreshReasonPolicy.isRemoteTrigger("reading_stats_changed"))
        assertFalse(AutoRefreshReasonPolicy.isRemoteTrigger("screen_off"))
    }

    @Test
    fun localAndRemoteWorkCannotReplaceEachOther() {
        assertNotEquals(
            AutoRefreshReasonPolicy.LOCAL_WORK_NAME,
            AutoRefreshReasonPolicy.REMOTE_WORK_NAME
        )
    }

    @Test
    fun providerChangesUseSettleDelay() {
        assertTrue(AutoRefreshReasonPolicy.needsLocalSettleDelay("book_content_changed"))
        assertTrue(AutoRefreshReasonPolicy.needsLocalSettleDelay("reading_stats_changed"))
        assertFalse(AutoRefreshReasonPolicy.needsLocalSettleDelay("screen_off"))
    }

    @Test
    fun screenStateReceiverIsExportedForSystemBroadcasts() {
        assertEquals(
            ContextCompat.RECEIVER_EXPORTED,
            AutoRefreshReasonPolicy.screenStateReceiverFlags()
        )
    }

    @Test
    fun contentChangesCanRefreshAnUnchangedCover() {
        assertTrue(
            AutoRefreshReasonPolicy.shouldRefreshUnchangedCover(
                "COVER",
                "book_content_changed",
                false
            )
        )
        assertFalse(
            AutoRefreshReasonPolicy.shouldRefreshUnchangedCover(
                "COVER",
                "screen_off",
                true
            )
        )
        assertTrue(
            AutoRefreshReasonPolicy.shouldRefreshUnchangedCover(
                "AUTO_COVER",
                "screen_off",
                true
            )
        )
    }
}
