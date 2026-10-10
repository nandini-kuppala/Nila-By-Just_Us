# Doctor visit — the Doctor tab

Put the phone near the paediatrician, tap **Record the visit**, tap **Visit
finished**. Nila turns the visit into notes a parent can act on — medicines with
reminders, dates on a calendar, things to watch for, do's and don'ts — each one
carrying the doctor's own words. Everything runs on the phone's NPU; nothing
leaves the device.

The module was built and measured as a standalone lab app (`doctor-visit/`) and
then integrated into Nila as the fifth tab.

---

## Where it lives

| Part | Path |
|---|---|
| Engine (speech, extraction, reminders, storage) | `android/app/src/main/java/com/nila/visit/` |
| Screens (tab, notes page, calendar) | `android/app/src/main/java/com/nila/ui/screens/VisitScreens.kt` |
| Navigation (tab, routes, keep-screen-on) | `android/app/src/main/java/com/nila/ui/NilaRoot.kt` |
| NPU hand-over | `npu/NpuArbiter.kt` — `claimForVisit()` / `visitDone()` |
| Whisper download entry | `assistant/ModelInstaller.kt` — `Model.LISTENING` |
| Assets | `assets/visit/` — mel filters, Whisper tokenizer, patched wrappers, 8 sample visits |
| Tests | `src/test/java/com/nila/visit/` — mel vs numpy, extraction guards, dates, reminder planner |
| Lab (experiments, headless runs, scoring) | `doctor-visit/` — see its README |

```
visit/
  asr/        MelSpectrogram, WhisperTokenizer, WhisperAsr      speech → text
  audio/      Audio (WAV, resample, normalise, cut at pauses)
  llm/        VisitLlm                                          Qwen on the NPU
  extract/    Extractor, Grounding, Numbers, WhenResolver,      text → notes
              DateFinder, VisitNotes
  pipeline/   VisitPipeline                                     the two stages in order
  remind/     Planner, ReminderReceiver (+ Reminders)           alarms
  VisitStore, VisitModels, VisitViewModel
```

## Navigation

Bottom bar: **Monitor · Ask · Watch · Health · Doctor**.

- **Doctor tab** — language picker, Record, Calendar, *Try a sample visit*
  (3 shown, View all), *Past visits* (3 shown, View all). While recording or
  writing, the tab shows that screen instead; the parent can switch tabs and
  come back.
- **Visit notes** (`doctor/visit/{id}`) — opens by itself when a visit is
  written. Back arrow returns to the tab. *Write again* re-runs a recording;
  *Delete* asks first.
- **Calendar** (`doctor/calendar`) — month grid of visits, vaccines, tests and
  medicine courses from recorded visits (samples are left out).
- The medicine scanner moved off the bar: Ask → *Try one of these* →
  **Scan a tablet sheet** (`ask/scan`), with a back arrow.

---

## How a visit is processed

```
 mic (16 kHz) ──► WAV on disk ──► Whisper Turbo (NPU) ──► transcript ──► Qwen3.5-2B (NPU) ──► notes ──► reminders / calendar
                                  speech → text                         3 passes + guards
```

**One model on the NPU at a time.** Before anything runs, the visit takes the
NPU from Ask (`NpuArbiter.claimForVisit`): Ask's engine is closed and moves to
the CPU. Whisper loads, transcribes, and is closed; only then is Qwen loaded,
used for three passes, and closed. When the visit is done (`visitDone`) Ask
returns to the NPU after its usual 45 s grace. If Ask is mid-answer the visit
waits up to 30 s; if the camera watch is running the visit refuses and says so.

The screen stays on from *Record* until the notes open — vivo's power manager
freezes a backgrounded app within seconds, mid-generation.

### 1. Recording

- `AudioRecord`, `MIC` source with automatic gain control, 16 kHz mono 16-bit,
  held in memory and written to `files/visits/<id>.wav` when the parent taps
  *Visit finished*.
- The level ring on screen follows the microphone peak.

### 2. Speech to text — Whisper Large-v3 Turbo

