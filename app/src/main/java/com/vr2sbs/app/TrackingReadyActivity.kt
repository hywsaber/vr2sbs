package com.vr2sbs.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.vr2sbs.R
import com.vr2sbs.databinding.ActivityTrackingReadyBinding
import com.vr2sbs.source.SourceDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingReadyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTrackingReadyBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val errors by lazy { ErrorUiController(this) }
    private val trackingManager by lazy { (application as Vr2sbsApplication).trackingSessionManager }
    private var continueJob: Job? = null

    private val source: SourceDescriptor? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(PlayerActivity.EXTRA_SOURCE, SourceDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(PlayerActivity.EXTRA_SOURCE)
        }
    }

    private val resumeExisting: Boolean by lazy {
        intent.getBooleanExtra(PlayerActivity.EXTRA_RESUME_EXISTING, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackingReadyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (source == null) {
            finish()
            return
        }

        binding.continueButton.setOnClickListener { continueToPlayer() }
        setContinueLoading(loading = false)
        if (!shouldShowSbsReminder()) {
            continueToPlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        continueJob?.cancel()
        continueJob = null
        setContinueLoading(loading = false)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun continueToPlayer() {
        if (continueJob != null) {
            return
        }
        maybeSaveSbsReminderPreference()
        setContinueLoading(loading = true)
        continueJob = uiScope.launch {
            val zeroViewResult = if (ImuTrackingSettings.isEnabled(this@TrackingReadyActivity)) {
                trackingManager.zeroView()
            } else {
                Result.success(Unit)
            }
            zeroViewResult
                .onSuccess {
                    val resolvedSource = source ?: return@onSuccess
                    startActivity(
                        Intent(this@TrackingReadyActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_SOURCE, resolvedSource)
                            .putExtra(PlayerActivity.EXTRA_RESUME_EXISTING, resumeExisting)
                    )
                    finish()
                }
                .onFailure { error ->
                    setContinueLoading(loading = false)
                    errors.show(
                        getString(
                            R.string.tracking_action_failed,
                            error.message ?: getString(R.string.tracking_ready_zero_failed)
                        )
                    )
                }
            continueJob = null
        }
    }

    private fun setContinueLoading(loading: Boolean) {
        binding.continueButton.isEnabled = !loading
        binding.continueButton.text = getString(R.string.tracking_ready_continue)
        binding.continueProgressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun shouldShowSbsReminder(): Boolean {
        return !getSharedPreferences(SBS_REMINDER_PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_HIDE_SBS_REMINDER, false)
    }

    private fun maybeSaveSbsReminderPreference() {
        if (binding.doNotShowAgainCheckbox.isChecked) {
            getSharedPreferences(SBS_REMINDER_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_HIDE_SBS_REMINDER, true)
                .apply()
        }
    }

    companion object {
        private const val SBS_REMINDER_PREFS_NAME = "sbs_reminder"
        private const val KEY_HIDE_SBS_REMINDER = "hide_sbs_reminder"
    }
}
