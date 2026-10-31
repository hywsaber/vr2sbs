package com.vr2sbs.app

object PlaybackResumeContract {
    const val ACTION_RESUME_PLAYBACK = "com.vr2sbs.action.RESUME_PLAYBACK"
    const val EXTRA_RESUME_REQUESTED = "com.vr2sbs.extra.RESUME_REQUESTED"

    fun isResumeRequest(
        action: String?,
        resumeRequested: Boolean
    ): Boolean {
        return action == ACTION_RESUME_PLAYBACK && resumeRequested
    }
}