- Qualcomm AI Hub export `whisper_large_v3_turbo_quantized`, **v0.62.0**,
  `precompiled_qnn_onnx`, built for Snapdragon 8 Elite Gen 5 with **QAIRT 2.45**
  — the same QNN version GenieX 0.4.0 ships, so one set of Hexagon libraries
  serves both models.
- Run by ONNX Runtime 1.27 with the QNN execution provider on the HTP.
  `tools/patch_whisper.py` (lab) rewrote the wrappers' uint16 tensors to float,
  because ONNX Runtime's Java API has no uint16; the patched
  `encoder_f.onnx` / `decoder_f.onnx` ship in the APK.
- Steps: normalise the volume → pick the language (chosen chip, or detected on
  the first 10 s) → **cut at the speaker's pauses** into pieces of ≤ 25 s for
  English, ≤ 8 s for Indian scripts (the decoder has 200 token slots per piece,
  and those scripts cost several tokens per letter) → mel spectrogram (Kotlin
  port, matches numpy to 3e-5) → encoder → greedy decoder with repeat guards.
- Speed on the iQOO 15: model load ~1.6 s; ~1 s encoder per piece; a 42 s
  English visit transcribes in ~4 s (≈ 10× faster than real time). Hindi/Tamil/
  Telugu ≈ 3–4× real time (shorter pieces).
- Measured error: English 3–8 % of words, Hinglish 7 %, Hindi 10 % of
  characters, Tamil 21 %. Telugu needs the language chosen (auto hears Tamil).

### 3. Text to notes — Qwen3.5-2B

Three narrow passes over the same transcript, each asking for one JSON object:

| Pass | Asks for | Reply cap |
|---|---|---|
| Medicines | name, amount *as said*, how often, times of day, days, "only if", quote | 600 tokens |
| Dates | next visit, vaccines (and any given today), tests — with "when" in words, quote | 450 tokens |
| Advice | watch for (+ "come at once"), do, don't, one-sentence summary | 700 tokens |

The model writes; **plain code checks** (all unit-tested):

- **Grounding** — every item's quote must be in the transcript (≥ 70 % of its
  content words), or the item is dropped and listed under *Left out*.
- A medicine's **name must be in the transcript** (English).
- **Amounts** are shown in the doctor's own words; for English the numbers must
  match the quote ("zero point six" = 0.6, "two and a half" = 2.5).
- **"Only if"** is read from the doctor's words (English, Hindi, Tamil, Telugu),
  so a fever medicine never gets four alarms a day. "Even if" is not "only if".
- **Dates are worked out in code** (`WhenResolver`), not by the model: "after
  three days", "on the eighth of December", "next Monday at 9", "at six months"
  (from the birth date). `DateFinder` adds English visit dates the model missed.
  A date that can't be worked out is shown as *Date not clear*.
- **Meal times**: "after each meal" → three reminders.
- The visit itself is never listed as an appointment.

Speed: ~1.3 s to load, ~25 tokens/s writing; a typical visit's notes take
30–60 s.

### 4. Reminders and calendar

`Planner` (pure Kotlin, unit-tested):

- each medicine rings at each of its times, every day of its course, starting
  with the first time still ahead today; a course with no length is planned
  30 days and labelled *Ongoing*; "only if" medicines never ring;
- a visit, vaccine or test rings at 7 pm the evening before and 8 am on the
  day (or an hour before, when the doctor gave a time).

Reminders are set only when the parent taps **Set N reminders**; exact alarms
when the phone allows them, otherwise within a 10-minute window. Channel:
*Medicine and doctor visit reminders* (normal sound; adjustable in Android
settings). Tapping one opens Nila.

### 5. How it is written down (storage)

- **Saved in two steps.** The visit is saved the moment its speech is text,
  marked *notes not written yet*; the notes are added when they finish. If the
  notes step fails (or the app is closed mid-way), the visit stays in the list
  with everything the doctor said, opens on the transcript, and offers **Write
  the notes again** — which re-runs only the notes, not the listening.

- `files/visits/<id>.json` — date, title, language, transcript, notes, stage
  timings, whether reminders are set. `files/visits/<id>.wav` — the recording.
  (`files` = `/sdcard/Android/data/com.nila/files`, private to the app.)
