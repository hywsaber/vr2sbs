package com.vr2sbs.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.util.concurrent.ListenableFuture
import com.vr2sbs.R
import com.vr2sbs.databinding.ActivityPlayerBinding
import com.vr2sbs.databinding.DialogGlassesSettingsBinding
import com.vr2sbs.databinding.DialogProjectionSettingsBinding
import com.vr2sbs.diag.DiagnosticsOverlay
import com.vr2sbs.diag.DiagnosticsState
import com.vr2sbs.diag.PlaybackDiagnosticsConfig
import com.vr2sbs.display.ActiveRoute
import com.vr2sbs.display.DisplayRouteSnapshot
import com.vr2sbs.display.DisplayRouteState
import com.vr2sbs.display.DisplayRouteStateMachine
import com.vr2sbs.display.ExternalDisplayController
import com.vr2sbs.display.ExternalGlPresentation
import com.vr2sbs.display.PhysicalDisplayMode
import com.vr2sbs.display.RouteTarget
import com.vr2sbs.display.asDebugLabel
import com.vr2sbs.image.VrImageLoader
import com.vr2sbs.player.CodecCapabilityProbe
import com.vr2sbs.player.PlaybackSessionOwner
import com.vr2sbs.player.VrPlaybackService
import com.vr2sbs.render.ProjectionMode
import com.vr2sbs.render.RenderMode
import com.vr2sbs.render.VrSbsRenderer
import com.vr2sbs.source.SourceDescriptor
import com.vr2sbs.source.SourcePlaylistItem
import com.vr2sbs.source.SourceType
import com.vr2sbs.tracking.OneXrTrackingSessionManager
import com.vr2sbs.tracking.PoseState
import com.vr2sbs.tracking.RuntimePoseController
import com.vr2sbs.tracking.TOUCHPAD_DRAG_FULL_TRAVEL_RADIANS
import io.onexr.XrBiasState
import io.onexr.XrSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playbackSession: PlaybackSessionOwner
    private lateinit var diagnosticsOverlay: DiagnosticsOverlay
    private lateinit var displayController: ExternalDisplayController
    private lateinit var internalRenderer: VrSbsRenderer
    private lateinit var trackingManager: OneXrTrackingSessionManager
    private lateinit var routeBinding: PlayerRouteBinding

    private val routeStateMachine = DisplayRouteStateMachine()
    private var routeState: DisplayRouteState = DisplayRouteState.NoOutput
    private val glassesReconnect = GlassesReconnectPolicy()
    private var lastGlassesDisplayPresent: Boolean? = null

    private var decoderSummary: String = "unknown"
    private var internalSurface: Surface? = null
    private var externalSurface: Surface? = null
    private var externalPresentation: ExternalGlPresentation? = null
    private var externalPresentationModeSignature: DisplayModeSignature? = null
    private var externalPresentationToken: Long = 0L
    private var externalPresentationShownAtElapsedRealtimeMs: Long = 0L
    private var outputRebuildPlaybackPreserved = false
    private var outputRebuildDeadlineElapsedRealtimeMs: Long = 0L
    private var latestPose: PoseState = PoseState()
    private lateinit var runtimePoseController: RuntimePoseController
    private var trackingSummary: String = ""

    private var renderMode = RenderMode(perEyeFovDegrees = ProjectionFovConfig.DEFAULT_DEGREES)
    private var sbsOutputFormat = SbsOutputFormat.FULL
    private val errors by lazy { ErrorUiController(this) }
    private val projectionSettingsPreferences by lazy {
        getSharedPreferences(ProjectionFovConfig.PREFERENCES_FILE, MODE_PRIVATE)
    }

    private var latestSessionState: XrSessionState = XrSessionState.Idle
    private var latestBiasState: XrBiasState = XrBiasState.Inactive

    private var trackingSessionStateJob: Job? = null
    private var trackingBiasStateJob: Job? = null
    private var trackingPoseJob: Job? = null
    private var playbackSessionStateJob: Job? = null
    private var externalReconnectWatchdogJob: Job? = null
    private var pausedFrameContinuityJob: Job? = null
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var playbackProgressJob: Job? = null
    private var isTimelineScrubbing = false
    private var isTouchpadScaling = false
    private var pendingTimelinePositionMs = 0L
    private var bufferingDialog: AlertDialog? = null
    private var bufferingPromptJob: Job? = null
    private var bufferingPromptDismissedForCurrentStall = false
    private var playbackHasStartedForCurrentSource = false
    private var projectionFovSliderOnChangeCount = 0
    private var lastTouchpadX = 0f
    private var lastTouchpadY = 0f
    private var launchRequest = PlayerLaunchRequest(
        source = null,
        resumeExisting = false
    )
    private var isImageMode = false
    private var imageBaseSource: SourceDescriptor? = null
    private var imagePlaylist: List<SourcePlaylistItem> = emptyList()
    private var imageIndex = 0
    private var currentImageBitmap: Bitmap? = null
    private var currentImageSideBySide = false
    private var imageLoadJob: Job? = null
    private var imageLoadGeneration = 0L

    private val mediaControllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playbackHasStartedForCurrentSource = true
                hideBufferingPrompt()
            }
            updatePlayPauseButton()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE) {
                playbackHasStartedForCurrentSource = false
            }
            handleBufferingState(playbackState)
            refreshTimelineUi()
            updatePlayPauseButton()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                scheduleBufferingPromptIfNeeded()
            } else {
                hideBufferingPrompt()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                refreshTimelineUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runtimePoseController = RuntimePoseController(
            initialSensitivity = DEFAULT_IMU_SENSITIVITY,
            initialImuTrackingEnabled = ImuTrackingSettings.isEnabled(this)
        )

        val app = application as Vr2sbsApplication
        trackingManager = app.trackingSessionManager
        playbackSession = app.playbackSessionOwner
        routeBinding = PlayerRouteBinding(playbackSession)

        displayController = ExternalDisplayController(
            context = this,
            listener = object : ExternalDisplayController.Listener {
                override fun onModeChanged(mode: PhysicalDisplayMode?) {
                    runOnUiThread {
                        if (!canProcessRouting("display-mode-changed")) {
                            return@runOnUiThread
                        }
                        handleGlassesDisplayChanged(present = mode != null)
                        syncExternalPresentation()
                        updateRouting("display-mode-changed")
                    }
                }
            }
        )

        diagnosticsOverlay = DiagnosticsOverlay(this)
        binding.diagnosticsContainer.addView(diagnosticsOverlay)
        binding.diagnosticsContainer.visibility = View.GONE

        launchRequest = parseLaunchRequest(intent)
        val resolvedSource = launchRequest.source
        if (resolvedSource == null) {
            finish()
            return
        }
        val initialFovDegrees = savedInstanceState
            ?.getFloat(STATE_PER_EYE_FOV_DEGREES, ProjectionFovConfig.DEFAULT_DEGREES)
            ?: ProjectionFovConfig.DEFAULT_DEGREES
        sbsOutputFormat = loadPersistedSbsOutputFormat()
        renderMode = renderMode.copy(
            projectionMode = loadPersistedProjectionMode(),
            perEyeFovDegrees = ProjectionFovConfig.normalizeDegrees(initialFovDegrees),
            fisheyeSourceFovDegrees = loadPersistedFisheyeSourceFov(),
            packedHalfSbs = sbsOutputFormat == SbsOutputFormat.HALF
        )

        decoderSummary = CodecCapabilityProbe().findHevcDecoderSummary()

        internalRenderer = VrSbsRenderer(
            listener = object : VrSbsRenderer.Listener {
                override fun onVideoSurfaceReady(surface: Surface) {
                    runOnUiThread { onInternalSurfaceReady(surface) }
                }

                override fun onVideoSurfaceDestroyed() {
                    runOnUiThread { onInternalSurfaceDestroyed() }
                }
            }
        )

        binding.videoSurface.setEGLContextClientVersion(2)
        binding.videoSurface.setRenderer(internalRenderer)
        binding.videoSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        applyRenderModeToRenderers()
        applyRuntimePose(runtimePoseController.currentPose())

        setupControls()
        if (resolvedSource.isImage) {
            val restoredIndex = savedInstanceState?.getInt(STATE_IMAGE_INDEX, -1) ?: -1
            enterImageMode(resolvedSource, restoredIndex)
        } else {
            connectMediaController()
            val currentSessionSource = playbackSession.state.value.source
            val shouldForceReset = shouldForceSourceReset(
                hasSavedInstanceState = savedInstanceState != null,
                resumeExisting = launchRequest.resumeExisting,
                currentSourceNormalized = currentSessionSource?.normalized,
                requestedSourceNormalized = resolvedSource.normalized
            )
            playbackSession.attachSource(resolvedSource, forceReset = shouldForceReset)
        }
        updateTrackingSummary()
        updateTrackingControls()
        renderRouteStatus()
        updateDiagnostics()
    }

    override fun onStart() {
        super.onStart()
        routeBinding.onStart()
        if (!isImageMode) {
            connectMediaController()
        }
        displayController.start()
        binding.videoSurface.onResume()
        syncExternalPresentation()
        applyRenderModeToRenderers()
        applyPoseToRenderers(latestPose)
        attachTrackingCollectors()
        attachPlaybackSessionCollector()
        updateRouting("activity-start")
        updateGlassesWaitBanner()
        updateDiagnostics()
        startExternalReconnectWatchdog()
    }

    override fun onPause() {
        if (!isChangingConfigurations) {
            logRouting("onPause -> pauseForAppBackground")
            playbackSession.pauseForAppBackground()
        }
        super.onPause()
    }

    override fun onStop() {
        hideBufferingPrompt()
        outputRebuildPlaybackPreserved = false
        routeBinding.onTeardownBegin()
        displayController.stop()
        stopPlaybackProgressUpdates()
        externalReconnectWatchdogJob?.cancel()
        externalReconnectWatchdogJob = null
        pausedFrameContinuityJob?.cancel()
        pausedFrameContinuityJob = null
        playbackSessionStateJob?.cancel()
        playbackSessionStateJob = null
        logRouting("onStop teardown -> clear/dismiss")
        clearActiveSurface(force = true)
        dismissExternalPresentation()
        if (::internalRenderer.isInitialized) {
            binding.videoSurface.onPause()
        }
        super.onStop()
        if (!isChangingConfigurations) {
            releaseMediaController()
        }
        trackingSessionStateJob?.cancel()
        trackingSessionStateJob = null
        trackingBiasStateJob?.cancel()
        trackingBiasStateJob = null
        trackingPoseJob?.cancel()
        trackingPoseJob = null
    }

    override fun onDestroy() {
        hideBufferingPrompt()
        imageLoadJob?.cancel()
        imageLoadJob = null
        imageLoadGeneration += 1L
        currentImageBitmap = null
        routeBinding.onTeardownBegin()
        clearActiveSurface(force = true)
        dismissExternalPresentation()
        releaseMediaController()
        if (::internalRenderer.isInitialized) {
            internalRenderer.release()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(STATE_PER_EYE_FOV_DEGREES, renderMode.perEyeFovDegrees)
        if (isImageMode) {
            outState.putInt(STATE_IMAGE_INDEX, imageIndex)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest = parseLaunchRequest(intent)
        val resolvedSource = launchRequest.source ?: return
        if (!::playbackSession.isInitialized) {
            return
        }
        if (resolvedSource.isImage) {
            enterImageMode(resolvedSource, restoredIndex = -1)
            return
        }
        exitImageMode()
        val currentSessionSource = playbackSession.state.value.source
        if (currentSessionSource?.playbackKey != resolvedSource.playbackKey) {
            glassesReconnect.cancel()
            updateGlassesWaitBanner()
        }
        val shouldForceReset = shouldForceSourceReset(
            hasSavedInstanceState = false,
            resumeExisting = launchRequest.resumeExisting,
            currentSourceNormalized = currentSessionSource?.normalized,
            requestedSourceNormalized = resolvedSource.normalized
        )
        playbackSession.attachSource(resolvedSource, forceReset = shouldForceReset)
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val STATE_PER_EYE_FOV_DEGREES = "state_per_eye_fov_degrees"
        private const val STATE_IMAGE_INDEX = "state_image_index"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_RESUME_EXISTING = "extra_resume_existing"
    }

    private fun parseLaunchRequest(intent: Intent?): PlayerLaunchRequest {
        if (intent == null) {
            return PlayerLaunchRequest(source = null, resumeExisting = false)
        }
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SOURCE, SourceDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SOURCE)
        }
        return PlayerLaunchRequest(
            source = source,
            resumeExisting = intent.getBooleanExtra(EXTRA_RESUME_EXISTING, false)
        )
    }

    private fun connectMediaController() {
        if (mediaControllerFuture != null) {
            return
        }
        val token = SessionToken(this, ComponentName(this, VrPlaybackService::class.java))
        val connectionHints = Bundle().apply {
            putBoolean(VrPlaybackService.CONTROLLER_HINT_ALLOW_APP_PLAYBACK, true)
        }
        val future = MediaController.Builder(this, token)
            .setConnectionHints(connectionHints)
            .buildAsync()
        mediaControllerFuture = future
        future.addListener(
            {
                if (mediaControllerFuture !== future) {
                    MediaController.releaseFuture(future)
                    return@addListener
                }
                runCatching { future.get() }
                    .onSuccess { controller ->
                        mediaController?.removeListener(mediaControllerListener)
                        mediaController = controller
                        controller.addListener(mediaControllerListener)
                        refreshTimelineUi()
                        updatePlayPauseButton()
                        startPlaybackProgressUpdates()
                    }
                    .onFailure { error ->
                        val detail = error.message ?: getString(R.string.error_unknown)
                        errors.show(getString(R.string.error_connect_playback_service, detail))
                    }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun releaseMediaController() {
        mediaController?.removeListener(mediaControllerListener)
        mediaController = null
        stopPlaybackProgressUpdates()
        val future = mediaControllerFuture ?: return
        mediaControllerFuture = null
        MediaController.releaseFuture(future)
    }

    private fun setupControls() {
        binding.glassesSettingsButton.setOnClickListener { showGlassesSettingsPopup() }
        binding.projectionSettingsButton.setOnClickListener { showProjectionSettingsPopup() }
        binding.fovDecreaseButton.setOnClickListener {
            adjustProjectionFovByStep(-ProjectionFovConfig.STEP_DEGREES)
        }
        binding.fovIncreaseButton.setOnClickListener {
            adjustProjectionFovByStep(ProjectionFovConfig.STEP_DEGREES)
        }
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        setupTimeline()
        setupTouchpad()
        configureVideoModeUi()
        refreshTimelineUi()
        updatePlayPauseButton()
    }

    private fun setupTimeline() {
        binding.timelineSlider.clearOnChangeListeners()
        binding.timelineSlider.clearOnSliderTouchListeners()
        binding.timelineSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) {
                return@addOnChangeListener
            }
            pendingTimelinePositionMs = value.toLong()
            binding.currentTimeText.text = formatPlaybackTime(pendingTimelinePositionMs)
        }
        binding.timelineSlider.addOnSliderTouchListener(
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    isTimelineScrubbing = true
                    pendingTimelinePositionMs = slider.value.toLong()
                    binding.currentTimeText.text = formatPlaybackTime(pendingTimelinePositionMs)
                }

                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    pendingTimelinePositionMs = slider.value.toLong()
                    isTimelineScrubbing = false
                    seekToTimelinePosition()
                }
            }
        )
    }

    private val touchpadGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                runRecenter()
                return true
            }
        })
    }

    private val touchpadScaleGestureDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyProjectionFov(
                    ProjectionFovConfig.fovAfterPinchScale(
                        currentDegrees = renderMode.perEyeFovDegrees,
                        scaleFactor = detector.scaleFactor
                    )
                )
                return true
            }
        })
    }

    private fun setupTouchpad() {
        binding.touchpadArea.post {
            resetTouchpadIndicator(animate = false)
        }
        binding.touchpadArea.setOnTouchListener { _, event ->
            if (event.pointerCount >= 2 || isTouchpadScaling) {
                touchpadScaleGestureDetector.onTouchEvent(event)
            } else {
                touchpadGestureDetector.onTouchEvent(event)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouchpadScaling = false
                    lastTouchpadX = event.x
                    lastTouchpadY = event.y
                    moveTouchpadIndicator(event.x, event.y)
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!isTouchpadScaling) {
                        applyRuntimePose(runtimePoseController.commitTouchpadBias())
                    }
                    isTouchpadScaling = true
                    resetTouchpadIndicator(animate = true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTouchpadScaling || event.pointerCount >= 2) {
                        isTouchpadScaling = true
                        return@setOnTouchListener true
                    }
                    applyTouchpadDragDelta(
                        deltaX = event.x - lastTouchpadX,
                        deltaY = event.y - lastTouchpadY
                    )
                    lastTouchpadX = event.x
                    lastTouchpadY = event.y
                    moveTouchpadIndicator(event.x, event.y)
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    isTouchpadScaling = true
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isTouchpadScaling) {
                        applyRuntimePose(runtimePoseController.commitTouchpadBias())
                    }
                    isTouchpadScaling = false
                    resetTouchpadIndicator(animate = true)
                    true
                }

                else -> false
            }
        }
    }

    private fun moveTouchpadIndicator(rawX: Float, rawY: Float) {
        val area = binding.touchpadArea
        val indicator = binding.touchpadIndicator
        val maxOffsetX = ((area.width - indicator.width) / 2f).coerceAtLeast(0f)
        val maxOffsetY = ((area.height - indicator.height) / 2f).coerceAtLeast(0f)
        val centeredX = (rawX - area.width / 2f).coerceIn(-maxOffsetX, maxOffsetX)
        val centeredY = (rawY - area.height / 2f).coerceIn(-maxOffsetY, maxOffsetY)
        indicator.animate().cancel()
        indicator.translationX = centeredX
        indicator.translationY = centeredY
        indicator.alpha = 1f
    }

    private fun applyTouchpadDragDelta(deltaX: Float, deltaY: Float) {
        val area = binding.touchpadArea
        val indicator = binding.touchpadIndicator
        val maxOffsetX = ((area.width - indicator.width) / 2f).coerceAtLeast(0f)
        val maxOffsetY = ((area.height - indicator.height) / 2f).coerceAtLeast(0f)
        if (maxOffsetX <= 0f || maxOffsetY <= 0f) {
            return
        }
        val yawDelta = (deltaX / maxOffsetX) * TOUCHPAD_DRAG_FULL_TRAVEL_RADIANS
        val pitchDelta = (deltaY / maxOffsetY) * TOUCHPAD_DRAG_FULL_TRAVEL_RADIANS
        applyRuntimePose(runtimePoseController.applyTouchpadBiasDelta(yawDelta, pitchDelta))
    }

    private fun resetTouchpadIndicator(animate: Boolean) {
        val indicator = binding.touchpadIndicator
        indicator.animate().cancel()
        if (animate) {
            indicator.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(0.78f)
                .setDuration(120L)
                .start()
            return
        }
        indicator.translationX = 0f
        indicator.translationY = 0f
        indicator.alpha = 0.78f
    }

    private fun seekBy(deltaMs: Long) {
        val controller = mediaController ?: return
        val durationMs = controller.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = (controller.currentPosition + deltaMs).coerceAtLeast(0L)
        val boundedTarget = if (durationMs != null) target.coerceAtMost(durationMs) else target
        controller.seekTo(boundedTarget)
        glassesReconnect.onUserSeek(boundedTarget)
        updateGlassesWaitBanner()
        refreshTimelineUi()
    }

    private fun enterImageMode(source: SourceDescriptor, restoredIndex: Int) {
        isImageMode = true
        glassesReconnect.cancel()
        updateGlassesWaitBanner()
        imageBaseSource = source
        imagePlaylist = if (source.playlist.isNotEmpty()) {
            source.playlist
        } else {
            listOf(
                SourcePlaylistItem(
                    uri = source.normalized,
                    displayName = source.displayName ?: source.normalized
                )
            )
        }
        imageIndex = initialImageIndex(
            playlist = imagePlaylist,
            sourceUri = source.normalized,
            restoredIndex = restoredIndex
        )
        // A video may still be playing when a photo is opened from the browser.
        playbackSession.pauseForAppBackground()
        releaseMediaController()
        configureImageModeUi()
        showImageAt(imageIndex)
    }

    private fun exitImageMode() {
        if (!isImageMode) return
        isImageMode = false
        imageLoadGeneration += 1L
        imageLoadJob?.cancel()
        imageLoadJob = null
        imageBaseSource = null
        imagePlaylist = emptyList()
        imageIndex = 0
        currentImageBitmap = null
        currentImageSideBySide = false
        if (::internalRenderer.isInitialized) {
            internalRenderer.setStaticImage(null)
        }
        externalPresentation?.setStaticImage(null)
        configureVideoModeUi()
        connectMediaController()
    }

    private fun configureImageModeUi() {
        binding.playerTimelineRow.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.seekBackButton.contentDescription =
            getString(R.string.player_previous_photo_content_description)
        binding.seekForwardButton.contentDescription =
            getString(R.string.player_next_photo_content_description)
        binding.seekBackButton.setOnClickListener { showPreviousImage() }
        binding.seekForwardButton.setOnClickListener { showNextImage() }
        updateImageNavigationButtons()
    }

    private fun configureVideoModeUi() {
        binding.playerTimelineRow.visibility = View.VISIBLE
        binding.playPauseButton.visibility = View.VISIBLE
        binding.seekBackButton.contentDescription =
            getString(R.string.player_seek_back_content_description)
        binding.seekForwardButton.contentDescription =
            getString(R.string.player_seek_forward_content_description)
        binding.seekBackButton.setOnClickListener { seekBy(-SEEK_INTERVAL_MS) }
        binding.seekForwardButton.setOnClickListener { seekBy(SEEK_INTERVAL_MS) }
        binding.seekBackButton.isEnabled = true
        binding.seekForwardButton.isEnabled = true
    }

    private fun showPreviousImage() {
        if (imageIndex > 0) {
            showImageAt(imageIndex - 1)
        }
    }

    private fun showNextImage() {
        if (imageIndex < imagePlaylist.lastIndex) {
            showImageAt(imageIndex + 1)
        }
    }

    private fun currentImageSource(): SourceDescriptor? {
        val baseSource = imageBaseSource ?: return null
        val item = imagePlaylist.getOrNull(imageIndex) ?: return baseSource
        return baseSource.copy(
            original = item.uri,
            normalized = item.uri,
            displayName = item.displayName
        )
    }

    private fun updateImageNavigationButtons() {
        binding.seekBackButton.isEnabled = imageIndex > 0
        binding.seekForwardButton.isEnabled = imageIndex < imagePlaylist.lastIndex
    }

    private fun showImageAt(index: Int) {
        val item = imagePlaylist.getOrNull(index) ?: return
        val baseSource = imageBaseSource ?: return
        imageIndex = index
        updateImageNavigationButtons()
        val imageSource = baseSource.copy(
            original = item.uri,
            normalized = item.uri,
            displayName = item.displayName
        )
        imageLoadJob?.cancel()
        val generation = ++imageLoadGeneration
        imageLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    VrImageLoader.load(
                        context = this@PlayerActivity,
                        smbClientManager = (application as Vr2sbsApplication).smbClientManager,
                        source = imageSource
                    )
                }
            }
            if (!isActive || generation != imageLoadGeneration || !isImageMode) {
                return@launch
            }
            result
                .onSuccess { loaded ->
                    currentImageBitmap = loaded.bitmap
                    currentImageSideBySide = loaded.sideBySide
                    internalRenderer.setStaticImage(loaded.bitmap, loaded.sideBySide)
                    externalPresentation?.setStaticImage(loaded.bitmap, loaded.sideBySide)
                }
                .onFailure { error ->
                    currentImageBitmap = null
                    currentImageSideBySide = false
                    internalRenderer.setStaticImage(null)
                    externalPresentation?.setStaticImage(null)
                    Toast.makeText(
                        this@PlayerActivity,
                        getString(
                            R.string.player_image_load_failed,
                            error.message ?: getString(R.string.error_unknown)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun togglePlayPause() {
        val controller = mediaController ?: return
        if (glassesReconnect.isWaitingForReconnect) {
            // While waiting for the glasses, play/pause only controls whether
            // playback should continue once the output is back.
            if (glassesReconnect.pendingResumePositionMs != null) {
                glassesReconnect.onUserPause()
            } else {
                glassesReconnect.onUserPlay(controller.currentPosition)
            }
            updateGlassesWaitBanner()
            updatePlayPauseButton()
            return
        }
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
        updatePlayPauseButton()
    }

    private fun seekToTimelinePosition() {
        val controller = mediaController ?: return
        val durationMs = controller.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = if (durationMs != null) {
            pendingTimelinePositionMs.coerceIn(0L, durationMs)
        } else {
            pendingTimelinePositionMs.coerceAtLeast(0L)
        }
        controller.seekTo(target)
        glassesReconnect.onUserSeek(target)
        updateGlassesWaitBanner()
        refreshTimelineUi()
    }

    private fun refreshTimelineUi() {
        val controller = mediaController
        if (controller == null) {
            binding.timelineSlider.isEnabled = false
            if (binding.timelineSlider.valueTo != 1f) {
                binding.timelineSlider.valueTo = 1f
            }
            if (!isTimelineScrubbing) {
                binding.timelineSlider.value = 0f
            }
            binding.currentTimeText.text = getString(R.string.player_default_time)
            binding.durationTimeText.text = getString(R.string.player_default_time)
            return
        }

        val durationMs = controller.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val currentPositionMsRaw = controller.currentPosition.coerceAtLeast(0L)
        val currentPositionMs = if (durationMs > 0L) {
            currentPositionMsRaw.coerceAtMost(durationMs)
        } else {
            currentPositionMsRaw
        }
        if (binding.timelineSlider.valueTo != safeDurationMs.toFloat()) {
            binding.timelineSlider.valueTo = safeDurationMs.toFloat()
        }
        binding.timelineSlider.isEnabled = durationMs > 0L
        if (!isTimelineScrubbing) {
            pendingTimelinePositionMs = currentPositionMs
            val sliderValue = currentPositionMs.coerceIn(0L, safeDurationMs).toFloat()
            if (binding.timelineSlider.value != sliderValue) {
                binding.timelineSlider.value = sliderValue
            }
        }
        val displayedPositionMs = if (isTimelineScrubbing) pendingTimelinePositionMs else currentPositionMs
        binding.currentTimeText.text = formatPlaybackTime(displayedPositionMs)
        binding.durationTimeText.text = formatPlaybackTime(durationMs)
    }

    private fun updatePlayPauseButton() {
        val isPlaying = mediaController?.isPlaying == true
        val autoResumePending = glassesReconnect.pendingResumePositionMs != null
        val showPause = isPlaying || autoResumePending
        val iconRes = if (showPause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val contentDescription = if (showPause) {
            getString(R.string.player_pause_content_description)
        } else {
            getString(R.string.player_play_content_description)
        }
        binding.playPauseButton.setIconResource(iconRes)
        binding.playPauseButton.contentDescription = contentDescription
    }

    private fun handleBufferingState(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) {
            scheduleBufferingPromptIfNeeded()
        } else {
            hideBufferingPrompt()
        }
    }

    private fun scheduleBufferingPromptIfNeeded() {
        if (bufferingPromptDismissedForCurrentStall || bufferingDialog != null) return
        if (bufferingPromptJob?.isActive == true) return
        val controller = mediaController ?: return
        if (controller.playbackState != Player.STATE_BUFFERING || !controller.playWhenReady) return
        if (!isNetworkPlaybackSource()) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val promptDelayMs = if (playbackHasStartedForCurrentSource) {
            BUFFERING_PROMPT_DELAY_MS
        } else {
            INITIAL_BUFFERING_PROMPT_DELAY_MS
        }
        bufferingPromptJob = lifecycleScope.launch {
            delay(promptDelayMs)
            if (!isActive) return@launch
            bufferingPromptJob = null
            val latest = mediaController ?: return@launch
            if (latest.playbackState != Player.STATE_BUFFERING || !latest.playWhenReady) {
                return@launch
            }
            if (bufferingPromptDismissedForCurrentStall || bufferingDialog != null) {
                return@launch
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@launch
            }
            showBufferingDialog()
        }
    }

    private fun isNetworkPlaybackSource(): Boolean = when (
        playbackSession.state.value.source?.type
    ) {
        SourceType.SMB_URI, SourceType.HTTP_URL -> true
        else -> false
    }

    private fun showBufferingDialog() {
        if (isFinishing || isDestroyed || bufferingDialog != null) return
        val dialog = createBufferingDialog(this)
        dialog.setOnDismissListener {
            bufferingDialog = null
            bufferingPromptDismissedForCurrentStall = true
            bufferingPromptJob?.cancel()
            bufferingPromptJob = null
        }
        bufferingDialog = dialog
        dialog.show()
        Log.i(TAG, "Network buffering prompt shown")
        applyPlayerSettingsDialogStyle(dialog)
    }

    private fun hideBufferingPrompt() {
        bufferingPromptJob?.cancel()
        bufferingPromptJob = null
        val dialog = bufferingDialog
        bufferingDialog = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        bufferingPromptDismissedForCurrentStall = false
    }

    private fun startPlaybackProgressUpdates() {
        if (playbackProgressJob?.isActive == true) {
            return
        }
        playbackProgressJob = lifecycleScope.launch {
            while (isActive) {
                refreshTimelineUi()
                delay(PLAYBACK_PROGRESS_UPDATE_MS)
            }
        }
    }

    private fun stopPlaybackProgressUpdates() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
    }

    private fun showGlassesSettingsPopup() {
        val dialogBinding = DialogGlassesSettingsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.glasses_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.player_settings_close, null)
            .create()

        bindImuSensitivitySlider(
            slider = dialogBinding.imuSensitivitySlider,
            valueLabel = dialogBinding.imuSensitivityValueText
        )
        bindImuTrackingToggle(
            toggle = dialogBinding.imuTrackingSwitch,
            row = dialogBinding.imuTrackingRow,
            onEnableRequested = {
                dialog.dismiss()
                launchCalibrationSetup()
            }
        )

        dialogBinding.runCalibrationButton.setOnClickListener {
            runCalibration()
            dialog.dismiss()
        }

        dialog.show()
        applyPlayerSettingsDialogStyle(dialog)
    }

    private fun bindImuSensitivitySlider(slider: Slider, valueLabel: TextView) {
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.value = runtimePoseController.imuSensitivity()
        valueLabel.text = formatImuSensitivityValue(runtimePoseController.imuSensitivity())
        slider.addOnChangeListener { _, value, fromUser ->
            valueLabel.text = formatImuSensitivityValue(value)
            if (!fromUser) {
                return@addOnChangeListener
            }
            applyRuntimePose(runtimePoseController.setImuSensitivity(value))
        }
    }

    private fun bindImuTrackingToggle(
        toggle: SwitchMaterial,
        row: View,
        onEnableRequested: () -> Unit
    ) {
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = runtimePoseController.isImuTrackingEnabled()
        row.setOnClickListener { toggle.toggle() }
        toggle.setOnCheckedChangeListener { _, isChecked ->
            ImuTrackingSettings.setEnabled(this, isChecked)
            if (isChecked) {
                onEnableRequested()
            } else {
                applyRuntimePose(runtimePoseController.setImuTrackingEnabled(false))
            }
        }
    }

    /**
     * Enabling IMU tracking requires the calibration screen again. The active
     * playback session is preserved so the player can resume the same source.
     */
    private fun launchCalibrationSetup() {
        val source = if (isImageMode) {
            currentImageSource() ?: launchRequest.source
        } else {
            playbackSession.state.value.source ?: launchRequest.source
        }
        val intent = Intent(this, TrackingSetupActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_RESUME_EXISTING, true)
        if (source != null) {
            intent.putExtra(PlayerActivity.EXTRA_SOURCE, source)
        }
        startActivity(intent)
        finish()
    }

    private fun showProjectionSettingsPopup() {
        Log.i(TAG, "Opening projection settings dialog")
        val dialogBinding = DialogProjectionSettingsBinding.inflate(layoutInflater)
        dialogBinding.projectionOptionsGroup.check(checkedIdForProjectionMode(renderMode.projectionMode))
        updateFisheyeSourceFovVisibility(dialogBinding)
        dialogBinding.projectionOptionsGroup.setOnCheckedChangeListener { _, checkedId ->
            applyProjectionMode(projectionModeForCheckedId(checkedId))
            updateFisheyeSourceFovVisibility(dialogBinding)
        }
        bindSbsOutputFormatGroup(dialogBinding.projectionSbsFormatGroup)
        bindProjectionFovSlider(
            slider = dialogBinding.projectionFovSlider,
            valueLabel = dialogBinding.projectionFovValueText
        )
        bindFisheyeSourceFovSlider(
            slider = dialogBinding.projectionLensFovSlider,
            valueLabel = dialogBinding.projectionLensFovValueText
        )
        dialogBinding.projectionResetFovButton.setOnClickListener {
            applyProjectionFov(ProjectionFovConfig.DEFAULT_DEGREES)
            val normalized = ProjectionFovConfig.normalizeDegrees(renderMode.perEyeFovDegrees)
            dialogBinding.projectionFovSlider.value = normalized
            dialogBinding.projectionFovValueText.text = formatProjectionFovValue(normalized)
            Log.i(TAG, "Projection FOV reset to default: $normalized")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.projection_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.player_settings_close, null)
            .create()
        dialog.show()
        applyPlayerSettingsDialogStyle(dialog)
    }

    private fun applyPlayerSettingsDialogStyle(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(this, R.drawable.bg_player_settings_dialog)
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { closeButton ->
            closeButton.isAllCaps = false
            closeButton.setTextColor(
                ContextCompat.getColor(this, R.color.player_dialog_close_text)
            )
        }
    }

    private fun formatImuSensitivityValue(value: Float): String {
        return getString(R.string.imu_sensitivity_value_format, value)
    }

    private fun formatProjectionFovValue(value: Float): String {
        return getString(R.string.projection_fov_value_format, value)
    }

    private fun projectionModeForCheckedId(checkedId: Int): ProjectionMode {
        return when (checkedId) {
            R.id.projectionFisheyeEquidistantOption -> ProjectionMode.VR180_FISHEYE_EQUIDISTANT
            R.id.projectionFisheyeEquisolidOption -> ProjectionMode.VR180_FISHEYE_EQUISOLID
            else -> ProjectionMode.VR180
        }
    }

    private fun checkedIdForProjectionMode(mode: ProjectionMode): Int {
        return when (mode) {
            ProjectionMode.VR180_FISHEYE_EQUIDISTANT -> R.id.projectionFisheyeEquidistantOption
            ProjectionMode.VR180_FISHEYE_EQUISOLID -> R.id.projectionFisheyeEquisolidOption
            else -> R.id.projectionHalfEquirectangularOption
        }
    }

    private fun isFisheyeProjectionMode(mode: ProjectionMode): Boolean {
        return mode == ProjectionMode.VR180_FISHEYE_EQUIDISTANT ||
            mode == ProjectionMode.VR180_FISHEYE_EQUISOLID
    }

    private fun updateFisheyeSourceFovVisibility(dialogBinding: DialogProjectionSettingsBinding) {
        dialogBinding.projectionLensFovRow.visibility =
            if (isFisheyeProjectionMode(renderMode.projectionMode)) View.VISIBLE else View.GONE
    }

    private fun bindSbsOutputFormatGroup(group: MaterialButtonToggleGroup) {
        group.clearOnButtonCheckedListeners()
        group.check(
            if (sbsOutputFormat == SbsOutputFormat.HALF) {
                R.id.projectionSbsHalfOption
            } else {
                R.id.projectionSbsFullOption
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val format = if (checkedId == R.id.projectionSbsHalfOption) {
                SbsOutputFormat.HALF
            } else {
                SbsOutputFormat.FULL
            }
            applySbsOutputFormat(format)
        }
    }

    private fun bindProjectionFovSlider(slider: Slider, valueLabel: TextView) {
        val currentFov = ProjectionFovConfig.normalizeDegrees(renderMode.perEyeFovDegrees)
        projectionFovSliderOnChangeCount = 0
        Log.i(TAG, "Projection slider init value=$currentFov")
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = ProjectionFovConfig.MIN_DEGREES
        slider.valueTo = ProjectionFovConfig.MAX_DEGREES
        slider.stepSize = 0f
        slider.value = currentFov
        valueLabel.text = formatProjectionFovValue(currentFov)
        slider.addOnChangeListener { _, value, fromUser ->
            projectionFovSliderOnChangeCount += 1
            val normalized = ProjectionFovConfig.normalizeDegrees(value)
            valueLabel.text = formatProjectionFovValue(normalized)
            Log.d(
                TAG,
                "Projection slider onChange count=$projectionFovSliderOnChangeCount, value=$normalized, fromUser=$fromUser"
            )
            if (!fromUser) {
                return@addOnChangeListener
            }
            applyProjectionFov(normalized)
        }
    }

    private fun bindFisheyeSourceFovSlider(slider: Slider, valueLabel: TextView) {
        val currentFov = ProjectionFovConfig.normalizeFisheyeSourceFovDegrees(
            renderMode.fisheyeSourceFovDegrees
        )
        slider.clearOnChangeListeners()
        slider.clearOnSliderTouchListeners()
        slider.valueFrom = ProjectionFovConfig.MIN_FISHEYE_SOURCE_FOV_DEGREES
        slider.valueTo = ProjectionFovConfig.MAX_FISHEYE_SOURCE_FOV_DEGREES
        slider.stepSize = 0f
        slider.value = currentFov
        valueLabel.text = formatProjectionFovValue(currentFov)
        slider.addOnChangeListener { _, value, fromUser ->
            val normalized = ProjectionFovConfig.normalizeFisheyeSourceFovDegrees(value)
            valueLabel.text = formatProjectionFovValue(normalized)
            if (!fromUser) {
                return@addOnChangeListener
            }
            applyFisheyeSourceFov(normalized)
        }
    }

    private fun adjustProjectionFovByStep(delta: Float) {
        val updatedValue = ProjectionFovConfig.normalizeDegrees(renderMode.perEyeFovDegrees + delta)
        applyProjectionFov(updatedValue)
        Log.i(TAG, "Projection FOV adjusted from player controls to $updatedValue")
    }

    private fun loadPersistedSbsOutputFormat(): SbsOutputFormat {
        val storedValue = projectionSettingsPreferences.getString(
            SbsOutputFormat.PREFERENCE_KEY_SBS_OUTPUT_FORMAT,
            null
        )
        val normalizedValue = SbsOutputFormat.fromPreference(storedValue)
        val normalizedPreference = SbsOutputFormat.toPreference(normalizedValue)
        if (storedValue != normalizedPreference) {
            projectionSettingsPreferences.edit()
                .putString(SbsOutputFormat.PREFERENCE_KEY_SBS_OUTPUT_FORMAT, normalizedPreference)
                .apply()
        }
        return normalizedValue
    }

    private fun loadPersistedProjectionMode(): ProjectionMode {
        val storedValue = projectionSettingsPreferences.getString(
            ProjectionFovConfig.PREFERENCE_KEY_PROJECTION_MODE,
            null
        )
        val normalizedValue = ProjectionFovConfig.projectionModeFromPreference(storedValue)
        val normalizedPreference = ProjectionFovConfig.projectionModeToPreference(normalizedValue)
        if (storedValue != normalizedPreference) {
            projectionSettingsPreferences.edit()
                .putString(ProjectionFovConfig.PREFERENCE_KEY_PROJECTION_MODE, normalizedPreference)
                .apply()
        }
        return normalizedValue
    }

    private fun loadPersistedFisheyeSourceFov(): Float {
        val storedValue = projectionSettingsPreferences.getFloat(
            ProjectionFovConfig.PREFERENCE_KEY_FISHEYE_SOURCE_FOV_DEGREES,
            ProjectionFovConfig.DEFAULT_FISHEYE_SOURCE_FOV_DEGREES
        )
        val normalizedValue = ProjectionFovConfig.normalizeFisheyeSourceFovDegrees(storedValue)
        if (storedValue != normalizedValue) {
            projectionSettingsPreferences.edit()
                .putFloat(ProjectionFovConfig.PREFERENCE_KEY_FISHEYE_SOURCE_FOV_DEGREES, normalizedValue)
                .apply()
        }
        return normalizedValue
    }

    private fun applyProjectionMode(value: ProjectionMode) {
        if (renderMode.projectionMode == value) {
            return
        }
        renderMode = renderMode.copy(projectionMode = value)
        applyRenderModeToRenderers()
        projectionSettingsPreferences.edit()
            .putString(
                ProjectionFovConfig.PREFERENCE_KEY_PROJECTION_MODE,
                ProjectionFovConfig.projectionModeToPreference(value)
            )
            .apply()
        Log.i(TAG, "Projection mode changed to $value")
    }

    private fun applyProjectionFov(value: Float) {
        val normalizedValue = ProjectionFovConfig.normalizeDegrees(value)
        if (renderMode.perEyeFovDegrees == normalizedValue) {
            return
        }
        renderMode = renderMode.copy(perEyeFovDegrees = normalizedValue)
        applyRenderModeToRenderers()
    }

    private fun applySbsOutputFormat(format: SbsOutputFormat) {
        if (sbsOutputFormat == format) {
            return
        }
        val wasExpectedPlaying = playbackSession.state.value.expectedPlayWhenReady
        sbsOutputFormat = format
        renderMode = renderMode.copy(packedHalfSbs = format == SbsOutputFormat.HALF)
        projectionSettingsPreferences.edit()
            .putString(
                SbsOutputFormat.PREFERENCE_KEY_SBS_OUTPUT_FORMAT,
                SbsOutputFormat.toPreference(format)
            )
            .apply()
        applyRenderModeToRenderers()
        if (externalPresentation != null) {
            if (wasExpectedPlaying) {
                // Rebuilding the glasses presentation temporarily removes the
                // video surface. Keep the player running instead of treating
                // that moment as an output interruption.
                outputRebuildPlaybackPreserved = true
                outputRebuildDeadlineElapsedRealtimeMs =
                    SystemClock.elapsedRealtime() + OUTPUT_REBUILD_PRESERVE_MS
                logRouting("preserve playback during SBS output rebuild")
            }
            dismissExternalPresentation()
            syncExternalPresentation()
        }
        Log.i(TAG, "SBS output format changed to $format")
    }

    private fun shouldPreservePlaybackDuringOutputRebuild(): Boolean {
        return outputRebuildPlaybackPreserved &&
            SystemClock.elapsedRealtime() < outputRebuildDeadlineElapsedRealtimeMs
    }

    private fun applyFisheyeSourceFov(value: Float) {
        val normalizedValue = ProjectionFovConfig.normalizeFisheyeSourceFovDegrees(value)
        if (renderMode.fisheyeSourceFovDegrees == normalizedValue) {
            return
        }
        renderMode = renderMode.copy(fisheyeSourceFovDegrees = normalizedValue)
        applyRenderModeToRenderers()
        projectionSettingsPreferences.edit()
            .putFloat(ProjectionFovConfig.PREFERENCE_KEY_FISHEYE_SOURCE_FOV_DEGREES, normalizedValue)
            .apply()
        Log.i(TAG, "Fisheye source FOV changed to $normalizedValue")
    }

    private fun formatPlaybackTime(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun attachTrackingCollectors() {
        trackingSessionStateJob?.cancel()
        trackingSessionStateJob = lifecycleScope.launch {
            trackingManager.sessionState.collect { state ->
                handleTrackingSessionState(state)
            }
        }

        trackingBiasStateJob?.cancel()
        trackingBiasStateJob = lifecycleScope.launch {
            trackingManager.biasState.collect { state ->
                latestBiasState = state
                updateTrackingSummary()
                updateDiagnostics()
            }
        }

        trackingPoseJob?.cancel()
        trackingPoseJob = lifecycleScope.launch {
            trackingManager.pose.collect { pose ->
                if (pose == null || !pose.trackingAvailable) {
                    return@collect
                }
                applyRuntimePose(runtimePoseController.onTrackingPoseUpdated(pose))
            }
        }
    }

    private fun startExternalReconnectWatchdog() {
        externalReconnectWatchdogJob?.cancel()
        externalReconnectWatchdogJob = lifecycleScope.launch {
            while (isActive) {
                if (!canProcessRouting("reconnect-watchdog-loop")) {
                    delay(EXTERNAL_RECONNECT_WATCHDOG_MS)
                    continue
                }
                if (routeState !is DisplayRouteState.ExternalActive) {
                    syncExternalPresentation()
                    updateRouting("reconnect-watchdog")
                }
                delay(EXTERNAL_RECONNECT_WATCHDOG_MS)
            }
        }
    }

    private fun attachPlaybackSessionCollector() {
        playbackSessionStateJob?.cancel()
        playbackSessionStateJob = lifecycleScope.launch {
            playbackSession.state.collect {
                maybeEnsurePausedFrameVisibility()
                updateDiagnostics()
            }
        }
    }

    private fun handleTrackingSessionState(state: XrSessionState) {
        latestSessionState = state
        updateTrackingControls()
        updateTrackingSummary()
        updateDiagnostics()
    }

    private fun updateTrackingControls() {
    }

    private fun updateTrackingSummary() {
        trackingSummary = when (val state = latestSessionState) {
            XrSessionState.Idle,
            XrSessionState.Stopped -> getString(R.string.tracking_state_manual)

            XrSessionState.Connecting -> getString(R.string.tracking_state_connecting)

            is XrSessionState.Calibrating -> getString(
                R.string.tracking_state_calibrating,
                state.calibrationSampleCount,
                state.calibrationTarget
            )

            is XrSessionState.Streaming -> getString(R.string.tracking_state_streaming)

            is XrSessionState.Error -> getString(R.string.tracking_state_error)
        }
        if (latestBiasState is XrBiasState.Error && latestSessionState !is XrSessionState.Streaming) {
            trackingSummary = getString(R.string.tracking_state_error)
        }
    }

    private fun runCalibration() {
        lifecycleScope.launch {
            when (latestSessionState) {
                XrSessionState.Connecting,
                is XrSessionState.Calibrating -> Unit

                is XrSessionState.Streaming -> {
                    trackingManager.recalibrate()
                        .onFailure { error ->
                            errors.show(
                                getString(
                                    R.string.tracking_action_failed,
                                    error.message ?: "recalibration failed"
                                )
                            )
                        }
                }

                else -> {
                    trackingManager.runCalibration()
                        .onFailure { error ->
                            errors.show(
                                getString(
                                    R.string.tracking_action_failed,
                                    error.message ?: "calibration failed"
                                )
                            )
                        }
                }
            }
        }
    }

    private fun runZeroView() {
        lifecycleScope.launch {
            trackingManager.zeroView()
                .onFailure { error ->
                    errors.show(
                        getString(
                            R.string.tracking_action_failed,
                            error.message ?: "zero view failed"
                        )
                    )
                }
        }
    }

    private fun preferredSbsDisplayModeId(display: Display): Int? {
        return selectSbsDisplayModeId(
            format = sbsOutputFormat,
            currentRefreshRateHz = display.mode.refreshRate,
            modes = display.supportedModes.map { mode ->
                DisplayModeCandidate(
                    modeId = mode.modeId,
                    widthPixels = mode.physicalWidth,
                    heightPixels = mode.physicalHeight,
                    refreshRateHz = mode.refreshRate
                )
            }
        )
    }

    private fun syncExternalPresentation() {
        if (!canProcessRouting("sync-external-presentation")) {
            return
        }
        val display = displayController.currentPresentationDisplay()
        if (display == null) {
            if (externalPresentation?.isShowing != true) {
                dismissExternalPresentation()
            }
            updateRouting("no-external-display")
            return
        }

        val currentSignature = display.toSignature()
        val activePresentation = externalPresentation
        val presentationIsSettling = activePresentation != null &&
            SystemClock.elapsedRealtime() - externalPresentationShownAtElapsedRealtimeMs <
            EXTERNAL_PRESENTATION_SETTLE_MS
        if (
            activePresentation != null &&
            activePresentation.display.displayId == display.displayId &&
            externalPresentationModeSignature == currentSignature &&
            (externalSurface != null || presentationIsSettling)
        ) {
            return
        }

        dismissExternalPresentation()
        val token = ++externalPresentationToken
        val preferredModeId = preferredSbsDisplayModeId(display)
        val forcedSurfaceWidth = sbsOutputFormat.forcedRenderWidthPixels()
        val forcedSurfaceHeight = if (forcedSurfaceWidth != null) {
            SbsOutputFormat.OUTPUT_HEIGHT_PIXELS
        } else {
            null
        }
        runCatching {
            ExternalGlPresentation(
                context = this,
                display = display,
                preferredDisplayModeId = preferredModeId,
                forcedSurfaceWidthPixels = forcedSurfaceWidth,
                forcedSurfaceHeightPixels = forcedSurfaceHeight,
                listener = object : ExternalGlPresentation.Listener {
                    override fun onVideoSurfaceReady(surface: Surface) {
                        runOnUiThread {
                            if (token != externalPresentationToken ||
                                !canProcessRouting("external-surface-ready")
                            ) {
                                return@runOnUiThread
                            }
                            externalSurface = surface
                            updateRouting("external-surface-ready")
                        }
                    }

                    override fun onVideoSurfaceDestroyed() {
                        runOnUiThread {
                            if (token != externalPresentationToken ||
                                !canProcessRouting("external-surface-destroyed")
                            ) {
                                return@runOnUiThread
                            }
                            onExternalSurfaceDestroyed()
                        }
                    }
                }
            ).also {
                if (token != externalPresentationToken) {
                    it.dismiss()
                    return@also
                }
                externalPresentation = it
                externalPresentationModeSignature = currentSignature
                it.show()
                externalPresentationShownAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
                applyRenderModeToRenderers()
                applyPoseToRenderers(latestPose)
                if (isImageMode) {
                    it.setStaticImage(currentImageBitmap, currentImageSideBySide)
                }
            }
        }.onFailure {
            if (token != externalPresentationToken) {
                return@onFailure
            }
            val detail = it.message ?: getString(R.string.error_unknown)
            errors.show(getString(R.string.error_start_external_presentation, detail))
            externalPresentation = null
            externalSurface = null
            externalPresentationModeSignature = null
            updateRouting("external-presentation-failure")
        }
    }

    private fun dismissExternalPresentation() {
        externalPresentationToken += 1L
        externalPresentation?.dismiss()
        externalPresentation = null
        externalSurface = null
        externalPresentationModeSignature = null
        externalPresentationShownAtElapsedRealtimeMs = 0L
    }

    private fun onInternalSurfaceReady(surface: Surface) {
        internalSurface = surface
        if (!canProcessRouting("internal-surface-ready")) {
            return
        }
        updateRouting("internal-surface-ready")
    }

    private fun onInternalSurfaceDestroyed() {
        val destroyedSurface = internalSurface
        internalSurface = null
        routeBinding.onSurfaceDestroyed(
            surface = destroyedSurface,
            clearSessionSurface = canProcessRouting("internal-surface-destroyed"),
            log = ::logRouting
        )
        updateRouting("internal-surface-destroyed")
    }

    private fun onExternalSurfaceDestroyed() {
        val destroyedSurface = externalSurface
        externalSurface = null
        routeBinding.onSurfaceDestroyed(
            surface = destroyedSurface,
            clearSessionSurface = canProcessRouting("external-surface-destroyed"),
            log = ::logRouting
        )
        updateRouting("external-surface-destroyed")
    }

    private fun updateRouting(trigger: String) {
        if (!canProcessRouting("update-routing:$trigger")) {
            return
        }
        val snapshot = DisplayRouteSnapshot(
            externalDisplayId = displayController.currentPresentationDisplay()?.displayId
                ?: externalPresentation?.display?.displayId,
            externalSurfaceReady = externalSurface != null,
            activeRoute = routeBinding.activeRoute,
            activeSurfaceBound = routeBinding.activeSurface != null
        )
        val decision = routeStateMachine.decide(snapshot)
        logRouting(
            "updateRouting trigger=$trigger target=${decision.target} state=${decision.state.asDebugLabel()} " +
                "activeRoute=${routeBinding.activeRoute} activeSurface=${routeBinding.activeSurface != null} " +
                "externalSurface=${externalSurface != null}"
        )
        routeState = decision.state
        var externalSurfaceRebound = false

        when (decision.target) {
            RouteTarget.EXTERNAL -> {
                externalSurfaceRebound = bindSurface(externalSurface, ActiveRoute.EXTERNAL)
            }
            RouteTarget.HOLD_CURRENT -> Unit
            RouteTarget.NONE -> {
                if (shouldPreservePlaybackDuringOutputRebuild()) {
                    logRouting("skip interruption pause during SBS output rebuild")
                } else {
                    playbackSession.pauseForInterruption()
                }
                clearActiveSurface()
            }
        }
        if (routeState is DisplayRouteState.ExternalActive) {
            if (externalSurfaceRebound) {
                playbackSession.showPausedFrameIfExpected()
            }
            resumeAfterGlassesReconnectIfNeeded()
            playbackSession.resumeIfExpected()
        }

        maybeEnsurePausedFrameVisibility()
        renderRouteStatus()
        updateDiagnostics()
    }

    /**
     * Remembers the playback position when the glasses disappear. The player
     * itself stays open; playback pauses and waits for the external surface.
     */
    private fun handleGlassesDisplayChanged(present: Boolean) {
        val previous = lastGlassesDisplayPresent
        lastGlassesDisplayPresent = present
        if (previous == null || previous == present) {
            return
        }
        if (present) {
            // The resume itself waits for the rebuilt external surface and is
            // handled by [resumeAfterGlassesReconnectIfNeeded].
            return
        }
        if (isImageMode) {
            return
        }
        val state = playbackSession.state.value
        val source = state.source ?: return
        val controller = mediaController
        val positionMs = controller?.currentPosition ?: state.positionMs
        val playbackInProgress = controller?.playWhenReady ?: state.expectedPlayWhenReady
        glassesReconnect.onGlassesLost(
            playbackInProgress = playbackInProgress,
            positionMs = positionMs,
            sourceKey = source.playbackKey
        )
        updateGlassesWaitBanner()
    }

    /**
     * Once the glasses are back and the external surface is ready, continue the
     * same video from the position where the connection dropped.
     */
    private fun resumeAfterGlassesReconnectIfNeeded() {
        if (!glassesReconnect.isWaitingForReconnect) {
            return
        }
        val sourceKey = playbackSession.state.value.source?.playbackKey.orEmpty()
        val plan = glassesReconnect.onGlassesReturned(sourceKey)
        updateGlassesWaitBanner()
        if (plan == null) {
            return
        }
        // Continue exactly where the connection dropped, then resume.
        playbackSession.seekTo(plan.positionMs)
        playbackSession.play()
        updatePlayPauseButton()
    }

    private fun updateGlassesWaitBanner() {
        val waiting = glassesReconnect.isWaitingForReconnect && !isImageMode
        if (!waiting) {
            binding.glassesWaitBanner.visibility = View.GONE
            return
        }
        val positionMs = glassesReconnect.pendingResumePositionMs
        binding.glassesWaitBanner.text = if (positionMs != null) {
            getString(
                R.string.player_glasses_waiting_resume,
                formatPlaybackTime(positionMs)
            )
        } else {
            getString(R.string.player_glasses_waiting)
        }
        binding.glassesWaitBanner.visibility = View.VISIBLE
    }

    private fun bindSurface(surface: Surface?, route: ActiveRoute): Boolean {
        return routeBinding.bindSurface(surface, route, ::logRouting)
    }

    private fun clearActiveSurface(force: Boolean = false) {
        routeBinding.clearActiveSurface(force = force, log = ::logRouting)
    }

    private fun maybeEnsurePausedFrameVisibility() {
        if (!canProcessRouting("paused-frame-ensure")) {
            pausedFrameContinuityJob?.cancel()
            pausedFrameContinuityJob = null
            return
        }
        val sessionState = playbackSession.state.value
        val shouldEnsure = routeState is DisplayRouteState.ExternalActive &&
            routeBinding.activeRoute == ActiveRoute.EXTERNAL &&
            routeBinding.activeSurface != null &&
            sessionState.pausedFrameVisibleExpected &&
            sessionState.awaitingFirstFrameAfterSurfaceBind
        if (!shouldEnsure) {
            pausedFrameContinuityJob?.cancel()
            pausedFrameContinuityJob = null
            return
        }
        if (pausedFrameContinuityJob?.isActive == true) {
            return
        }
        pausedFrameContinuityJob = lifecycleScope.launch {
            val deadlineMs = SystemClock.elapsedRealtime() + PAUSED_FRAME_VISIBILITY_TIMEOUT_MS
            while (isActive && SystemClock.elapsedRealtime() < deadlineMs) {
                val latestState = playbackSession.state.value
                val stillShouldEnsure = routeState is DisplayRouteState.ExternalActive &&
                    routeBinding.activeRoute == ActiveRoute.EXTERNAL &&
                    routeBinding.activeSurface != null &&
                    latestState.pausedFrameVisibleExpected &&
                    latestState.awaitingFirstFrameAfterSurfaceBind
                if (!stillShouldEnsure) {
                    break
                }
                playbackSession.showPausedFrameIfExpected()
                delay(PAUSED_FRAME_REFRESH_RETRY_MS)
            }
        }
    }

    private fun canProcessRouting(trigger: String): Boolean {
        return routeBinding.canProcess(trigger, ::logRouting)
    }

    private fun logRouting(message: String) {
        if (!PlaybackDiagnosticsConfig.isEnabled()) {
            return
        }
        Log.i(PlaybackDiagnosticsConfig.ROUTING_LOG_TAG, message)
    }

    private fun renderRouteStatus() {
        binding.videoSurface.visibility = View.INVISIBLE
    }

    private fun updateDiagnostics() {
        val diagnosticsEnabled = PlaybackDiagnosticsConfig.isEnabled()
        binding.diagnosticsContainer.visibility = if (diagnosticsEnabled) View.VISIBLE else View.GONE
        if (!diagnosticsEnabled) {
            return
        }
        val mode = displayController.currentPhysicalMode()
        val playbackState = playbackSession.state.value
        val displaySummary = if (mode == null) {
            "internal-only [${routeState.asDebugLabel()}]"
        } else {
            "${mode.width}x${mode.height}@${mode.refreshRateHz} [${routeState.asDebugLabel()}]"
        }
        val playbackSummary = buildString {
            append("expected=")
            append(playbackState.expectedPlayWhenReady)
            append(", actual=")
            append(playbackState.playWhenReady)
            append(", pause=")
            append(playbackState.pauseReason)
            append(", surface=")
            append(playbackState.hasBoundSurface)
            append(", pausedFrameExpected=")
            append(playbackState.pausedFrameVisibleExpected)
            append(", pausedFrameRefresh=")
            append(playbackState.lastPausedFrameRefreshResult)
            append("#")
            append(playbackState.pausedFrameRefreshCount)
            append(", firstFrameWaiting=")
            append(playbackState.awaitingFirstFrameAfterSurfaceBind)
            append(", firstFrameCount=")
            append(playbackState.renderedFirstFrameCount)
        }
        diagnosticsOverlay.update(
            DiagnosticsState(
                displaySummary = displaySummary,
                trackingSummary = trackingSummary,
                decoderSummary = decoderSummary,
                playbackSummary = playbackSummary
            )
        )
    }

    private fun applyPoseToRenderers(pose: PoseState) {
        if (::internalRenderer.isInitialized) {
            internalRenderer.setPose(pose)
        }
        externalPresentation?.setPose(pose)
    }

    private fun applyRenderModeToRenderers() {
        if (::internalRenderer.isInitialized) {
            internalRenderer.setRenderMode(renderMode)
        }
        externalPresentation?.setRenderMode(renderMode)
    }

    private fun applyRuntimePose(pose: PoseState) {
        latestPose = pose
        applyPoseToRenderers(pose)
    }

    private fun runRecenter() {
        if (!runtimePoseController.isImuTrackingEnabled()) {
            applyRuntimePose(runtimePoseController.resetTouchpadBias())
            resetTouchpadIndicator(animate = false)
            return
        }
        if (latestSessionState !is XrSessionState.Streaming) {
            errors.show(getString(R.string.tracking_recenter_requires_streaming))
            return
        }
        applyRuntimePose(runtimePoseController.resetTouchpadBias())
        resetTouchpadIndicator(animate = false)
        runZeroView()
    }
}

private data class DisplayModeSignature(
    val displayId: Int,
    val modeId: Int,
    val width: Int,
    val height: Int
)

private fun android.view.Display.toSignature(): DisplayModeSignature {
    val mode = this.mode
    return DisplayModeSignature(
        displayId = displayId,
        modeId = mode.modeId,
        width = mode.physicalWidth,
        height = mode.physicalHeight
    )
}

internal data class PlayerLaunchRequest(
    val source: SourceDescriptor?,
    val resumeExisting: Boolean
)

internal fun shouldForceSourceReset(
    hasSavedInstanceState: Boolean,
    resumeExisting: Boolean,
    currentSourceNormalized: String?,
    requestedSourceNormalized: String
): Boolean {
    return !hasSavedInstanceState &&
        !(resumeExisting && currentSourceNormalized == requestedSourceNormalized)
}

internal fun initialImageIndex(
    playlist: List<SourcePlaylistItem>,
    sourceUri: String,
    restoredIndex: Int
): Int {
    if (restoredIndex in playlist.indices) return restoredIndex
    val matchingIndex = playlist.indexOfFirst {
        it.uri.equals(sourceUri, ignoreCase = true)
    }
    return if (matchingIndex >= 0) matchingIndex else 0
}

internal fun createBufferingDialog(context: Context): AlertDialog =
    MaterialAlertDialogBuilder(
        context,
        R.style.ThemeOverlay_Vr2sbs_Player_SettingsDialog
    )
        .setTitle(R.string.player_buffering_title)
        .setMessage(R.string.player_buffering_message)
        .setPositiveButton(R.string.player_buffering_wait, null)
        .create()

private const val EXTERNAL_RECONNECT_WATCHDOG_MS = 500L
private const val EXTERNAL_PRESENTATION_SETTLE_MS = 2000L
private const val OUTPUT_REBUILD_PRESERVE_MS = 5000L
private const val PAUSED_FRAME_REFRESH_RETRY_MS = 250L
private const val PAUSED_FRAME_VISIBILITY_TIMEOUT_MS = 2000L
private const val BUFFERING_PROMPT_DELAY_MS = 2500L
private const val INITIAL_BUFFERING_PROMPT_DELAY_MS = 8000L
private const val SEEK_INTERVAL_MS = 15_000L
private const val PLAYBACK_PROGRESS_UPDATE_MS = 300L
private const val DEFAULT_IMU_SENSITIVITY = 0.9f
