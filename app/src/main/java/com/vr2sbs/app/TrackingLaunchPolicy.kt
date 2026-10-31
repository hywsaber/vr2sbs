package com.vr2sbs.app

import io.onexr.XrSessionState

internal enum class TrackingSetupCompletionTarget {
    MAIN,
    READY
}

internal fun shouldLaunchTrackingSetupForSource(
    sessionState: XrSessionState,
    imuTrackingEnabled: Boolean = true
): Boolean {
    return imuTrackingEnabled && sessionState !is XrSessionState.Streaming
}

internal fun trackingSetupCompletionTarget(hasSource: Boolean): TrackingSetupCompletionTarget {
    return if (hasSource) {
        TrackingSetupCompletionTarget.READY
    } else {
        TrackingSetupCompletionTarget.MAIN
    }
}