- Each note keeps its **quote** — tap an item to see the doctor's words.
- *Delete* removes the JSON, the WAV and the visit's reminders.

---

## Is it the same Qwen as Ask?

**Same model, same file, separate engine.**

| | Ask | Doctor visit |
|---|---|---|
| Weights | `Qwen3.5-2B-Q4_0.gguf` | the **same file** (`files/models/`), not a second copy |
| Runtime | GenieX 0.4.0, llama.cpp plugin, NPU | same |
| Prompt format | Qwen chat template, reasoning off | same |
| Context | 4,096 tokens | 4,096 tokens |
| Sampling | temperature 0.3, top-k 20, top-p 0.8, presence penalty 1.5 | temperature 0.1, top-k 1 (near-greedy), **no** presence penalty |
| Lifetime | stays loaded between questions | loaded for one visit, reset before each pass, closed after |

The sampling differs on purpose: the visit wants the same JSON every time, and a
presence penalty pushes the model to invent new keys and items. The two engines
are never on the NPU together — Ask is moved to the CPU for the length of a
visit.

---

## How long can a visit be?

There is no timer on the recording. The limits come from three places;
the first one reached decides.

**1. The notes model's context — the hard limit.** Prompt + reply must fit in
4,096 tokens. Measured on the phone (lab, synthetic English transcripts):

| Speech (English, ~150 words/min) | Prompt tokens | Result |
|---|---|---|
| ~2 min | ~690 | ✅ all medicines, dates, advice |
| ~4 min | ~1,070 | ⚠️ works; a visit with many items hits the reply caps (below) |
| ~11 min | ~2,410 | ⚠️ runs, but medicines lost their quotes and were dropped |
| ~16 min | ~3,350 | ⚠️ same |
| ~21 min | ~4,300 | ❌ `Context length exceeded` — no notes |

Roughly 190 tokens per minute of English speech, ~300 for Hindi/Tamil, ~500
for Telugu. So the hard ceiling is about **16 min English, 9 min Hindi/Tamil,
6 min Telugu** of continuous talk (pauses cost nothing; a real visit with
silences can run longer on the clock).

**2. The reply caps — the quality limit.** Each pass's reply is capped (600 /
450 / 700 tokens). That fits about **5 medicines, 4–5 dates and ~10 advice
items** per visit; past that the reply is cut and the unfinished items are
dropped. In practice this bites before the context does.

**3. Memory — the recording limit.** The recording is held in memory
(~32 KB per second) and then read back as floats for Whisper. With Nila's
256 MB heap, recordings past roughly **25–30 minutes** risk running out of
memory. (Estimated, not tested.)

**Practical guidance today:** a typical paediatric visit of **up to ~10 minutes
in English (~5 minutes in Indian languages)** with a handful of medicines works
well. Whisper itself has no length limit — a 15-minute English recording
transcribes in about 1.5 minutes.

### Known gaps

- Longer visits need the transcript processed in windows (e.g. 4-minute
  slices) and the notes merged; and/or larger reply caps.
- A timer warning near the limit, or a foreground service so recording
  survives the screen locking, are not built yet.
- Tamil and Telugu notes are experimental — a 2B model reads those scripts
  poorly. Synthetic voices are cleaner than a clinic.
- It records instructions; it does not check them. Every medicine says to check
  the amount against the prescription.

---

## Models on the phone

| Model | Size | Where | How it gets there |
|---|---|---|---|
| Whisper Large-v3 Turbo (AI Hub v0.62.0) | 861 MB zip → 1.07 GB | `files/models/whisper-turbo/` | Doctor tab → *Download (860 MB)*, Wi-Fi only; unzipped in-app, wrappers added from the APK |
| Qwen3.5-2B-Q4_0 | 1.2 GB | `files/models/Qwen3.5-2B-Q4_0.gguf` | Ask's download (Settings → App & data) — shared |

For development the Whisper folder can be filled over adb, but only after the
app has created it (on Android 16 a folder made by adb under `Android/data` is
unreadable by the app).
