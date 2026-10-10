# Doctor visit — the lab

**Status: integrated into Nila as the Doctor tab** (`android/app/.../com/nila/visit`
and `ui/screens/VisitScreens.kt`). This folder stays as the lab: a standalone
Android project (`com.nila.visit`, "Nila Visit Lab") for trying changes and
measuring them headlessly before they go into the app. In the app, Whisper Turbo
is downloaded from AI Hub and unpacked into `files/models/whisper-turbo`, the
notes reuse Ask's GGUF, and the NPU is handed over through `NpuArbiter.claimForVisit`.

## The idea

At the paediatrician a parent hears a lot and remembers little: two medicines
with different timings, a date for the next vaccine, "check the colour of the
poop before you come back", what not to give. Put the phone near the doctor,
tap record, and when the visit ends Nila writes it all down in the places a
parent will look:

| Section | What goes in it | What Nila does with it |
|---|---|---|
| **Medicines** | name, amount *as the doctor said it*, how often, for how long, "only if…" | reminders at those times for that many days; nothing for "only if" medicines |
| **Dates** | next visit, vaccines, tests — with the day worked out | calendar entries; a reminder the evening before and on the morning |
| **Watch for** | signs to check, flagged when the doctor said "come at once" | — |
| **Do / Don't** | the doctor's do's and don'ts | — |
| **Summary** | one sentence: what the doctor found | — |

Every item carries the **doctor's own words** it came from, shown underneath it.
That is the feature's honesty rule, the same one the main app keeps: it writes
down what was said, it never advises, and anything it cannot trace to the
transcript is dropped before the parent sees it.

## How it works

```
 microphone / WAV ──► Whisper (NPU) ──► transcript ──► Qwen3.5-2B (NPU) ──► notes ──► reminders + calendar
                     speech → text     shown as-is     3 narrow passes      guards      plain code
```

**One model on the NPU at a time.** Whisper holds the Hexagon NPU while there
is audio to read, then releases it completely; only then is Qwen loaded. The
notes can't be written before the doctor finishes anyway, and the main app
learned in 2.5.1 that two models resident on the NPU can kill the process.

### Speech to text — Whisper Large-v3 Turbo, from Qualcomm AI Hub

- AI Hub's `precompiled_qnn_onnx` export, **v0.62.0**, for Snapdragon 8 Elite
  Gen 5. That release is built with **QAIRT 2.45**, the same runtime GenieX 0.4.0
  ships, so speech and chat share one set of Hexagon libraries (the newer v0.64
  exports need QAIRT 2.50).
- Run by ONNX Runtime 1.27 with the QNN execution provider (`qnn-runtime`
  pinned to 2.45.0).
- `tools/patch_whisper.py` changes the wrappers' uint16 tensors to float (ONNX
  Runtime's Java API has no uint16), keeping the plain tensor names at the
  EPContext node — the names compiled into the QNN graph.
- Features: a Kotlin port of WhisperFeatureExtractor (JTransforms FFT), held to
  a numpy reference to within 3e-5 by `MelSpectrogramTest`.
- Decoding: Qualcomm's own greedy loop, plus a language token
  (detected or chosen), no-repeat-4-gram and triple-repeat blocks.
- Audio is cut **at the speaker's pauses** into pieces of whole phrases —
  ≤ 25 s for English, ≤ 8 s for Indian scripts. Whisper tends to stop at the
  first full stop of a piece, and a Hindi phrase costs several times the tokens
  of an English one against a 200-token decoder.

### Text to notes — Qwen3.5-2B, the model Ask already runs

- Same GGUF and GenieX calls as the app, on the NPU, near-greedy, engine reset
  before every generation.
- **Three narrow passes** — medicines, dates, advice — instead of one big form.
- **Guards**, all in plain code and JVM-tested:
  - quote must be in the transcript (content words, ≥ 70 %)
  - a medicine's name must be in an English transcript
  - an amount is shown only in the doctor's own words, and for English only
    when its numbers match the quote's (`Numbers`: "zero point six" = 0.6,
    "two and a half" = 2.5)
  - "only if" read from the doctor's words in English, Hindi, Tamil, Telugu
  - a "date" whose day can't be worked out is dropped
- Dates are resolved by `WhenResolver`, not the model ("after three days",
  "on the eighth of December", "next Monday at 9", "at six months" from birth).

## Results on the iQOO 15 (Snapdragon 8 Elite Gen 5)

Synthetic visits in `testdata/visits.json`, voiced by the Mac's own voices
(`tools/make_audio.py`) and read from files — nothing is played on the phone.

**Speech to text, Whisper Large-v3 Turbo on the NPU**

| Visit | Language | Error rate |
|---|---|---|
| loose motions | English | 3.3 % words |
| mother postnatal | English | 5.3 % |
| jaundice | Hinglish | 7.4 % |
| 4-month check-up | English | 7.9 % |
| fever | Hindi | 10.2 % characters |
| vaccine | Tamil | 21.4 % |
| feeding | Telugu | needs the language chosen; auto-detect hears Tamil |

Speed: encoder ~1.1 s per piece, decoder ~5 ms a token; a 40 s English visit
transcribes in ~8 s. Model load ~1.6–6 s.

**Notes** — see the latest table in the session log; English is the strong
case, Tamil and Telugu are labelled experimental because a 2B model reads
those scripts poorly. Whisper Small's speech-to-English translation was tried
as a pivot for them and rejected — it invents content.

## Running it

```
cd doctor-visit
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nila.visit/.MainActivity     # creates its own folders
```

Models go in `/sdcard/Android/data/com.nila.visit/files/models/` (copy with
`adb shell cp`, into folders the app created — on Android 16 a folder made by
adb is unreadable by the app):

```
models/whisper-turbo/   AI Hub whisper_large_v3_turbo_quantized v0.62.0 + encoder_f.onnx, decoder_f.onnx
models/whisper-small/   AI Hub whisper_small_quantized v0.62.0 + patched wrappers
models/Qwen3.5-2B-Q4_0.gguf
testdata/visits.json, testdata/audio/*.wav
```

Headless runs (report in `files/reports/`, log tag `VisitLab`):

```
adb shell am start-foreground-service -n com.nila.visit/.lab.LabService \
    --es case all --es model turbo --es mode audio|transcript|asr|translate|cycles --es lang case
python3 tools/score_asr.py <pulled reports>     # word / character error rate
python3 tools/score_notes.py <pulled reports> v # medicines, dates, advice vs expected
```

## Known limits, honestly

- **vivo freezes the app in the background** within seconds (seen as
  `am_app_frozen ... pem_freezer` mid-generation). The lab keeps its screen on
  while it works; the integrated feature must run its processing as a
  foreground service *and* ask the parent to exempt Nila from vivo's
  background power management.
- Tamil and Telugu notes are experimental (see above).
- Synthetic voices are cleaner than a clinic. Real visits — a fan, a crying
  baby, two people talking — are the next test.
- This records instructions; it does not check them. Every medicine card says
  to check the amount against the prescription.
