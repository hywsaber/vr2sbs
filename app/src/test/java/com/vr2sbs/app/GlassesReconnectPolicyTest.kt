package com.vr2sbs.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesReconnectPolicyTest {
    @Test
    fun losingGlassesWhilePlayingArmsResumeAtThatPosition() {
        val policy = GlassesReconnectPolicy()

        policy.onGlassesLost(
            playbackInProgress = true,
            positionMs = 93_000L,
            sourceKey = "source-a"
        )

        assertTrue(policy.isWaitingForReconnect)
        assertEquals(93_000L, policy.pendingResumePositionMs)
    }

    @Test
    fun losingGlassesWhilePausedKeepsWaitingWithoutAutoResume() {
        val policy = GlassesReconnectPolicy()

        policy.onGlassesLost(
            playbackInProgress = false,
            positionMs = 12_000L,
            sourceKey = "source-a"
        )

        assertTrue(policy.isWaitingForReconnect)
        assertNull(policy.pendingResumePositionMs)
        assertNull(policy.onGlassesReturned("source-a"))
        assertFalse(policy.isWaitingForReconnect)
    }

    @Test
    fun returningGlassesReturnsArmedPositionAndClearsState() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, 45_000L, "source-a")

        val plan = policy.onGlassesReturned("source-a")

        assertNotNull(plan)
        assertEquals(45_000L, plan?.positionMs)
        assertEquals("source-a", plan?.sourceKey)
        assertFalse(policy.isWaitingForReconnect)
        assertNull(policy.pendingResumePositionMs)
    }

    @Test
    fun returningGlassesForDifferentSourceDoesNotResume() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, 45_000L, "source-a")

        assertNull(policy.onGlassesReturned("source-b"))
        assertFalse(policy.isWaitingForReconnect)
    }

    @Test
    fun userPauseWhileWaitingCancelsAutoResume() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, 30_000L, "source-a")

        policy.onUserPause()

        assertTrue(policy.isWaitingForReconnect)
        assertNull(policy.pendingResumePositionMs)
        assertNull(policy.onGlassesReturned("source-a"))
    }

    @Test
    fun userPlayWhileWaitingArmsResumeAtCurrentPosition() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(false, 30_000L, "source-a")

        policy.onUserPlay(72_500L)

        assertEquals(72_500L, policy.pendingResumePositionMs)
        assertEquals(72_500L, policy.onGlassesReturned("source-a")?.positionMs)
    }

    @Test
    fun userSeekWhileWaitingMovesResumePosition() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, 30_000L, "source-a")

        policy.onUserSeek(120_000L)

        assertEquals(120_000L, policy.onGlassesReturned("source-a")?.positionMs)
    }

    @Test
    fun userSeekWithoutArmedResumeIsIgnored() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(false, 30_000L, "source-a")

        policy.onUserSeek(120_000L)

        assertNull(policy.pendingResumePositionMs)
    }

    @Test
    fun cancelClearsWaitingAndResume() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, 30_000L, "source-a")

        policy.cancel()

        assertFalse(policy.isWaitingForReconnect)
        assertNull(policy.pendingResumePositionMs)
        assertNull(policy.onGlassesReturned("source-a"))
    }

    @Test
    fun negativePositionsClampToZero() {
        val policy = GlassesReconnectPolicy()
        policy.onGlassesLost(true, -5_000L, "source-a")

        assertEquals(0L, policy.pendingResumePositionMs)

        policy.onUserSeek(-1L)
        assertEquals(0L, policy.onGlassesReturned("source-a")?.positionMs)
    }

    @Test
    fun userSeekAndPlayOutsideWaitingStateAreIgnored() {
        val policy = GlassesReconnectPolicy()

        policy.onUserSeek(10_000L)
        policy.onUserPlay(20_000L)
        policy.onUserPause()

        assertFalse(policy.isWaitingForReconnect)
        assertNull(policy.pendingResumePositionMs)
    }
}
