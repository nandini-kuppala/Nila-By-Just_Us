package com.nila.babyproof

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nila.babyproof.ui.ScanScreen
import com.nila.babyproof.ui.Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // vivo freezes a backgrounded app within seconds; a scan keeps the screen on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Theme { ScanScreen() } }
        // A headless lab run needs this activity in front (vivo freezes a background
        // app), but not a bright screen: dim it to the minimum while a run is on.
        lifecycleScope.launch {
            while (true) {
                val want = if (com.nila.babyproof.lab.LabService.running) 0.01f else -1f
                if (window.attributes.screenBrightness != want)
                    window.attributes = window.attributes.apply { screenBrightness = want }
                delay(3_000)
            }
        }
    }
}
