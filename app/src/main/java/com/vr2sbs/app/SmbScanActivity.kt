package com.vr2sbs.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vr2sbs.R
import com.vr2sbs.databinding.ActivitySmbScanBinding
import com.vr2sbs.databinding.DialogSmbCredentialsBinding
import com.vr2sbs.databinding.ItemSmbDeviceBinding
import com.vr2sbs.smb.SmbDevice
import com.vr2sbs.smb.SmbDeviceDiscovery
import com.vr2sbs.smb.SmbDiscoveryUnavailableException
import com.vr2sbs.smb.SmbProfile
import com.vr2sbs.smb.rememberedDeviceProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SmbScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySmbScanBinding
    private val app by lazy { application as Vr2sbsApplication }
    private val manager by lazy { app.smbClientManager }
    private val profileStore by lazy { app.smbProfileStore }
    private val discovery by lazy { SmbDeviceDiscovery(this) }

    private val devices = linkedMapOf<String, SmbDevice>()
    private var scanJob: Job? = null
    private var scanGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmbScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.smbScanRescanButton.setOnClickListener { startScan() }
        startScan()
    }

    override fun onStart() {
        super.onStart()
        renderRememberedDevices()
    }

    override fun onDestroy() {
        scanGeneration += 1
        scanJob?.cancel()
        scanJob = null
        super.onDestroy()
    }

    private fun startScan() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        devices.clear()
        renderDevices()
        setScanning(true)
        showStatus(getString(R.string.smb_scan_status_scanning))
        scanJob = lifecycleScope.launch {
            try {
                discovery.scan().collect { device ->
                    if (generation != scanGeneration) return@collect
                    val existing = devices[device.host]
                    if (existing == null || (existing.name.isBlank() && device.name.isNotBlank())) {
                        devices[device.host] = device
                        renderDevices()
                        showStatus(
                            resources.getQuantityString(
                                R.plurals.smb_scan_status_found,
                                devices.size,
                                devices.size
                            )
                        )
                    }
                }
                if (generation != scanGeneration) return@launch
                setScanning(false)
                showStatus(
                    if (devices.isEmpty()) {
                        getString(R.string.smb_scan_status_empty)
                    } else {
                        resources.getQuantityString(
                            R.plurals.smb_scan_status_complete,
                            devices.size,
                            devices.size
                        )
                    }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: SmbDiscoveryUnavailableException) {
                if (generation != scanGeneration) return@launch
                setScanning(false)
                showStatus(getString(R.string.smb_scan_status_network_unavailable))
            } catch (error: Throwable) {
                if (generation != scanGeneration) return@launch
                setScanning(false)
                showStatus(
                    getString(
                        R.string.smb_scan_status_failed,
                        error.message?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.error_unknown)
                    )
                )
            }
        }
    }

    private fun setScanning(scanning: Boolean) {
        binding.smbScanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
        binding.smbScanRescanButton.isEnabled = !scanning
        binding.smbScanRescanButton.setText(
            if (scanning) R.string.smb_scan_scanning_button else R.string.smb_scan_rescan
        )
    }

    private fun showStatus(message: String) {
        binding.smbScanStatusText.text = message
    }

    private fun renderDevices() {
        binding.smbScanDevicesContainer.removeAllViews()
        devices.values
            .sortedWith(
                compareBy<SmbDevice> { it.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.host }
            )
            .forEach { device ->
                val row = ItemSmbDeviceBinding.inflate(
                    layoutInflater,
                    binding.smbScanDevicesContainer,
                    false
                )
                row.smbDeviceName.text = device.displayName
                row.smbDeviceAddress.text = if (device.name.isBlank()) {
                    getString(R.string.smb_scan_device_smb_server)
                } else {
                    device.host
                }
                row.root.setOnClickListener { showCredentialsDialog(device) }
                binding.smbScanDevicesContainer.addView(row.root)
            }
        binding.smbScanDevicesHeading.visibility =
            if (devices.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderRememberedDevices() {
        val remembered = rememberedDeviceProfiles(profileStore.loadAll())
        binding.smbScanRememberedContainer.removeAllViews()
        binding.smbScanRememberedHeading.visibility =
            if (remembered.isEmpty()) View.GONE else View.VISIBLE
        remembered.forEach { profile ->
            addSavedProfileButton(
                binding.smbScanRememberedContainer,
                getString(R.string.smb_saved_profile, profile.displayName)
            ) {
                openBrowser(profile, remember = true)
            }.setOnLongClickListener {
                confirmForgetDevice(profile)
                true
            }
        }
    }

    private fun addSavedProfileButton(
        container: LinearLayout,
        label: String,
        onClick: (View) -> Unit
    ): MaterialButton {
        val button = MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
        }
        container.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.launcher_section_spacing) / 2
            }
        )
        return button
    }

    private fun openBrowser(profile: SmbProfile, remember: Boolean) {
        startActivity(SmbBrowserActivity.createIntent(this, profile, remember))
    }

    private fun showCredentialsDialog(device: SmbDevice) {
        val dialogBinding = DialogSmbCredentialsBinding.inflate(layoutInflater)
        val savedProfiles = profileStore.loadAll()
            .filter { it.host.equals(device.host, ignoreCase = true) }
        val saved = savedProfiles.firstOrNull { it.share.isBlank() }
            ?: savedProfiles.firstOrNull()
        dialogBinding.smbDialogServer.text =
            getString(R.string.smb_scan_dialog_server, device.host)
        dialogBinding.smbDialogUsernameInput.setText(saved?.username.orEmpty())
        dialogBinding.smbDialogDomainInput.setText(saved?.domain.orEmpty())
        dialogBinding.smbDialogRemember.isChecked = true

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.smb_scan_connect_title, device.displayName))
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_connect, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            positive.setOnClickListener {
                val profile = SmbProfile.create(
                    host = device.host,
                    share = "",
                    domain = dialogBinding.smbDialogDomainInput.text?.toString().orEmpty(),
                    username = dialogBinding.smbDialogUsernameInput.text?.toString().orEmpty(),
                    password = dialogBinding.smbDialogPasswordInput.text?.toString().orEmpty()
                )
                val remember = dialogBinding.smbDialogRemember.isChecked
                setDialogBusy(dialogBinding, positive, negative, busy = true)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { manager.connect(profile, remember) }
                    }
                    if (!dialog.isShowing) return@launch
                    result.onSuccess {
                        dialog.dismiss()
                        renderRememberedDevices()
                        openBrowser(profile, remember = false)
                    }.onFailure { error ->
                        setDialogBusy(dialogBinding, positive, negative, busy = false)
                        dialogBinding.smbDialogError.text = getString(
                            R.string.smb_error_connect_failed,
                            safeErrorMessage(error)
                        )
                        dialogBinding.smbDialogError.visibility = View.VISIBLE
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setDialogBusy(
        binding: DialogSmbCredentialsBinding,
        positive: Button,
        negative: Button,
        busy: Boolean
    ) {
        binding.smbDialogUsernameInput.isEnabled = !busy
        binding.smbDialogDomainInput.isEnabled = !busy
        binding.smbDialogPasswordInput.isEnabled = !busy
        binding.smbDialogRemember.isEnabled = !busy
        positive.isEnabled = !busy
        negative.isEnabled = !busy
        binding.smbDialogProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) binding.smbDialogError.visibility = View.GONE
    }

    private fun confirmForgetDevice(profile: SmbProfile) {
        val profiles = profileStore.loadAll()
            .filter { it.host.equals(profile.host, ignoreCase = true) }
        if (profiles.isEmpty()) {
            renderRememberedDevices()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smb_scan_forget_device_title)
            .setMessage(getString(R.string.smb_scan_forget_device_message, profile.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.smb_forget) { _, _ ->
                profiles.forEach { profileStore.remove(it.id) }
                renderRememberedDevices()
            }
            .show()
    }

    private fun safeErrorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.error_unknown)
}
