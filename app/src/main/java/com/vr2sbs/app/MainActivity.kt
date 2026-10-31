package com.vr2sbs.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.vr2sbs.R
import com.vr2sbs.bilibili.BilibiliApiClient
import com.vr2sbs.bilibili.BilibiliApiException
import com.vr2sbs.bilibili.BilibiliCookieStore
import com.vr2sbs.bilibili.BilibiliLinkException
import com.vr2sbs.bilibili.BilibiliNetworkException
import com.vr2sbs.bilibili.BilibiliNoStreamException
import com.vr2sbs.bilibili.BilibiliResolver
import com.vr2sbs.bilibili.BilibiliShareText
import com.vr2sbs.databinding.ActivityMainBinding
import com.vr2sbs.databinding.DialogBilibiliShareBinding
import com.vr2sbs.source.IntentIngestor
import com.vr2sbs.source.SourceDescriptor
import com.vr2sbs.source.SourceResolver
import com.vr2sbs.tracking.OneXrConnectionProbe
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private enum class ConnectionStatusUi(
        val labelResId: Int,
        val indicatorColorResId: Int
    ) {
        CHECKING(R.string.xreal_status_checking, R.color.xreal_status_checking),
        CONNECTED(R.string.xreal_status_connected, R.color.xreal_status_connected),
        NOT_CONNECTED(R.string.xreal_status_not_connected, R.color.xreal_status_not_connected),
    }

    private lateinit var binding: ActivityMainBinding
    private val resolver = SourceResolver()
    private val errors by lazy { ErrorUiController(this) }
    private val trackingManager by lazy { (application as Vr2sbsApplication).trackingSessionManager }
    private val smbMediaCache by lazy { (application as Vr2sbsApplication).smbMediaCache }
    private val playbackSession by lazy { (application as Vr2sbsApplication).playbackSessionOwner }
    private val bilibiliCookies by lazy { BilibiliCookieStore() }
    private val bilibiliResolver by lazy { BilibiliResolver(bilibiliCookies::cookieHeader) }
    private var connectionProbeJob: Job? = null
    private var bilibiliLoginStatusJob: Job? = null
    private var bilibiliResolveJob: Job? = null
    private var bilibiliDialog: AlertDialog? = null
    private var bilibiliDialogBinding: DialogBilibiliShareBinding? = null
    private var pendingResumeRequest = false
    private var currentConnectionStatus = ConnectionStatusUi.CHECKING
    private var startupCalibrationLaunched = false
    private var restoredFromState = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult
        }
        resolver.resolveUri(this, uri, persistPermission = true)
            .onSuccess(::handleResolvedSource)
            .onFailure { errors.show(it.message ?: getString(R.string.error_open_selected_file)) }
    }

    private val bilibiliLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bilibiliDialogBinding?.let(::refreshBilibiliLoginStatus)
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.bilibili_login_success, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoredFromState = savedInstanceState != null

        binding.openFileButton.setOnClickListener {
            openDocument.launch(arrayOf("video/*", "image/*"))
        }
        binding.openSmbShareButton.setOnClickListener {
            startActivity(Intent(this, SmbBrowserActivity::class.java))
        }
        binding.scanSmbButton.setOnClickListener {
            startActivity(Intent(this, SmbScanActivity::class.java))
        }

        binding.openUrlButton.setOnClickListener {
            val raw = binding.urlInput.text?.toString().orEmpty()
            resolver.resolveUrl(raw)
                .onSuccess(::handleResolvedSource)
                .onFailure { errors.show(it.message ?: getString(R.string.error_invalid_url)) }
        }
        binding.openBilibiliShareButton.setOnClickListener {
            showBilibiliShareDialog()
        }
        binding.requirementsHelpButton.setOnClickListener {
            showRequirementsModal()
        }
        binding.newReleasesButton.setOnClickListener {
            openReleasesPage()
        }
        binding.clearSmbCacheButton.setOnClickListener {
            confirmClearSmbCache()
        }

        renderConnectionStatusUi(ConnectionStatusUi.CHECKING)
        setRuntimeStatus(getString(R.string.status_glasses_required))
        pendingResumeRequest = isExplicitResumeRequest(intent)
        maybeHandleInboundIntent(intent)
        maybeResumeActivePlaybackSession(consumeOnMiss = false)
    }

    override fun onStart() {
        super.onStart()
        startConnectionProbeLoop()
        maybeResumeActivePlaybackSession(consumeOnMiss = true)
    }

    override fun onResume() {
        super.onResume()
        updateSmbCacheButton()
    }

    override fun onStop() {
        super.onStop()
        connectionProbeJob?.cancel()
        connectionProbeJob = null
    }

    override fun onDestroy() {
        bilibiliLoginStatusJob?.cancel()
        bilibiliLoginStatusJob = null
        bilibiliResolveJob?.cancel()
        bilibiliResolveJob = null
        bilibiliDialog?.dismiss()
        bilibiliDialog = null
        bilibiliDialogBinding = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoredFromState = false
        pendingResumeRequest = isExplicitResumeRequest(intent)
        maybeHandleInboundIntent(intent)
        maybeResumeActivePlaybackSession(consumeOnMiss = true)
    }

    private fun maybeHandleInboundIntent(intent: Intent?) {
        if (intent == null) return
        val bilibiliText = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) {
                intent.getStringExtra(Intent.EXTRA_TEXT)
            } else {
                null
            }
            else -> null
        }
        if (!bilibiliText.isNullOrBlank() && BilibiliShareText.extract(bilibiliText) != null) {
            if (!restoredFromState) {
                showBilibiliShareDialog(prefill = bilibiliText)
            }
            return
        }
        val ingestorResult = IntentIngestor(resolver).ingest(this, intent) ?: return
        ingestorResult
            .onSuccess(::handleResolvedSource)
            .onFailure { errors.show(it.message ?: getString(R.string.error_unsupported_launch_intent)) }
    }

    private fun handleResolvedSource(source: SourceDescriptor) {
        lifecycleScope.launch {
            val probe = trackingManager.probeConnection()
            renderConnectionStatus(probe)
            if (probe.connected) {
                if (
                    shouldLaunchTrackingSetupForSource(
                        trackingManager.sessionState.value,
                        imuTrackingEnabled = ImuTrackingSettings.isEnabled(this@MainActivity)
                    )
                ) {
                    launchTrackingSetup(source)
                } else {
                    launchTrackingReady(source)
                }
            } else {
                setRuntimeStatus(getString(R.string.status_glasses_required))
                Toast.makeText(this@MainActivity, R.string.toast_glasses_required, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPlayer(source: SourceDescriptor, resumeExisting: Boolean = false) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_SOURCE, source)
            .putExtra(PlayerActivity.EXTRA_RESUME_EXISTING, resumeExisting)
        if (resumeExisting) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun launchTrackingSetup(source: SourceDescriptor?) {
        val intent = Intent(this, TrackingSetupActivity::class.java)
        if (source != null) {
            intent.putExtra(PlayerActivity.EXTRA_SOURCE, source)
        }
        startActivity(intent)
    }

    private fun launchTrackingReady(source: SourceDescriptor) {
        startActivity(
            Intent(this, TrackingReadyActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SOURCE, source)
        )
    }

    private fun startConnectionProbeLoop() {
        connectionProbeJob?.cancel()
        connectionProbeJob = lifecycleScope.launch {
            while (isActive) {
                val probe = trackingManager.probeConnection()
                renderConnectionStatus(probe)
                maybeLaunchStartupCalibration(probe)
                delay(2000L)
            }
        }
    }

    private fun maybeLaunchStartupCalibration(probe: OneXrConnectionProbe) {
        if (
            startupCalibrationLaunched ||
            pendingResumeRequest ||
            !probe.connected ||
            isFinishing
        ) {
            return
        }
        if (
            !shouldLaunchTrackingSetupForSource(
                trackingManager.sessionState.value,
                imuTrackingEnabled = ImuTrackingSettings.isEnabled(this)
            )
        ) {
            return
        }
        startupCalibrationLaunched = true
        launchTrackingSetup(source = null)
    }

    private fun maybeResumeActivePlaybackSession(consumeOnMiss: Boolean) {
        if (!pendingResumeRequest) {
            return
        }
        val activeSource = playbackSession.state.value.source
        if (activeSource == null) {
            if (consumeOnMiss) {
                pendingResumeRequest = false
            }
            return
        }
        pendingResumeRequest = false
        launchPlayer(activeSource, resumeExisting = true)
        finish()
    }

    private fun isExplicitResumeRequest(intent: Intent?): Boolean {
        if (intent == null) {
            return false
        }
        return PlaybackResumeContract.isResumeRequest(
            action = intent.action,
            resumeRequested = intent.getBooleanExtra(
                PlaybackResumeContract.EXTRA_RESUME_REQUESTED,
                false
            )
        )
    }

    private fun setRuntimeStatus(message: String?) {
        if (message.isNullOrBlank()) {
            binding.statusText.text = ""
            binding.statusText.visibility = View.GONE
            return
        }
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
    }

    private fun showRequirementsModal() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.requirements_modal_title)
            .setMessage(R.string.requirements_modal_message)
            .setPositiveButton(R.string.requirements_modal_action, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.catppuccin_mocha_blue))
    }

    private fun openReleasesPage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_open_releases, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSmbCacheButton() {
        val used = Formatter.formatShortFileSize(this, smbMediaCache.totalBytes())
        binding.clearSmbCacheButton.text = getString(R.string.smb_cache_clear_button, used)
    }

    private fun confirmClearSmbCache() {
        val used = Formatter.formatShortFileSize(this, smbMediaCache.totalBytes())
        val capacity = Formatter.formatShortFileSize(this, smbMediaCache.capacityBytes)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smb_cache_clear_title)
            .setMessage(getString(R.string.smb_cache_clear_message, used, capacity))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_cache_clear_confirm) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { smbMediaCache.clear() }
                    updateSmbCacheButton()
                    Toast.makeText(
                        this@MainActivity,
                        R.string.smb_cache_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showBilibiliShareDialog(prefill: String? = null) {
        val existingDialog = bilibiliDialog
        if (existingDialog?.isShowing == true) {
            if (!prefill.isNullOrBlank()) {
                bilibiliDialogBinding?.bilibiliShareInput?.setText(prefill)
            }
            return
        }

        val dialogBinding = DialogBilibiliShareBinding.inflate(layoutInflater)
        val initialText = prefill?.takeIf { it.isNotBlank() }
            ?: clipboardText()?.takeIf { BilibiliShareText.extract(it) != null }
        dialogBinding.bilibiliShareInput.setText(initialText.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bilibili_share_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bilibili_open, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startBilibiliResolve(dialog, dialogBinding)
            }
        }
        dialog.setOnDismissListener {
            bilibiliLoginStatusJob?.cancel()
            bilibiliLoginStatusJob = null
            bilibiliResolveJob?.cancel()
            bilibiliResolveJob = null
            if (bilibiliDialog === dialog) {
                bilibiliDialog = null
                bilibiliDialogBinding = null
            }
        }
        bilibiliDialog = dialog
        bilibiliDialogBinding = dialogBinding
        dialogBinding.bilibiliLoginButton.setOnClickListener {
            bilibiliLoginLauncher.launch(Intent(this, BilibiliLoginActivity::class.java))
        }
        dialog.show()
        refreshBilibiliLoginStatus(dialogBinding)
    }

    private fun refreshBilibiliLoginStatus(dialogBinding: DialogBilibiliShareBinding) {
        bilibiliLoginStatusJob?.cancel()
        dialogBinding.bilibiliLoginProgress.visibility = View.VISIBLE
        dialogBinding.bilibiliLoginStatus.text =
            getString(R.string.bilibili_login_status_checking)
        bilibiliLoginStatusJob = lifecycleScope.launch {
            val nav = withContext(Dispatchers.IO) {
                runCatching {
                    BilibiliApiClient(bilibiliCookies::cookieHeader).fetchNav()
                }.getOrNull()
            }
            if (!isActive) {
                return@launch
            }
            dialogBinding.bilibiliLoginProgress.visibility = View.GONE
            when {
                nav == null -> {
                    dialogBinding.bilibiliLoginStatus.text =
                        getString(R.string.bilibili_login_check_failed)
                    dialogBinding.bilibiliLoginButton.text = getString(R.string.bilibili_login)
                }
                nav.isLoggedIn -> {
                    val name = nav.userName ?: getString(R.string.bilibili_login_title)
                    dialogBinding.bilibiliLoginStatus.text = nav.vipLabel?.let { vip ->
                        getString(R.string.bilibili_login_status_vip, name, vip)
                    } ?: getString(R.string.bilibili_login_status, name)
                    dialogBinding.bilibiliLoginButton.text =
                        getString(R.string.bilibili_switch_account)
                }
                else -> {
                    dialogBinding.bilibiliLoginStatus.text =
                        getString(R.string.bilibili_login_status_signed_out)
                    dialogBinding.bilibiliLoginButton.text = getString(R.string.bilibili_login)
                }
            }
        }
    }

    private fun startBilibiliResolve(
        dialog: AlertDialog,
        dialogBinding: DialogBilibiliShareBinding
    ) {
        val rawText = dialogBinding.bilibiliShareInput.text?.toString().orEmpty()
        if (BilibiliShareText.extract(rawText) == null) {
            dialogBinding.bilibiliShareInputLayout.error =
                getString(R.string.bilibili_error_no_link)
            return
        }
        dialogBinding.bilibiliShareInputLayout.error = null
        dialogBinding.bilibiliShareError.visibility = View.GONE
        dialogBinding.bilibiliShareProgress.visibility = View.VISIBLE
        dialogBinding.bilibiliLoginButton.isEnabled = false
        val openButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        openButton.isEnabled = false

        bilibiliResolveJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { bilibiliResolver.resolve(rawText) }
            }
            if (!isActive || isFinishing || isDestroyed) {
                return@launch
            }
            dialogBinding.bilibiliShareProgress.visibility = View.GONE
            dialogBinding.bilibiliLoginButton.isEnabled = true
            openButton.isEnabled = true
            result.onSuccess { resolved ->
                dialog.dismiss()
                val message = resolved.qualityLabel?.let { quality ->
                    getString(R.string.bilibili_opening_quality, resolved.title, quality)
                } ?: getString(R.string.bilibili_opening, resolved.title)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                handleResolvedSource(resolved.source)
            }.onFailure { error ->
                dialogBinding.bilibiliShareError.text = bilibiliErrorMessage(error)
                dialogBinding.bilibiliShareError.visibility = View.VISIBLE
            }
        }
    }

    private fun bilibiliErrorMessage(error: Throwable): String = when (error) {
        is BilibiliApiException -> when (error.code) {
            -101, -403 -> getString(R.string.bilibili_error_login_required)
            -404 -> getString(R.string.bilibili_error_video_not_found)
            -352 -> getString(R.string.bilibili_error_risk_control)
            else -> getString(
                R.string.bilibili_error_api,
                error.code,
                error.message ?: getString(R.string.error_unknown)
            )
        }
        is BilibiliNetworkException -> getString(
            R.string.bilibili_error_network,
            error.cause?.message ?: error.message ?: getString(R.string.error_unknown)
        )
        is BilibiliNoStreamException -> getString(R.string.bilibili_error_no_streams)
        is BilibiliLinkException -> getString(R.string.bilibili_error_no_link)
        else -> getString(
            R.string.bilibili_error_resolve,
            error.message ?: getString(R.string.error_unknown)
        )
    }

    private fun clipboardText(): String? {
        val manager = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) {
            return null
        }
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    private fun renderConnectionStatusUi(nextStatus: ConnectionStatusUi) {
        val animateConnectedTransition =
            currentConnectionStatus == ConnectionStatusUi.NOT_CONNECTED &&
                nextStatus == ConnectionStatusUi.CONNECTED

        currentConnectionStatus = nextStatus
        binding.xrealStatusText.text = getString(nextStatus.labelResId)
        binding.xrealStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, nextStatus.indicatorColorResId)
        )

        if (animateConnectedTransition) {
            playConnectedMicroAnimation()
            return
        }
        resetStatusAnimations()
    }

    private fun resetStatusAnimations() {
        binding.xrealStatusDot.scaleX = 1f
        binding.xrealStatusDot.scaleY = 1f
        binding.xrealStatusDot.alpha = 1f
        binding.xrealStatusIcon.translationY = 0f
        binding.xrealStatusIcon.scaleX = 1f
        binding.xrealStatusIcon.scaleY = 1f
        binding.xrealStatusText.alpha = 1f
    }

    private fun playConnectedMicroAnimation() {
        val iconNudge = resources.getDimension(R.dimen.launcher_icon_nudge_distance)
        val dotScaleX = ObjectAnimator.ofFloat(binding.xrealStatusDot, View.SCALE_X, 1f, 1.65f, 1f)
        val dotScaleY = ObjectAnimator.ofFloat(binding.xrealStatusDot, View.SCALE_Y, 1f, 1.65f, 1f)
        val dotFade = ObjectAnimator.ofFloat(binding.xrealStatusDot, View.ALPHA, 0.5f, 1f)
        val textFade = ObjectAnimator.ofFloat(binding.xrealStatusText, View.ALPHA, 0.2f, 1f)
        val iconNudgeAnimator = ObjectAnimator.ofFloat(binding.xrealStatusIcon, View.TRANSLATION_Y, 0f, -iconNudge, 0f)
        val iconScaleX = ObjectAnimator.ofFloat(binding.xrealStatusIcon, View.SCALE_X, 1f, 1.1f, 1f)
        val iconScaleY = ObjectAnimator.ofFloat(binding.xrealStatusIcon, View.SCALE_Y, 1f, 1.1f, 1f)
        AnimatorSet().apply {
            duration = 420L
            interpolator = FastOutSlowInInterpolator()
            playTogether(dotScaleX, dotScaleY, dotFade, textFade, iconNudgeAnimator, iconScaleX, iconScaleY)
            start()
        }
    }

    private fun renderConnectionStatus(probe: OneXrConnectionProbe) {
        val nextStatus = if (probe.connected) {
            ConnectionStatusUi.CONNECTED
        } else {
            ConnectionStatusUi.NOT_CONNECTED
        }
        renderConnectionStatusUi(nextStatus)
    }

    private companion object {
        const val RELEASES_URL = "https://github.com/hywsaber/vr2sbs/releases/"
    }
}
