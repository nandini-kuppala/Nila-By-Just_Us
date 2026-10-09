# The camera lane: a scene model on the NPU, day and night

What the safety watch became, why each piece is shaped the way it is, and what it
costs. Everything here ships in the app; nothing described below reaches a
network.

The short version: the always-on watch is unchanged — face, posture and movement,
five frames a second, a few megabytes. On top of it sits a vision model that is
asked *occasionally* what the cot actually contains, a night mode that makes a
dark room legible to it, a set of rules that decides what its answer is worth,
and three things the phone can do about a confirmed hazard before anybody crosses
the room.

> **Status and testing:** this file is the *why*. For what has actually been run
> and passed, what is still unproven, and the step-by-step plan for testing on the
> phone, see [`vision-integration-handover.md`](vision-integration-handover.md).
> The NPU half of this document is built but has not yet been executed on device.

---

## 1. What a parent sees

Put the phone on a shelf facing the cot and start the watch.

- **In daylight**, the watch behaves as it did, and a card on the Watch screen now
  says what the model saw: posture, whether the face is clear, one sentence about
  what the baby is doing, and any hazard in reach.
- **When the room goes dark**, the picture turns into a night view — lifted,
  denoised, drawn in green so it is obvious which picture is being shown — and the
  model keeps working on the lifted frames rather than on a black rectangle.
- **While the baby is asleep**, nothing runs but the cheap lane. The card says
  *Settled — model idle*.
- **When the baby wakes**, moves sharply, cries, or the posture rules see
  something, the model is asked within seconds.
- **If it sees a hazard twice**, the parent is notified, the second phone is
  notified, and — if the parent has turned it on — the phone plays the sound that
  settles this baby and flashes its rear ring light to hold the baby's attention
  for the twenty seconds it takes somebody to get there.

Everything in the last line is off by default. The switches are in
**Settings → What the camera may do**.

---

## 2. The pipeline

```
CameraX frames, ~5 fps, 640 px
   │
   ├─ FaceWatcher (BlazeFace) ─┐
   ├─ PoseWatcher (landmarker) ├─ ActivityRules ─ WatchAlert ─ notification   [unchanged]
   ├─ MotionEnergy ────────────┘
   │
   ├─ NightVision.meanLuma ──┐
   │  RoomSensors.lux ───────┴─ DayNight ─ LightMode.DAY | NIGHT
   │                                        │
   │                                        ├─ night view for the screen (2 fps, only when a screen is open)
   │                                        └─ NightVision.enhance for the model
   │
   └─ WatchPolicy.decide ─ (rarely) ─ NpuVlm on the Hexagon NPU ─ SceneDescriber.parse
                                          │         └─ falls back to FastVLM on CPU/GPU
                                          ▼
                                     HazardRules.assess
                                          │
                                     Confirmer (2 of 3)
                                          ▼
                              notification + second phone + Distraction
```

New files, all under `android/app/src/main/java/com/nila/`:

| File | What it is |
|---|---|
| `vision/DayNight.kt` | day/night decision, with dwell and hysteresis |
| `vision/NightVision.kt` | frame brightness, temporal averaging, auto levels, gamma, the green view |
| `vision/NpuVlm.kt` | Intern3.5-VL-2B on the NPU through GenieX, with the retry loop |
| `vision/HazardRules.kt` | what the model is allowed to call dangerous, and the two-look confirmer |
| `vision/WatchPolicy.kt` | when the model runs at all |
| `vision/VisionSettings.kt` | the four switches |
| `npu/NpuArbiter.kt` | who gets the NPU when the camera and the chat both want it |
| `actions/Distraction.kt` | sound, ring light and torch, with a cooldown |
| `actions/VivoRingLight.kt` | the rear ring, through the phone's own light service |

Changed: `CameraWatch` (keeps the bound camera for the torch, measures brightness,
hands out enhanced frames), `SceneDescriber` (a `HAZARD` line in the form, and the
NPU backend in front of the LiteRT one), `WatchService` (owns the new lane),
`WatchState`, `WatchScreen`, `SettingsScreen`, `NpuLlm` (yields the NPU), and
`Entities` (two new event kinds).

