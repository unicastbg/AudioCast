package com.svetlio.audiocast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.svetlio.audiocast.ui.AppRoot
import com.svetlio.audiocast.ui.theme.AudioCastTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioCastTheme {
                AppRoot()
            }
        }
    }
}
