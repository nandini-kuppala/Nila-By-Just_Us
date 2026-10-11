package com.nila.babyproof.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nila.babyproof.pipeline.BabyproofPipeline
import com.nila.babyproof.pipeline.Rules
import com.nila.babyproof.pipeline.Scan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Headless evaluation runs, driven from adb so a measurement never depends on taps.
 *
 *   adb shell am start-foreground-service -n com.nila.babyproof/.lab.LabService \
 *       --es mode detector|full [--es only e01,e07] [--es set eval|demo]
 *
 * Photos come from files/<set>/images/ (pushed by tools/run_eval.py). Results go to
 * files/<set>/results/<mode>/<photo>.json, progress to logcat under BabyproofLab.
 *
 * "full" runs the detector over every photo first, then loads the vision model
 * once and confirms them all: the two models never share the NPU, and the slow
 * VLM load is paid once per run instead of once per photo.
 */
class LabService : LifecycleService() {

    companion object {
        private const val TAG = "BabyproofLab"
        /** Read by MainActivity: while a run is on, the screen is kept on but dimmed to the minimum. */
        @Volatile var running = false
    }

    /**
     * Back-to-back NPU work heats the phone. Pause only when the phone itself warns
     * ("critical", skin about 45 C, where vivo shows its overheating notice): wait
     * until it is back to "moderate", or 10 minutes at most, then carry on.
     * Warm-but-fine ("severe", 42 C) does not stop a run -- a dim screen on USB power
     * sits there for good.
     */
    private suspend fun coolDown(rest: Long) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_CRITICAL) {
            var waited = 0
            while (waited < 600 && (waited == 0 || pm.currentThermalStatus > android.os.PowerManager.THERMAL_STATUS_MODERATE)) {
                if (waited % 60 == 0) Log.i(TAG, "phone is hot (thermal status ${pm.currentThermalStatus}); cooling for ${waited}s")
                kotlinx.coroutines.delay(10_000); waited += 10
            }
        }
        kotlinx.coroutines.delay(rest)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        foreground()
        val mode = intent?.getStringExtra("mode") ?: "detector"
        val only = intent?.getStringExtra("only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val set = intent?.getStringExtra("set") ?: "eval"
        val rest = intent?.getStringExtra("rest")?.toLongOrNull() ?: 3_000L
        lifecycleScope.launch(Dispatchers.Default) {
            running = true
            // vivo's log buffer rotates within minutes; the runner watches this file instead.
            val done = File(getExternalFilesDir(null), "$set/results/$mode.done").apply { delete() }
            var outcome = "ok"
            try {
                run(set, mode, only, rest)
            } catch (t: Throwable) {
                Log.e(TAG, "run failed", t)
                outcome = "failed: $t"
            } finally {
                running = false
                runCatching { done.writeText(outcome) }
                Log.i(TAG, "LAB DONE")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(set: String, mode: String, only: Set<String>?, rest: Long) {
        val base = File(getExternalFilesDir(null), set)
        val photos = File(base, "images").listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
            .filter { only == null || it.nameWithoutExtension in only }
        val out = File(base, "results/$mode").apply { mkdirs() }
        val pipeline = BabyproofPipeline(this)
        Log.i(TAG, "run $mode on ${photos.size} photos")

        val found = LinkedHashMap<File, BabyproofPipeline.Found>()
        for ((i, f) in photos.withIndex()) {
            coolDown(rest / 3)
            val photo = pipeline.decode { f.inputStream() }
            val t = System.currentTimeMillis()
            val r = pipeline.find(photo)
            found[f] = r
            Log.i(TAG, "find ${i + 1}/${photos.size} ${f.name}: ${r.candidates.size} hits in ${System.currentTimeMillis() - t}ms")
            if (mode == "detector") {
                val scan = Scan(Rules.merge(r.candidates.mapNotNull { Rules.decide(it, null) }), r.candidates, emptyList(), r.timings, r.notes)
                write(out, f, photo.width, photo.height, scan)
            }
            photo.recycle()
        }
        if (mode != "full") return
        // The detector's NPU session is closed before the VLM loads (confirm does it too).
        pipeline.detector.close()

        for ((i, entry) in found.entries.withIndex()) {
            val (f, r) = entry
            coolDown(rest)
            val photo = pipeline.decode { f.inputStream() }
            val t = System.currentTimeMillis()
            val scan = pipeline.confirm(photo, r)
            Log.i(TAG, "confirm ${i + 1}/${found.size} ${f.name}: ${scan.findings.size} findings in ${System.currentTimeMillis() - t}ms")
            write(out, f, photo.width, photo.height, scan)
            photo.recycle()
        }
        pipeline.closeVlm()
    }

    private fun write(dir: File, photo: File, w: Int, h: Int, scan: Scan) {
        val json = scan.json().put("image", photo.name).put("width", w).put("height", h)
        File(dir, photo.nameWithoutExtension + ".json").writeText(json.toString(1))
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("lab", "Lab runs", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, "lab").setContentTitle("Babyproof lab run")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }
}
