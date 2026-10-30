package com.vr2xr.app

import android.content.Context

/**
 * Persists whether IMU head tracking is enabled. The value is shared by the
 * calibration setup screen, the launcher routing policy, and the player's
 * glasses settings toggle.
 */
object ImuTrackingSettings {
    private const val PREFERENCES_FILE = "tracking_settings"
    private const val PREFERENCE_KEY_IMU_TRACKING_ENABLED = "imu_tracking_enabled"

    fun isEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(PREFERENCE_KEY_IMU_TRACKING_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(PREFERENCE_KEY_IMU_TRACKING_ENABLED, enabled)
            .apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
}
