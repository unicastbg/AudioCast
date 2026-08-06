package com.svetlio.audiocast.core

import android.content.Context

/**
 * Thin SharedPreferences wrapper for persisted app config.
 * Phase 0 only stores the device Role; more keys land here as features arrive.
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

    val isConfigured: Boolean get() = role != Role.UNSET

    companion object {
        private const val PREFS = "audiocast_settings"
        private const val KEY_ROLE = "role"
    }
}
