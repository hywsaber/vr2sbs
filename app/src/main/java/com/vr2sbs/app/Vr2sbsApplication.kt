package com.vr2sbs.app

import android.app.Application
import com.vr2sbs.player.AppPlaybackSessionOwner
import com.vr2sbs.player.PlaybackCoordinator
import com.vr2sbs.player.PlaybackSessionOwner
import com.vr2sbs.smb.SmbClientManager
import com.vr2sbs.smb.SmbMediaCache
import com.vr2sbs.smb.SmbProfileStore
import com.vr2sbs.tracking.OneXrTrackingSessionManager

class Vr2sbsApplication : Application() {
    val smbProfileStore: SmbProfileStore by lazy {
        SmbProfileStore(this)
    }

    val smbClientManager: SmbClientManager by lazy {
        SmbClientManager(smbProfileStore)
    }

    val smbMediaCache: SmbMediaCache by lazy {
        SmbMediaCache(this)
    }
    val trackingSessionManager: OneXrTrackingSessionManager by lazy {
        OneXrTrackingSessionManager(this)
    }

    val playbackCoordinator: PlaybackCoordinator by lazy {
        PlaybackCoordinator()
    }

    val playbackSessionOwner: PlaybackSessionOwner by lazy {
        AppPlaybackSessionOwner(playbackCoordinator)
    }

}
