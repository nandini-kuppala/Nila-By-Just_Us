package com.nila.visit.lab

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
import com.nila.visit.audio.Audio
import com.nila.visit.pipeline.VisitPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Headless test runs, driven from adb so a measurement never depends on taps.
 *
 *   adb shell am start-foreground-service -n com.nila.visit/.lab.LabService \
 *       --es case en-cold-cough --es model turbo [--es mode transcript|audio|asr] [--es lang hi]
 *
 * Cases come from files/testdata/visits.json, audio from files/testdata/audio/
 * (both pushed by tools/run_eval.py). Each run writes files/reports/<case>.json
 * and logs progress under the tag VisitLab. Nothing is ever played aloud: the
 * audio is read from disk exactly as a recording would be.
 */
class LabService : LifecycleService() {

    companion object { private const val TAG = "VisitLab" }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        foreground()
        val case = intent?.getStringExtra("case") ?: "all"
        val model = intent?.getStringExtra("model") ?: "turbo"
        val mode = intent?.getStringExtra("mode") ?: "audio"
        val lang = intent?.getStringExtra("lang")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                runCases(case, model, mode, lang)
            } catch (t: Throwable) {
                Log.e(TAG, "run failed", t)
            } finally {
                Log.i(TAG, "LAB DONE")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runCases(case: String, model: String, mode: String, lang: String?) {
        val base = getExternalFilesDir(null)!!
        if (mode == "cycles") {
            // Load and release the same model over and over, as a day of visits
            // would. The ninth Whisper load in one process once hung in FastRPC.
            val n = case.toIntOrNull() ?: 12
            val pipeline = VisitPipeline(this)
            val clip = Audio.readWav(File(base, "testdata/audio/en-mother-postnatal.wav")).copyOfRange(0, 5 * Audio.RATE)
            for (i in 1..n) {
                val t = System.currentTimeMillis()
                if (model == "llm") {
                    val llm = com.nila.visit.llm.VisitLlm(this, pipeline.llmFile())
                    llm.load(); llm.generate("<|im_start|>user\nSay OK.<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", 4); llm.close()
                } else {
                    pipeline.transcribe(clip, model, "en")
                }
                Log.i(TAG, "cycle $i/$n ok in ${System.currentTimeMillis() - t}ms")
            }
            return
        }
        val visits = JSONObject(File(base, "testdata/visits.json").readText()).getJSONArray("visits")
        val reports = File(base, "reports").apply { mkdirs() }
        val pipeline = VisitPipeline(this)
        for (i in 0 until visits.length()) {
            val v = visits.getJSONObject(i)
            val id = v.getString("id")
            if (case != "all" && case != id) continue
            val visitDate = LocalDate.parse(v.getString("visit_date"))
            val birth = v.optString("baby_birth_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            // "case" = use the visit's own language, as a picker on the visit screen would.
            val caseLang = if (lang == "case") v.optString("language").takeIf { it.isNotBlank() } else lang
            Log.i(TAG, "case $id mode=$mode model=$model lang=$caseLang")
            val started = System.currentTimeMillis()
            val report = JSONObject().put("case", id).put("mode", mode).put("model", model)
            when (mode) {
                "asr", "translate" -> {
                    val audio = Audio.readWav(File(base, "testdata/audio/$id.wav"))
                    val (text, chunks, stages) = pipeline.transcribe(audio, model, caseLang, translate = mode == "translate")
                    report.put("transcript", text).put("reference", v.getString("transcript"))
                        .put("language", chunks.firstOrNull()?.language ?: JSONObject.NULL)
                        .put("stages", JSONArray(stages.map { JSONObject().put("name", it.name).put("ms", it.ms).put("detail", it.detail) }))
                        .put("chunks", JSONArray(chunks.map { JSONObject().put("enc_ms", it.encoderMs).put("dec_ms", it.decoderMs).put("mel_ms", it.melMs).put("tokens", it.tokens) }))
                }
                else -> {
                    val audio = if (mode == "audio") Audio.readWav(File(base, "testdata/audio/$id.wav")) else null
                    val result = pipeline.run(audio, v.getString("transcript"), visitDate, birth, model, caseLang)
                    report.put("result", result.toJson()).put("reference", v.getString("transcript"))
                        .put("expected", v.getJSONObject("expected"))
                }
            }
            report.put("total_ms", System.currentTimeMillis() - started)
            File(reports, "$id.$mode.$model.json").writeText(report.toString(1))
            Log.i(TAG, "case $id done in ${System.currentTimeMillis() - started}ms")
        }
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("lab", "Lab runs", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
        )
        val n: Notification = Notification.Builder(this, "lab")
            .setContentTitle("Visit lab").setContentText("Running a test")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 7, n, type)
    }
}
