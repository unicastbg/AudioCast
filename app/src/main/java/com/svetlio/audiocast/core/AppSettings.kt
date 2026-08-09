package com.svetlio.audiocast.core

import android.content.Context

/**
 * Thin SharedPreferences wrapper for persisted app config.
 * Stores the device Role and the live-capture transport choice.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var role: Role
        get() = when (prefs.getString(KEY_ROLE, null)) {
            Role.SENDER.name -> Role.SENDER
            Role.RECEIVER.name -> Role.RECEIVER
            else -> Role.UNSET
        }
        set(value) {
            prefs.edit().putString(KEY_ROLE, value.name).apply()
        }

    /**
     * Transport for LIVE capture only (file mode always uses TCP). Used by the
     * sender to decide what it sends; the receiver always listens on both.
     */
    var liveTransport: Transport
        get() = when (prefs.getString(KEY_TRANSPORT, null)) {
            Transport.UDP.name -> Transport.UDP
            else -> Transport.TCP
        }
        set(value) {
            prefs.edit().putString(KEY_TRANSPORT, value.name).apply()
        }

    /** Show the audio level meter on the receiver screen. */
    var visualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUALIZER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VISUALIZER, value).apply()
        }

    /**
     * Shared security PIN. Empty = open (no auth). Set the same value on both
     * the sender and receiver. Sender uses it to authenticate; receiver to verify.
     */
    var securityPin: String
        get() = prefs.getString(KEY_PIN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PIN, value).apply()
        }

    val isConfigured: Boolean get() = role != Role.UNSET

    companion object {
        private const val PREFS = "audiocast_settings"
        private const val KEY_ROLE = "role"
        private const val KEY_TRANSPORT = "live_transport"
        private const val KEY_VISUALIZER = "visualizer_enabled"
        private const val KEY_PIN = "security_pin"
    }
}
