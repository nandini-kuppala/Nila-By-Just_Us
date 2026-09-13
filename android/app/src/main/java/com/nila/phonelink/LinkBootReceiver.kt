package com.nila.phonelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Put the receiving phone back to work after a restart.
 *
 * The phone in the parent's room is the one nobody looks at. It sits on a
 * charger with its screen off, and its whole job is to be listening at three in
 * the morning. A restart -- an update installed overnight, a battery that ran
 * out, a phone somebody rebooted on Tuesday -- used to end that silently:
 * [ParentService] was started by pairing and by nothing else, so after a reboot
 * it never came back, and the Settings screen said "Not paired" about a phone
 * that was still paired.
 *
 * Only the receiving half is restored. A guardian is not started here on
 * purpose: monitoring turns on the microphone and the camera, and an app that
 * quietly resumed recording a room because the phone rebooted would be doing
 * something nobody asked it to do.
 */
class LinkBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val store = LinkStore(context.applicationContext)
        val paired = runCatching { store.key != null }.getOrDefault(false)
        val role = runCatching { store.currentRole }.getOrNull()
        if (!paired || role != LinkStore.Role.PARENT) return

        Log.i("LinkBootReceiver", "restoring the receiving link after ${intent.action}")
        runCatching { ParentService.start(context.applicationContext) }
            .onFailure { Log.w("LinkBootReceiver", "could not restart", it) }
    }
}
