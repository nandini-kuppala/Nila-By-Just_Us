package com.nila.vision.guard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nila.npu.NpuArbiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watch's handle on the vision model, which runs in [VlmProcessService] (its own process, so a native
 * crash while loading cannot take the app down).
 *
 * The NPU arbitration stays here, in the main process, where the chat model lives: the chat is moved off
 * the NPU before the vision model loads, and a look is skipped while a chat reply is still being written.
 */
class RemoteVlm(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onState: (VlmStatus) -> Unit,
    /** The vision process went away (crash or kill) while it was loaded or loading. */
    private val onDied: () -> Unit,
) {
    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile var ready = false
        private set
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    @Volatile private var loadResult: CompletableDeferred<Boolean>? = null
    private val answers = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val ids = AtomicInteger(1)

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            service = Messenger(binder)
            runCatching { binder.linkToDeath({ died("the vision process ended") }, 0) }
            connected?.complete(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            died("the vision process disconnected")
        }
    }

    /** Whether the Intern3.5-VL bundle is on this phone (the same check [com.nila.vision.NpuVlm] makes). */
    fun isInstalled(): Boolean {
        val external = File(context.getExternalFilesDir("models"), "intern-vl")
        val dir = if (external.isDirectory) external else File(File(context.filesDir, "local"), "intern-vl")
        if (!dir.isDirectory) return false
        return listOf("metadata.json", "vision_encoder.bin", "part1_of_4.bin", "part4_of_4.bin", "tokenizer.json")
            .all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    private suspend fun ensureBound(): Boolean {
        service?.let { return true }
        val wait = CompletableDeferred<Boolean>()
        connected = wait
        if (!bound) {
            bound = runCatching {
                context.bindService(
                    Intent(context, VlmProcessService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                )
            }.getOrDefault(false)
        }
        if (!bound) return false
        return withTimeoutOrNull(8_000) { wait.await() } == true
    }

    /**
     * Put the model on the NPU (in the vision process). False when the chat model is mid-reply (try again
     * shortly), when the process could not be started, or when the model would not load.
     */
    suspend fun load(): Boolean {
        if (ready) return true
        if (!ensureBound()) {
            log("VLM process could not be started")
            return false
        }
        // Nothing else may be resident on the NPU when the vision model loads (two models crash it).
        if (!NpuArbiter.claimForVisionLoad()) {
            log("VLM load deferred: the chat model is answering on the NPU")
            return false
        }
        val result = CompletableDeferred<Boolean>()
        loadResult = result
        send(VlmProcessService.MSG_LOAD, 0, Bundle())
        val ok = withTimeoutOrNull(120_000) { result.await() } == true
        ready = ok
        if (!ok) NpuArbiter.visionUnloaded()
        return ok
    }

    /** One look at [frame]; the model's raw four-line answer, or null. */
    suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String? {
        if (!ready) return null
        val s = service ?: return null
        if (!NpuArbiter.requestForVision()) {
            log("VLM look skipped: the chat model is answering on the NPU")
            return null
        }
        val file = File(File(context.cacheDir, "vision").apply { mkdirs() }, "guard_look.jpg")
        return try {
            FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val id = ids.getAndIncrement()
            val answer = CompletableDeferred<String?>()
            answers[id] = answer
            val data = Bundle().apply {
                putString("path", file.absolutePath)
                putString("system", systemPrompt)
                putString("user", userPrompt)
            }
            runCatching { s.send(Message.obtain(null, VlmProcessService.MSG_LOOK, id, 0).apply { this.data = data; replyTo = replies }) }
                .onFailure { answers.remove(id); return null }
            answer.await()
        } finally {
            NpuArbiter.releaseVision()
        }
    }

    fun close() {
        ready = false
        send(VlmProcessService.MSG_CLOSE, 0, Bundle())
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        failPending()
        NpuArbiter.visionUnloaded()
    }

    private fun send(what: Int, arg1: Int, data: Bundle) {
        val s = service ?: return
        runCatching { s.send(Message.obtain(null, what, arg1, 0).apply { this.data = data; replyTo = replies }) }
    }

    private fun onReply(msg: Message) {
        when (msg.what) {
            VlmProcessService.MSG_STATE -> {
                val d = msg.data
                val status = when (d.getString("kind")) {
                    "loading" -> VlmStatus.Loading(d.getInt("attempt"), d.getInt("of"))
                    "ready" -> VlmStatus.Ready
                    "failed" -> VlmStatus.Failed(d.getString("reason") ?: "the model did not load")
                    else -> VlmStatus.Loading(1, 8)
                }
                onState(status)
                if (d.getBoolean("final")) {
                    if (status is VlmStatus.Ready) log("VLM ready in its own process (${d.getLong("loadMs")} ms, attempt ${d.getInt("attempts")})")
                    loadResult?.complete(status is VlmStatus.Ready)
                }
            }
            VlmProcessService.MSG_ANSWER -> answers.remove(msg.arg1)?.complete(msg.data.getString("text"))
        }
    }

    private fun died(why: String) {
        val wasActive = ready || loadResult?.isActive == true || answers.isNotEmpty()
        service = null
        ready = false
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        failPending()
        NpuArbiter.visionUnloaded()
        log("VLM process gone: $why")
        if (wasActive) Handler(Looper.getMainLooper()).post { onDied() }
    }

    private fun failPending() {
        loadResult?.complete(false)
        connected?.complete(false)
        answers.values.forEach { it.complete(null) }
        answers.clear()
    }
}
