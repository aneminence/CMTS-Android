package com.cmts.mobile.remote

import android.content.Context
import java.util.UUID

/** Persistent deviceId + resettable pairing token (SharedPreferences; no new deps). */
class RemoteIdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    var pairingToken: String
        get() {
            val existing = prefs.getString(KEY_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            return resetToken()
        }
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value).apply()
        }

    val deviceModel: String
        get() = RemoteDeviceModel.format(android.os.Build.MANUFACTURER, android.os.Build.MODEL)

    var deviceLabel: String
        get() = prefs.getString(KEY_LABEL, null) ?: DEFAULT_LABEL
        set(value) {
            prefs.edit().putString(KEY_LABEL, value.trim().ifEmpty { DEFAULT_LABEL }).apply()
        }

    var maxHz: Int
        get() = prefs.getInt(KEY_MAX_HZ, DEFAULT_MAX_HZ).coerceIn(1, RemoteSampleTiming.ABS_MAX_HZ)
        set(value) {
            prefs.edit().putInt(KEY_MAX_HZ, value.coerceIn(1, RemoteSampleTiming.ABS_MAX_HZ)).apply()
        }

    var remoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    fun resetToken(): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    companion object {
        private const val PREFS = "cmts_remote"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_LABEL = "device_label"
        private const val KEY_MAX_HZ = "max_hz"
        private const val KEY_ENABLED = "remote_enabled"
        const val DEFAULT_LABEL = "Phone-01"
        const val DEFAULT_MAX_HZ = RemoteSampleTiming.DEFAULT_MAX_HZ
        const val DEFAULT_PORT = 8787
        const val WS_PATH = "/cmts/v1"
        const val PROTO = 1
    }
}
