package com.vr2sbs.app

/**
 * Keeps a video playback session alive across a glasses disconnection.
 *
 * When the glasses drop off while a video is playing, the player stays on
 * screen and pauses. The position at that moment is remembered; once the
 * glasses are back and the external surface is ready again, playback seeks to
 * that position and resumes without the user selecting the file again.
 */
class GlassesReconnectPolicy {
    data class ResumePlan(
        val positionMs: Long,
        val sourceKey: String
    )

    var isWaitingForReconnect: Boolean = false
        private set

    /**
     * The position to continue from once the glasses return, or null when
     * playback should stay paused (the user had already paused before the
     * disconnect, or cancelled the automatic resume).
     */
    var pendingResumePositionMs: Long? = null
        private set

    private var pendingSourceKey: String? = null

    fun onGlassesLost(
        playbackInProgress: Boolean,
        positionMs: Long,
        sourceKey: String
    ) {
        isWaitingForReconnect = true
        pendingSourceKey = sourceKey
        pendingResumePositionMs = if (playbackInProgress) {
            positionMs.coerceAtLeast(0L)
        } else {
            null
        }
    }

    /**
     * Leaves the waiting state. Returns the resume plan only when an automatic
     * resume was pending for the same source.
     */
    fun onGlassesReturned(sourceKey: String): ResumePlan? {
        if (!isWaitingForReconnect) {
            return null
        }
        val resumePositionMs = pendingResumePositionMs
        val armedSourceKey = pendingSourceKey
        clear()
        if (resumePositionMs == null || armedSourceKey != sourceKey) {
            return null
        }
        return ResumePlan(positionMs = resumePositionMs, sourceKey = sourceKey)
    }

    fun onUserSeek(positionMs: Long) {
        if (!isWaitingForReconnect || pendingResumePositionMs == null) {
            return
        }
        pendingResumePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun onUserPause() {
        if (!isWaitingForReconnect) {
            return
        }
        pendingResumePositionMs = null
    }

    fun onUserPlay(positionMs: Long) {
        if (!isWaitingForReconnect) {
            return
        }
        pendingResumePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun cancel() {
        clear()
    }

    private fun clear() {
        isWaitingForReconnect = false
        pendingResumePositionMs = null
        pendingSourceKey = null
    }
}
