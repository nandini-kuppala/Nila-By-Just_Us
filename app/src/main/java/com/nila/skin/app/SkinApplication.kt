package com.nila.skin.app

import android.app.Application
import com.nila.skin.ui.SkinServices

class SkinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SkinServices.init(this)
    }
}
