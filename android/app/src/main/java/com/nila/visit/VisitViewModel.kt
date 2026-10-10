package com.nila.visit

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nila.visit.audio.Audio
import com.nila.visit.pipeline.VisitPipeline
import com.nila.visit.remind.Planner
import com.nila.visit.remind.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The doctor visit: record, process, keep.
 *
 * Activity-scoped, so a recording or a visit being written carries on while
 * the parent looks at another tab. Processing keeps the screen on (vivo
 * freezes a backgrounded app within seconds, mid-generation).
 */
class VisitViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object Idle : Phase
        data class Recording(val seconds: Int, val level: Float) : Phase
        data class Working(
            val step: String,
            val detail: String,
            val progress: Float?,
            /** Once speech is done: what was heard, to replay and show being written. */
            val transcript: String = "",
            val audio: File? = null,
            /** The notes found so far, pass by pass. */
            val partial: com.nila.visit.extract.VisitNotes? = null,
            val passesDone: Int = 0,
        ) : Phase
        data class Failed(val message: String) : Phase
    }

    private val store = VisitStore(app)
    private val pipeline = VisitPipeline(app)
    private val models = VisitModels(app)

    private val _models = MutableStateFlow<VisitModels.State>(VisitModels.State.Absent)
    val modelState: StateFlow<VisitModels.State> = _models.asStateFlow()

    init { refreshModels() }

    fun refreshModels() {
        viewModelScope.launch {
            var s = models.state()
            _models.value = s
            if (s == VisitModels.State.Unpacking) {
                models.unpack()
                s = models.state(); _models.value = s
            }
        }
    }

    fun downloadModels() {
        viewModelScope.launch {
            models.download()
            while (true) {
                val s = models.state()
                _models.value = s
                if (s !is VisitModels.State.Downloading) break
                kotlinx.coroutines.delay(1_500)
            }
            if (_models.value == VisitModels.State.Unpacking) {
                models.unpack(); _models.value = models.state()
            }
        }
    }

    fun cancelModelDownload() {
        viewModelScope.launch { models.cancel(); _models.value = models.state() }
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _visits = MutableStateFlow(store.all())
    val visits: StateFlow<List<VisitStore.Saved>> = _visits.asStateFlow()

    /** "auto" or a Whisper code. Auto mistook Telugu for Tamil in testing. */
    val language = MutableStateFlow("auto")
    private val model = "turbo"

    /** The replay's mute, kept across visits so a parent in public sets it once. */
    val replayMuted = MutableStateFlow(
        app.getSharedPreferences("visit", 0).getBoolean("replay_muted", false)
    )
    fun setReplayMuted(m: Boolean) {
        replayMuted.value = m
        getApplication<Application>().getSharedPreferences("visit", 0).edit().putBoolean("replay_muted", m).apply()
    }

    private var recorder: Job? = null

    private val _samples = MutableStateFlow<List<JSONObject>>(emptyList())
    /** The sample visits; copied out of the APK off the main thread. */
    val samples: StateFlow<List<JSONObject>> = _samples.asStateFlow()

    init { viewModelScope.launch(Dispatchers.IO) { _samples.value = models.samples() } }

    // ------------------------------------------------------------- recording

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (recorder != null) return
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        recorder = viewModelScope.launch(Dispatchers.IO) {
            val min = AudioRecord.getMinBufferSize(Audio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // MIC with automatic gain: VOICE_RECOGNITION records raw, and at a
            // doctor's distance that came out twenty times too quiet.
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, Audio.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Audio.RATE))
            val agc = if (android.media.audiofx.AutomaticGainControl.isAvailable())
                android.media.audiofx.AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } else null
            var pcm = ShortArray(Audio.RATE * 60)
            var n = 0
            val buf = ShortArray(Audio.RATE / 10)
            rec.startRecording()
            try {
                while (isActive) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r <= 0) continue
                    if (n + r > pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                    System.arraycopy(buf, 0, pcm, n, r); n += r
                    var peak = 0
                    for (i in 0 until r) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                    _phase.value = Phase.Recording(n / Audio.RATE, peak / 32768f)
                }
            } finally {
                rec.stop(); agc?.release(); rec.release()
                val file = store.audioFile(id)
                Audio.writeWav(file, pcm, n)
                pendingRecording = id to file
            }
        }
    }

    private var pendingRecording: Pair<String, File>? = null

    fun stopRecording() {
        val job = recorder ?: return
        recorder = null
        viewModelScope.launch {
            job.cancel(); job.join()
            val (id, file) = pendingRecording ?: return@launch
            process(id, "Visit · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")), Audio.readWav(file), null,
                LocalDate.now(), null, file)
        }
    }

    // ------------------------------------------------------------ processing

    fun runSample(s: JSONObject) {
        val f = models.sampleAudio(s.getString("id"))
        val lang = if (language.value == "auto") null else language.value
        process("sample-" + s.getString("id"), sampleTitle(s), Audio.readWav(f), lang,
            LocalDate.parse(s.getString("visit_date")),
            s.optString("baby_birth_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse), f)
    }

    companion object {
        private val LANGUAGE_NAMES = mapOf("en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu")
        fun languageName(code: String?) = LANGUAGE_NAMES[code] ?: code?.uppercase() ?: ""

        /** "en-cold-cough" -> "Cold and cough"; the sample's own title when it has one. */
        fun sampleTitle(s: JSONObject): String = s.optString("title").ifBlank {
            s.getString("id").substringAfter('-').replace('-', ' ').replace(" 4m", ", 4 months")
                .replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Speech to text, then text to notes.
     *
     * The visit is saved the moment its speech is text, before the notes are
     * written. If writing the notes then fails, the visit is still in the list
     * with everything the doctor said, and can be written again. [heard] is such
     * a visit: given, the speech step is skipped.
     */
    private fun process(id: String, title: String, audio: FloatArray?, forcedLang: String?, visit: LocalDate, birth: LocalDate?,
                        audioFile: File? = null, heard: VisitStore.Saved? = null) {
        if (_phase.value is Phase.Working) return
        viewModelScope.launch {
            var claimed = false
            var saved: VisitStore.Saved? = heard
            try {
                if (com.nila.monitor.WatchService.state.value.running) {
                    _phase.value = Phase.Failed("The camera watch is using the NPU. Stop it on the Watch tab, then try again.")
                    return@launch
                }
                _phase.value = Phase.Working("Getting ready", "Moving Ask off the NPU", null)
                // Ask may be halfway through an answer; it gets up to half a
                // minute to finish before the visit takes the NPU.
                repeat(60) {
                    if (!claimed) {
                        claimed = com.nila.npu.NpuArbiter.claimForVisit()
                        if (!claimed) kotlinx.coroutines.delay(500)
                    }
                }
                if (!claimed) {
                    com.nila.npu.NpuArbiter.visitDone()
                    _phase.value = Phase.Failed("Ask is still busy on the NPU. Try again in a moment.")
                    return@launch
                }
                // Writing a visit again replaces its notes, so the reminders set
                // from the old notes go with them.
                store.get(id)?.takeIf { it.remindersSet }?.let { Reminders.cancel(getApplication(), id) }
                val text: String
                val s1: JSONArray
                if (heard != null) {
                    text = heard.transcript
                    s1 = heard.stages
                } else {
                    val lang = forcedLang ?: if (language.value == "auto") null else language.value
                    _phase.value = Phase.Working("Listening back", "Turning speech into text on the NPU", null)
                    val (heardText, chunks, stages) = pipeline.transcribe(audio!!, model, lang) { i, total, _ ->
                        _phase.value = Phase.Working("Listening back", "Part ${i + 1} of $total", (i + 1f) / total)
                    }
                    if (heardText.isBlank()) {
                        _phase.value = Phase.Failed("No speech was heard in the recording. Hold the phone closer to the doctor and try again.")
                        return@launch
                    }
                    text = heardText
                    s1 = stagesJson(stages)
                    // Saved now, before the notes: see the comment on process().
                    saved = VisitStore.Saved(id, visit, title, chunks.firstOrNull()?.language, text,
                        com.nila.visit.extract.VisitNotes(), s1, false, notesWritten = false)
                    store.save(saved); _visits.value = store.all()
                }
                _phase.value = Phase.Working("Writing the notes", "Finding medicines", 0f, text, audioFile)
                val (notes, _, extra) = pipeline.extract(text, visit, birth, onPass = { pass, partial ->
                    val done = pass.ordinal + 1
                    val next = when (pass) {
                        com.nila.visit.extract.Extractor.Pass.MEDICINES -> "Finding dates"
                        com.nila.visit.extract.Extractor.Pass.DATES -> "Finding advice"
                        com.nila.visit.extract.Extractor.Pass.ADVICE -> "Checking every note against what was said"
                    }
                    _phase.value = Phase.Working("Writing the notes", next, done / 3f, text, audioFile, partial, done)
                })
                val stages = JSONArray().also { all ->
                    for (i in 0 until s1.length()) all.put(s1.get(i))
                    extra.second.forEach { all.put(JSONObject().put("name", it.name).put("ms", it.ms).put("detail", it.detail)) }
                }
                store.save(saved!!.copy(notes = notes, stages = stages, remindersSet = false, notesWritten = true))
                _visits.value = store.all()
                _phase.value = Phase.Idle
                opened.value = id
            } catch (t: Throwable) {
                android.util.Log.e("VisitViewModel", "visit failed", t)
                if (saved != null) {
                    // What the doctor said is kept; the visit opens on it, with
                    // a way to write the notes again.
                    _phase.value = Phase.Idle
                    opened.value = saved.id
                } else {
                    _phase.value = Phase.Failed(t.message ?: t.toString())
                }
            } finally {
                if (claimed) com.nila.npu.NpuArbiter.visitDone()
            }
        }
    }

    val opened = MutableStateFlow<String?>(null)

    private fun stagesJson(stages: List<VisitPipeline.Stage>) =
        JSONArray(stages.map { JSONObject().put("name", it.name).put("ms", it.ms).put("detail", it.detail) })

    /** Write the notes again from what was heard, without listening again. */
    fun writeNotesAgain(v: VisitStore.Saved) {
        opened.value = null
        process(v.id, v.title, null, null, v.date, null, store.audioFile(v.id).takeIf { it.exists() }, heard = v)
    }

    fun dismissError() { _phase.value = Phase.Idle }

    fun setReminders(v: VisitStore.Saved): Int {
        val plan = Planner.plan(v.notes, v.date)
        val n = Reminders.schedule(getApplication(), v.id, plan)
        store.save(v.copy(remindersSet = true)); _visits.value = store.all()
        return n
    }

    fun clearReminders(v: VisitStore.Saved) {
        Reminders.cancel(getApplication(), v.id)
        store.save(v.copy(remindersSet = false)); _visits.value = store.all()
    }

    fun hasRecording(v: VisitStore.Saved) = store.audioFile(v.id).exists()

    /** Run a saved recording through the current pipeline again. */
    fun reprocess(v: VisitStore.Saved) {
        val f = store.audioFile(v.id).takeIf { it.exists() } ?: return
        opened.value = null
        process(v.id, v.title, Audio.readWav(f), null, v.date, null, f)
    }

    fun delete(v: VisitStore.Saved) {
        Reminders.cancel(getApplication(), v.id); store.delete(v.id); _visits.value = store.all()
        if (opened.value == v.id) opened.value = null
    }
}