---

## 3. The model, and why it is on the NPU

`SceneDescriber` already ran **FastVLM-0.5B** through LiteRT-LM, with a CPU text
backend and a GPU vision backend. It still does, and it is still the fallback.
In front of it now sits **Intern3.5-VL-2B**, as Qualcomm compiles it for AI Hub:
a 448×448 vision encoder and a 2B language model in four shards, quantised w4a16,
run through the same GenieX SDK the chat model uses but with the `qairt` plugin
instead of `llama.cpp`.

Measured on the phone this app targets:

| | first token | whole answer | resident |
|---|---|---|---|
| Intern3.5-VL-2B, NPU | ~490 ms | ~1.2 s | model in DSP memory |
| FastVLM-0.5B, LiteRT CPU+GPU | ~550 ms | several seconds | ~1.7 GB |

Decoding is greedy (temperature 0). The lane compares consecutive looks before it
raises anything, and a sampler that answers the same frame differently twice
would defeat that comparison.

### The load is a retry loop, and that is not a workaround for our bug

The first `create()` in a process reliably fails on this phone. The bundle needs
the image encoder, four shards and **277 MB of working buffers** in one DSP
session; the first session cannot map all of it, the NPU-side process dies while
growing its heap, and QNN reports `err 1007`. A later attempt lands on a fresh
session and takes the lot. Across every cold start measured, the model loaded on
attempt 2 or attempt 4, in **7.5 to 23 seconds**.

Settings that did *not* change it: `mmap-budget`, `file_read_memory_budget_in_mb`,
encoder `use-mmap`, load order, `extended_udma`. `io_memory_estimation` made it
worse — the model loaded and then failed at run time with `Error 6002`. The real
fix is a smaller-context export of the bundle, which needs an AI Hub compile job;
until then the loop is the fix, and `NpuVlm.State` reports which attempt it is on
so the wait is visible rather than silent.

### Installing the bundle

The bundle is ~2.7 GB and is not downloaded by the app. It goes in either:

```
Android/data/com.nila/files/models/intern-vl/      (external, survives reinstall)
files/local/intern-vl/                             (app-private, adb push)
```

with at least `metadata.json`, `vision_encoder.bin`, `part1_of_4.bin`,
`part4_of_4.bin` and `tokenizer.json` present. With no bundle, `NpuVlm.isInstalled()`
is false, the lane uses the LiteRT model if that is installed, and with neither it
simply never looks — the cheap watch is unaffected either way.

---

## 4. Day and night

### Deciding which it is

Two witnesses, because each is wrong in a different way:

- **The light sensor** is the signal a parent would expect to matter, but it faces
  the ceiling while the camera faces the cot. A lamp behind the phone reads as
  daylight over a cot in shadow.
- **The frame's own mean brightness** is what the model will actually be shown.

Either may call for night; both must agree before the lane returns to day. A
change must persist for **20 seconds** before it is acted on, so the big light
during a nappy change does not re-tune the lane twice. Thresholds: night below
10 lux or mean luma 45; day above 30 lux and mean luma 80. The parent can force
either mode.

### What night mode does to a frame

1. **Temporal averaging** — sensor noise is uncorrelated between frames and a cot
   is not moving, so an exponential average over frames removes most of the grain
   without touching detail.
2. **Auto levels** — the 1st and 99.5th percentiles become black and white.
   Percentiles, not min and max, so one hot pixel or a humidifier LED cannot set
   the white point.
3. **Gamma 0.55** on dark frames, which lifts the midtones where a face sits.

Colour is scaled rather than replaced: each pixel keeps its hue and takes the gain
its brightness earned, capped at ×10 so noise is not multiplied into blocks. The
model is given the colour version — "a red cable" is only sayable if red survives
— and the screen gets the green one, because a grainy grey picture gives a parent
no way to tell an enhanced frame from a broken one.

