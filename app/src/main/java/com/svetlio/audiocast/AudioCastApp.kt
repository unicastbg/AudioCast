package com.svetlio.audiocast

import android.app.Application
import com.svetlio.audiocast.core.AppSettings

class AudioCastApp : Application() {

    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }
}
