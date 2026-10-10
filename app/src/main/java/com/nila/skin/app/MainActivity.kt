package com.nila.skin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nila.skin.ui.SkinApp

/** The standalone host. In Nila, the same [SkinApp] sits behind one card. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkinApp() }
    }
}