Cost: about **6 ms** per 640×480 frame, on the analysis thread that already holds
the bitmap. The night view for the screen is produced at most **twice a second**
and only while a preview is attached; with the screen off it is not produced at
all.

---

## 5. When the model runs — the part that protects the battery

A model asked every minute all night is roughly 500 inferences and a phone that
is flat by morning. `WatchPolicy` turns a quiet night into about **30**.

| Situation | Cadence |
|---|---|
| Dark, baby asleep | one look every **15 minutes** |
| Dark, baby awake | every **60 s** |
| Daylight, baby settled | every **5 minutes** |
| Daylight, baby active | every **90 s** |

"Asleep" is nothing clever: motion below 0.6× the scene's own baseline, with no
cry, for two minutes. That is the majority of a night, and during it the lane is
a face detector and a frame difference — exactly what it was before this work.

Immediate looks, subject only to a 12-second floor between looks:

- a **cry starts** (the audio lane has just raised it)
- a **posture or zone event** worth attention — rolled onto the front, left the safe zone, standing
- the **baby wakes**: motion above 2.5× baseline on two consecutive frames
- the parent taps **Look now**

Refusals, for frames that cannot be worth anything: the phone was knocked
(`cameraMoved`), a look is already running, the model is not loaded, or the hourly
budget of 30 looks is spent.

Every number above is in `WatchPolicy.Config` and covered by
`WatchPolicyTest`, which drives a synthetic clock through a night.

---

## 6. Sharing the NPU with Ask

There is one Hexagon block and two tenants: this model and the 2B chat model
behind Ask. **They cannot both be resident in it.** This was first written as "the
second one to arrive fails rather than queues", and the phone proved that wrong in
the worst way: with Qwen built on the NPU, creating the vision model **segfaulted**
inside Qualcomm's HTP stub (`QnnApi::createDevice` → `libQnnHtpV81Stub.so`, null
dereference, 2026-10-09). A native crash takes the whole app down, watch and all,
and cannot be caught from Kotlin. So the rule is enforced before anything reaches
the driver: **one model on the NPU at a time.**

**Vision wins.** A parent asking a question is holding the phone and can wait a
little longer; a baby reaching for a cable cannot. `NpuArbiter` implements exactly
that:

- before the vision model loads, it **claims** the NPU, and every chat engine is
  asked to step off it. The chat engine closes itself on its own thread, under its
  own lock, so it is never closed halfway through a sentence or a build
- a reply already being generated is **never cut off**: the chat engine refuses,
  the vision load is deferred, and the next look tries again seconds later
- while the vision model is resident, the chat model is only ever built on the
  **CPU**. The next question after an eviction rebuilds it there, about 4 s, and
  Ask carries on answering in its own words
- once the watch stops and the vision model closes, the chat model waits **45
  seconds** of quiet and moves back onto the NPU at its next question
- the settings screen reports which processor is answering, rather than claiming
  the NPU and being quietly wrong

Measured on the phone with both models installed: Qwen built on the NPU in 1.2 s;
the watch started; Qwen stepped off in under a second; the vision model loaded in
5.1 s on its **first** attempt (with Qwen still resident it had needed three, and
then crashed); looks ran at ~1.2 s; a question asked during the watch rebuilt Qwen
on the CPU in 3.9 s and answered. One process throughout.

The cost of losing is real and bounded: the same chat model prefills about seven
times slower on the CPU and generates about a third slower. An answer still
arrives.

---

## 7. What the model is allowed to say

The model fills in a form with closed vocabularies — `POSITION`, `FACE`,
`CONCERN`, `HAZARD`, `DOING` — because an open caption from a small model is
fluent and unfalsifiable, and this is a camera pointed at an infant.

`HAZARD` is free text, and `HazardRules` decides what it is worth:

