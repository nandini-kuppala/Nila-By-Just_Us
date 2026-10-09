package com.nila.vision.guard

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.nila.vision.NpuVlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The vision model (Intern3.5-VL-2B on the NPU), in its own process (`:vlm`).
 *
 * Why a process of its own: on the iQOO 15 the first `create()` of the bundle in a process fails, and in
 * Nila 2.6.0 the retry sometimes dies with a native null-pointer crash inside Qualcomm's HTP stub
 * (`QnnApi::createDevice`), which cannot be caught from Kotlin. Inside the main process that crash took
 * the whole app down -- camera, safe zone, alerts, the cry monitor. Here it takes down only this process;
 * the watch sees the binder die, starts a fresh one and tries again, and everything else keeps running.
 *
 * Protocol (Messenger): [MSG_LOAD] -> [MSG_STATE] updates, the last with `final = true`;
 * [MSG_LOOK] (JPEG path + prompts, arg1 = id) -> [MSG_ANSWER] (arg1 = id, `text` or none); [MSG_CLOSE].
 */
class VlmProcessService : Service() {

    companion object {
        private const val TAG = "NilaGuardVlm"
        const val MSG_LOAD = 1
        const val MSG_LOOK = 2
        const val MSG_CLOSE = 3
        const val MSG_STATE = 10
        const val MSG_ANSWER = 11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vlm: NpuVlm? = null

    private fun engine(): NpuVlm = vlm ?: NpuVlm(this).also { vlm = it }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo
            when (msg.what) {
                MSG_LOAD -> scope.launch {
                    val e = engine()
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { e.start { s -> send(reply, MSG_STATE, 0, stateBundle(s, false)) } }
                        .getOrDefault(false)
                    Log.i(TAG, "load ok=$ok in ${System.currentTimeMillis() - t0} ms state=${e.state}")
                    send(reply, MSG_STATE, 0, stateBundle(e.state, true))
                }
                MSG_LOOK -> {
                    val id = msg.arg1
                    val data = msg.data
                    val path = data.getString("path")
                    val system = data.getString("system").orEmpty()
                    val user = data.getString("user").orEmpty()
                    scope.launch {
                        val text = runCatching {
                            val bmp = path?.let { BitmapFactory.decodeFile(it) } ?: return@runCatching null
                            try {
                                engine().look(bmp, system, user)
                            } finally {
                                bmp.recycle()
                            }
                        }.getOrNull()
                        send(reply, MSG_ANSWER, id, Bundle().apply { text?.let { putString("text", it) } })
                    }
                }
                MSG_CLOSE -> {
                    runCatching { vlm?.close() }
                    vlm = null
                    stopSelf()
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { vlm?.close() }
        vlm = null
        stopSelf()
        return false
    }

    override fun onDestroy() {
        runCatching { vlm?.close() }
        vlm = null
        scope.cancel()
        super.onDestroy()
        // The NPU session is only fully given back by the process ending.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun stateBundle(s: NpuVlm.State, final: Boolean) = Bundle().apply {
        putBoolean("final", final)
        when (s) {
            NpuVlm.State.Absent -> putString("kind", "absent")
            is NpuVlm.State.Loading -> {
                putString("kind", "loading"); putInt("attempt", s.attempt); putInt("of", s.of)
            }
            is NpuVlm.State.Ready -> {
                putString("kind", "ready"); putLong("loadMs", s.loadMs); putInt("attempts", s.attempts)
            }
            is NpuVlm.State.Failed -> {
                putString("kind", "failed"); putString("reason", s.reason)
            }
        }
    }

    private fun send(to: Messenger?, what: Int, arg1: Int, data: Bundle) {
        to ?: return
        runCatching { to.send(Message.obtain(null, what, arg1, 0).apply { this.data = data }) }
    }
}
