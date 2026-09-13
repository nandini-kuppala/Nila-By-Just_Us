package com.nila.phonelink

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * The sending phone listening while its app is open, so that pairing can work.
 *
 * The link used to belong to the monitoring services and to nothing else, which
 * meant this phone opened no socket and advertised no service until somebody
 * started monitoring. Pairing happens before that: two phones, both in
 * Settings, neither of them monitoring. The receiving phone searched, found
 * nothing, and said "Looking for the monitoring phone" for as long as anybody
 * was willing to wait -- with nothing on either screen to say that the other
 * phone had to be started first.
 *
 * So the link is also held while a screen of this app is in front of somebody.
 *
 * **In front of somebody, and not merely while the process lives.** An app in
 * the background gets cached and its threads frozen, and a frozen guardian
 * stops sending heartbeats while its socket stays open -- which the receiving
 * phone reads, correctly by its own rules, as the phone watching the baby
 * having died, and sounds the alarm for it. A false alarm at 2am is worse than
 * the feature not existing. So going to the background gives the hold back, and
 * says why, and the other phone shows a sentence instead of sounding.
 *
 * Nothing here changes the overnight case: while monitoring, the service holds
 * its own reference and a wake lock, and this class's comings and goings are
 * invisible to it.
 */
object GuardianPresence {

    private const val TAG = "GuardianPresence"

    /**
     * Said to the other phone when this one steps back. Deliberately about the
     * app rather than about monitoring, because that is what changed.
     */
    private const val CLOSED =
        "The other phone's app was closed. Start monitoring to keep alerts coming."

    private var started = 0
    private var held: GuardianLink? = null

    /** Watch the activity stack. Called once, from [com.nila.NilaApp]. */
    fun watch(app: Application) {
        app.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(this@GuardianPresence) { started++ }
                refresh(app)
            }

            override fun onActivityStopped(activity: Activity) {
                synchronized(this@GuardianPresence) {
                    started = (started - 1).coerceAtLeast(0)
                }
                refresh(app)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Take or give back the hold, for the situation as it now is.
     *
     * Also called after pairing and after a change of role, because those
     * happen with the app already open -- no lifecycle callback fires, and the
     * whole point is that the other phone can connect the moment the code has
     * been typed.
     *
     * Blocking on a preferences read, so off the main thread; the callers are
     * a coroutine on [kotlinx.coroutines.Dispatchers.IO] and the lifecycle
     * callbacks above, which is why this hands the work to a thread of its own.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread({ apply(app) }, "nila-guardian-presence").start()
    }

    @Synchronized
    private fun apply(app: Context) {
        val store = LinkStore(app)
        val wanted = runCatching {
            started > 0 &&
                store.key != null &&
                store.currentRole == LinkStore.Role.GUARDIAN
        }.getOrDefault(false)

        if (wanted && held?.isRunning != true) {
            held = runCatching { GuardianLink.acquire(app) }
                .onFailure { Log.w(TAG, "could not listen", it) }
                .getOrNull()
            if (held != null) Log.i(TAG, "listening while the app is open")
        } else if (!wanted && held != null) {
            val going = held
            held = null
            runCatching { GuardianLink.release(going, CLOSED) }
            Log.i(TAG, "stopped listening; the app is closed or not a guardian")
        }
    }
}