| The model says | The app does |
|---|---|
| a hazard it then negates ("none", "no immediate danger") | nothing |
| water, with an adult holding the baby | nothing — that is a bath |
| "a small object near the mouth" in a sentence that also says toy, biscuit or spoon | holds back as unsure |
| a hazard outside the list, and says DANGER | **raises it** as "other", and says it was unrecognised |
| SAFE, but names stairs, electrical, heat, sharp or poison | **raises it anyway** |

Then the confirmer: **two of the last three** looks must agree before anybody is
woken. One look at one frame of a dim nursery is exactly what a 2B model is worst
at, and a monitor that cries wolf twice is a monitor that gets switched off
before the night it is right.

Measured on the eighteen labelled photographs in `ml/vision-eval/`:

| | caught | missed | false alarms | correct |
|---|---|---|---|---|
| Free-text prompt | 7 | 3 | 4 | 11/18 |
| The form | 9 | 1 | 3 | 14/18 |
| The form + these rules | 9 | 1 | 0 | **17/18** |

The rules were written after seeing those answers, so 17/18 is a floor on a set
they have seen rather than an estimate for a new room. The remaining miss is a
baby climbing onto a sink that the model describes as looking in a mirror.

---

## 8. Acting, not only notifying

A confirmed hazard notifies the parent and the second phone first, because that
path cannot fail. Then, if **Try to distract the baby** is on, the phone makes
itself more interesting than whatever the baby is reaching for:

- **Sound** — chosen by `SootheMemory`, which already knows which sound settles
  *this* baby, including the caregiver's own recorded voice.
- **The rear ring light** — saturated colours cycling every 180 ms. A baby tracks
  a changing colour far longer than a steady one.
- **The torch**, only if the parent switched it on as well: 400 ms pulses. It is a
  bright light into a cot, right for a crawler at a socket in daylight and wrong
  for a drowsy baby at 3am, so it is a separate switch.

It stops itself after 20 seconds, will not run again inside 90 seconds, and writes
a `DISTRACTION_PLAYED` row so the timeline says what the baby was offered.

### The ring light, and why it is reflection

vivo exposes the ring through a notification, and the system **ignores
notification lights while the screen is on and unlocked** — which is exactly the
state of a phone propped up watching a cot. The same light service accepts a
record built from colour segments, and a record marked as a *preview* skips that
suppression. Those classes are hidden API, so every call goes through reflection
and every failure is swallowed: on a non-vivo phone, or a firmware that moved
them, `VivoRingLight.available` is false and the alert is the sound, the torch and
the notification.

---

## 9. What this does not do

- It is **not a breathing, heart-rate or vital-signs monitor**, and nothing here
  says whether a baby is alive or well. It reports posture, visibility and what is
  in reach.
- **No camera sees in a completely dark room.** Night mode needs some light — a
  night light, or light under a door. There is no infrared camera in this phone
  and no infrared light.
- The hazard list is **what the model can name**. A hazard it describes as
  something else is caught only when it still says danger; a hazard it does not
  mention at all is missed, as `d10` is.
- The scene model is a **second opinion, not a sensor**. Between looks the watch
  is the same cheap lane it always was.

---

## 10. Where the numbers come from

Latency, load behaviour, the NPU session limit, the night-mode measurements and
the 11/14/17 progression were measured on an iQOO 15 (Snapdragon 8 Elite Gen 5,
Hexagon v81) during the experiments that preceded this integration. The labelled
photographs, the scoring script and the raw per-photograph results are in
`ml/vision-eval/`, so the accuracy number can be reproduced without a phone.

Tests covering the logic, all on the JVM:

```
android/app/src/test/java/com/nila/vision/DayNightTest.kt       day/night, dwell, overrides
android/app/src/test/java/com/nila/vision/HazardRulesTest.kt    every guard rail, as a photograph
android/app/src/test/java/com/nila/vision/WatchPolicyTest.kt    a night driven through a synthetic clock
android/app/src/test/java/com/nila/npu/NpuArbiterTest.kt        vision priority, chat on CPU, the grace period
```
