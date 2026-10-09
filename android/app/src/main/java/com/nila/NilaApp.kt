package com.nila

import android.app.Application
import com.nila.actions.Notifier
import com.nila.data.NilaDatabase

class NilaApp : Application() {
    val database by lazy { NilaDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        // The safety watch's vision model runs in its own process (":vlm"); none of the app's setup
        // below belongs there, and the phone link must not listen twice.
        if (android.os.Build.VERSION.SDK_INT >= 28 && getProcessName() != packageName) return
        Notifier(this).ensureChannels()
        // Both are read by things that run above the view model -- the theme by
        // the Activity, the recording policy by a foreground service that a
        // notification action can start with no Activity at all -- so they are
        // hydrated here, once, where every entry point has already run.
        com.nila.ui.theme.Appearance.load(this)
        com.nila.data.ClipPolicy.load(this)
        // A paired sending phone listens while its app is open, so that the
        // other phone can be paired to it before anybody starts monitoring.
        com.nila.phonelink.GuardianPresence.watch(this)
    }
}
