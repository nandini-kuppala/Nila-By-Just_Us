# Vision integration — status, test record and handover

**Written:** 2026-10-04 · **Updated:** 2026-10-09 · **Released in:** `v2.5.0` (commits
`4f61aff`..`264ddf7` on `main`). The GitHub Release itself is still pending -- see §9.

This is the handover for the camera-lane upgrade. It says what was built, how it
plugs into the app, what has actually been run and passed, what has **not** been
run, and the exact steps to finish the job once the iQOO 15 is plugged in.

The design rationale lives in [`vision-npu-night.md`](vision-npu-night.md); this
file is the status and test record. Where the two disagree, this one is newer.

---

## 1. Status at a glance

| | |
|---|---|
| Integration | **Complete.** Every requested capability is in the app and compiles. |
| Build | `assembleDebug` succeeds; the x86_64 split installs and runs. |
| JVM tests | **386 pass, 0 fail** (34 of them new). |
| Device tests | **6 pass** on an Android 35 emulator. |
| Hazard rules, offline eval | **17/18**, 0 false alarms. |
| Emulator run-through | No crash in any screen or transition. |
| Phone | Debug build installed on the iQOO 15 `10BFAU1X7Z000XR`, all four models in place and checksum-verified (§6.3). |
| **NPU vision model** | **Runs on the phone.** Loads in 5 s, ~1.2 s a look, inside the watch. Needed GenieX 0.4.0 (§10). |
| **NPU shared with Ask** | **Works, after a crash was found and fixed** (§10): one model on the NPU at a time. |
| **Ring light, soother** | **Work on the phone** (ring light needed a fix, §10). |
| **Torch strobe, full hazard chain, overnight** | Not run yet. §5 is the list. |

The honest summary, as of 2026-10-09: *the NPU half now runs on the phone, and
getting it there found three bugs no emulator could have shown* (§10). Section 5
is what remains.

---

## 2. What was asked for, and where it went

| Requirement | Where it lives | Lines |
|---|---|---|
| Day and night modes | `vision/DayNight.kt` | 114 |
| Automatic switching between them | same — two witnesses + a 20 s dwell | |
| Night vision on the picture | `vision/NightVision.kt` | 204 |
| Vision primarily on the NPU | `vision/NpuVlm.kt` (Intern3.5-VL-2B, GenieX) | 363 |
| Vision beats chat for the NPU; chat falls back to CPU | `npu/NpuArbiter.kt` + `assistant/NpuLlm.kt` | 140 |
| Action describing | `vision/SceneDescriber.kt` — the four-field form | +104 |
| Danger alerts | `vision/HazardRules.kt` + the confirmer | 199 |
| Distraction: sound/song | `actions/Distraction.kt` → `SoothePlayer` | 152 |
| Distraction: ring light | `actions/VivoRingLight.kt` | 148 |
| Distraction: flash | `Distraction.torchControl` → `CameraWatch.torch()` | |
| Don't waste the model; sleep detection at night | `vision/WatchPolicy.kt` | 222 |
| Parent-facing controls | `vision/VisionSettings.kt` + Settings screen | 90 |
| The data for the folder | `ml/vision-eval/` — 18 images, labels, scorer | |
| Documentation | `docs/vision-npu-night.md`, this file | |

Modified to carry it: `monitor/WatchService.kt`, `vision/CameraWatch.kt`,
`vision/SceneDescriber.kt`, `vision/WatchState.kt`, `assistant/NpuLlm.kt`,
`data/Entities.kt`, `ui/screens/WatchScreen.kt`, `ui/screens/SettingsScreen.kt`.
Total: **854 insertions across 8 modified files**, plus 9 new source files.

---

## 3. How it is integrated

The existing cheap lane was not touched in its essentials. The model was added
*beside* it as a second opinion, which is why a missing model degrades to the old
behaviour rather than breaking the watch.

```
CameraX ImageAnalysis (5 fps, unchanged)
   |
   |- FaceWatcher / PoseWatcher / motion ------> the old watch, still authoritative
   |                                             (posture, movement, safe zone)
   |- NightVision.meanLuma(frame) --+
   |                                +--> DayNight.update(lux, luma, now) --> DAY | NIGHT
   |  light sensor -----------------+          two witnesses, 20 s dwell
   |
   +- WatchPolicy.decide(motion, crying, event, mode, asleep)
            |  returns a Reason, or null for "the cheap lane is enough"
            v
      WatchService.look()
            |  snapshotFor(mode) - NightVision.enhance() first if NIGHT
            v
      SceneDescriber.lookNow()
            |  NpuVlm (NPU) --> falls back to LiteRT/MediaPipe --> null if neither
            v
      HazardRules.assess()  ->  Confirmer (same danger 2 of 3)
            |
            |-> Notifier  (always)
            |-> DB: HAZARD_SEEN
            +-> Distraction: soother + ring light + optional torch (opt-in)
```

**Key integration decisions, and why:**

- **GenieX stays at 0.3.5.** Nila's pinned version already ships `VlmWrapper`,
  `VlmCreateInput`, `VlmChatMessage` and `VlmContent`, so no SDK bump was needed.
  A bump would have put the working Ask pipeline at risk for no gain.
- **Night is done in software, not with the vivo Night camera extension.** That
  extension needs CameraX 1.6 and `compileSdk 36`; the app is on CameraX 1.4.1 and
  `compileSdk 35`. Upgrading both to get one vendor mode was not worth
  destabilising a stable app.
- **The torch is owned by whoever holds the camera.** `WatchService` hands
  `Distraction` a `torchControl` lambda rather than letting it open its own
  camera, so the two cannot fight for the device.
- **A chat reply in flight is never cut off.** Vision waits `VISION_WAIT_MS` (2 s)
  and then skips that look rather than killing a half-written answer; chat waits
  `GRACE_MS` (45 s) of quiet before it reclaims the NPU.
- **The model is not a sensor.** `WatchPolicy` runs it on events (cry, posture,
  motion wake) and otherwise on a cadence that stretches to 15 minutes when a dark
  room has been still for two minutes. A capped 30 looks per rolling hour is the
  battery's last defence.

---

## 4. What has been tested, and how

### 4.1 JVM unit tests — 386 pass, 0 fail

```bash
cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks
```

34 of those are new:

| Test class | Tests | What it pins down |
|---|---|---|
| `vision/WatchPolicyTest` | 12 | first look, cry, posture, motion wake, cadences, the hourly cap, sleep entry/exit |
| `vision/HazardRulesTest` | 10 | one row per case in `ml/vision-eval/labels.csv` |
| `vision/DayNightTest` | 8 | both witnesses, the dwell, all three preferences |
| `npu/NpuArbiterTest` | 4 | vision pre-empts, chat falls to CPU, the grace window |

### 4.2 Device tests — 6 pass on the emulator

`androidTest/java/com/nila/NightVisionDeviceTest.kt`, written because nothing
covered the two classes that cannot run on the JVM (`Bitmap` is a stub there, and
DataStore needs a filesystem).

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nila.NightVisionDeviceTest
```

Covers: a dark frame is lifted >1.5x; repeated frames of one scene settle rather
than flicker; `nightView` is green-dominant with red == blue; a frame of a new
size does not crash the accumulator; settings survive a DataStore round-trip; an
override beats a bright room.

> **Note:** `connectedDebugAndroidTest` **uninstalls the app afterwards**.
> Reinstall before carrying on by hand.

### 4.3 Hazard rules against real photographs — 17/18

```bash
cd ml/vision-eval && python score.py answers-2026-09-13.csv
# caught 9 of 10 dangers, 0 false alarms, 17/18 correct
```

Progression on the same 18 photographs: free-text prompt **11/18** → the
four-field form **14/18** → the form plus guard rails **17/18**. The guard rails
remove every false alarm and keep every catch. The one miss is
`d10_climbing_onto_sink.jpg`, where the model never names the climbing — no rule
can rescue a hazard that was not mentioned.

The rules were written after seeing these answers, so 17/18 is a floor on a seen
set, not an estimate for new rooms.

### 4.4 Emulator run-through — no crash anywhere

AVD `nila35`, `system-images;android-35;google_apis;x86_64`, booted with
`-camera-back emulated`. Screenshots in
`C:\Users\visha\Desktop\babytest\emulator-shots\`.

| Checked | Result |
|---|---|
| Launch, Watch start/stop, Settings, tab navigation | **0 `FATAL EXCEPTION`** across the whole session |
| Auto day/night | The emulated light sensor reads **0 lx**; after the 20 s dwell the chip went **Daylight → Night vision** and the preview became the green `nightView()` render |
| Night overrides | Automatic / Always on / Off all take effect and persist across a reinstall |
| Distraction setting | Switching it on reveals the "Use the flash as well" sub-switch |
| Safe zone and alerts | Border drawn; "Out of view of the camera / URGENT" raised; **Safety alert notification posted** |
| MediaPipe absent | `libmediapipe_tasks_vision_jni.so` will not load on x86_64 → `CameraWatch` degrades to face + movement and shows "Posture is unavailable on this phone". **No crash.** |
| No model bundle | `SceneCard` shows "No model installed", "Look now" is disabled |

**A bug the emulator found and that is now fixed:** "Look now" with no bundle
installed did nothing at all — no message, no log, a button that read as broken.
`SceneDescriber.anyModelInstalled` now feeds `WatchState.visionModelInstalled`,
and the card shows a chip, disables the button and says why.

**A hardening the emulator forced:** `NpuVlm.start()` wraps `initSdk()` in
`runCatching`, because a device with no Hexagon libraries throws
`UnsatisfiedLinkError` — an `Error`, not an `Exception`, so a bare `catch` would
have missed it and taken the app down.

---

## 5. What is NOT tested — the work for the next session

Everything here needs the iQOO 15. None of it can run on an emulator: the x86_64
APK contains 11 native libraries (TFLite, MediaPipe, ONNX, LiteRT-LM, ML Kit) and
**no GenieX**, because Hexagon is physical silicon.

| # | Untested | Why it matters | How to test it |
|---|---|---|---|
| 1 | ~~`NpuVlm` — Intern3.5-VL-2B on the NPU~~ | **Done 2026-10-09.** Failed on GenieX 0.3.5, runs on 0.4.0 | §10 |
| 2 | ~~The load-retry loop~~ | **Done.** 3 attempts with Qwen resident; 1 attempt once Qwen steps off first | §10 |
| 3 | ~~`NpuArbiter` under real contention~~ | **Done, and it crashed the app first.** Fixed with the residency rule | §10 |
| 4 | ~~`VivoRingLight`~~ | **Done.** Was unreachable until the hidden-API exemption was restored | §10 |
| 5 | **Torch strobe during distraction** | Emulator has no flash unit; the phone rejects strobes faster than ~300 ms | §6.5 |
| 6 | ~~Soother playback through `Distraction`~~ | **Done.** Played (media volume muted for the test) | §10 |
| 7 | **The full hazard chain** | Each link is tested; look → rules → confirmer → notify → `HAZARD_SEEN` → distract has never run end to end, because it needs a model to start it | §6.6 |
| 8 | **`NightVision.enhance()` on a real dark nursery** | Only ever seen synthetic emulator frames and the unit fixtures | §6.2 |
| 9 | **Duty-cycle battery claim over a night** | The whole justification for `WatchPolicy`; unmeasured | §6.7 |
| 10 | **`WatchPolicy` against a real baby** | Thresholds (`wakeMotionRatio` 2.5, `stillRatio` 0.6) came from reasoning, not from footage | §6.7 |

---

## 6. The on-phone test plan

### 6.1 Setup

Device: iQOO 15 (I2501), Snapdragon 8 Elite Gen 5 (SM8850), Hexagon NPU v81,
Android 16, 1440x3168, serial `10BFAU1X7Z000XR`.

> **This is not the phone the experiments ran on.** Those used another iQOO 15,
> serial `10BFAX1BU20010U`. Same model and chip, so the findings should carry over,
> but nothing measured on that unit has been re-measured on this one yet.

Install the **debug** build. It is not minified, so a crash gives a readable stack
trace, and it is debuggable, which is what lets `run-as` copy the models in
(§6.3). The release build built on this laptop is signed with the same key, so
switching to it later is an ordinary update that keeps the data.

```bash
export MSYS_NO_PATHCONV=1              # Git Bash: stops path mangling in adb
export ANDROID_HOME="C:\\Users\\visha\\AppData\\Local\\Android\\Sdk"
adb devices                            # expect 10BFAU1X7Z000XR

cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

> The repo's `local.properties` is gitignored, so `ANDROID_HOME` must be exported
> or Gradle fails with "SDK location not found".

### 6.2 Night vision on a real dark room

Turn the lights off. Start the watch. Expect: the chip flips to **Night vision**
within ~20 s, and the preview shows the lifted green render. Check the picture is
usable, not just bright — if it is a noise storm, raise `blendWeight` from 0.45;
if it smears a moving baby, lower it.

### 6.3 The NPU model — the first thing to do

**Done on 2026-10-09 and verified byte-for-byte.** This is the procedure that
worked; repeat it after any reinstall that clears the app's data.

The bundle is AI Hub's `GENIEX_QAIRT` export of Intern3.5-VL-2B, w4a16, 4096
context, built for this exact chip. On this laptop it is at:

```
C:\Users\visha\Desktop\babytest\models\intern35vl2b\extracted\
    intern3_5_vl_2b-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5\
```

24 files, 2.75 GB. `NpuVlm.isInstalled()` looks for five of them:
`metadata.json`, `vision_encoder.bin`, `part1_of_4.bin`, `part4_of_4.bin`,
`tokenizer.json`.

```bash
B=$(cygpath -w "/c/Users/visha/Desktop/babytest/models/intern35vl2b/extracted/intern3_5_vl_2b-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5")
adb shell mkdir -p /data/local/tmp/intern-vl
adb push "$B\\." /data/local/tmp/intern-vl/          # ~77 s at 34 MB/s
adb shell am force-stop com.nila
adb shell "run-as com.nila sh -c 'mkdir -p files/local/intern-vl && cp -r /data/local/tmp/intern-vl/. files/local/intern-vl/'"
```

Verify, don't assume. The checksums of all eight large files on the phone matched
the laptop's exactly:

| File | SHA-256 |
|---|---|
| `vision_encoder.bin` | `13030883c3b0…` |
| `part1_of_4.bin` | `6a6360c190d8…` |
| `part2_of_4.bin` | `c629acad572a…` |
| `part3_of_4.bin` | `40788bfba236…` |
| `part4_of_4.bin` | `36573322203d…` |
| `embedding_weights.raw` | `62369bc01dfb…` |
| `tokenizer.json` | `6581c44164d2…` |
| `metadata.json` | `90c31029f000…` |

```bash
adb shell "run-as com.nila sh -c 'cd files/local/intern-vl && sha256sum *.bin *.raw'"
```

Three things that will waste time if forgotten:

- **Why internal and not `Android/data`.** On Android 16, files adb pushes into
  `/sdcard/Android/data/com.nila/` can be invisible to the app. `run-as` writes as
  the app itself, so there is no doubt.
- **Never create an empty `Android/data/com.nila/files/models/intern-vl/`.**
  `bundleDir()` prefers that folder whenever it *exists*, before checking it has
  anything in it, and the app would then report the model missing while the
  complete internal copy sits unused.
- **`run-as` needs the debug build.** The release build is not debuggable.

The staging copy in `/data/local/tmp/intern-vl` can be deleted once the first look
has worked: `adb shell rm -rf /data/local/tmp/intern-vl`.

### The other three models

All on the phone as of 2026-10-09, each checked against Hugging Face's own SHA-256
(the `X-Linked-ETag` header), on the laptop after download and again on the phone.

| Model | For | Where on the phone | SHA-256 |
|---|---|---|---|
| `Qwen3.5-2B-Q4_0.gguf`, 1,214,873,856 B | Ask, on the NPU | `files/local/` (internal, via `run-as`) | `cd70221bebae…` |
| `fastvlm-0.5b.litertlm`, 1,156,342,768 B | Vision fallback | `Android/data/com.nila/files/models/` | `ccba1e8bfa0b…` |
| `qwen2.5-0.5b-instruct.task`, 546,660,344 B | "Shorter answers" | `Android/data/com.nila/files/models/` | `e608953f169a…` |

Laptop copies are in `C:\Users\visha\Desktop\babytest\models\`.

The two in `Android/data` cannot go in with `run-as`: on this phone the app's
`run-as` shell does not see `/sdcard` at all. A plain `adb shell cp` into that
folder works, and the app reads the result -- Settings shows "Shorter answers:
Installed".

```bash
adb push Qwen3.5-2B-Q4_0.gguf /data/local/tmp/
adb shell "run-as com.nila sh -c 'mkdir -p files/local && cp /data/local/tmp/Qwen3.5-2B-Q4_0.gguf files/local/'"
adb push fastvlm-0.5b.litertlm qwen2.5-0.5b-instruct.task /data/local/tmp/
adb shell "cp /data/local/tmp/fastvlm-0.5b.litertlm /data/local/tmp/qwen2.5-0.5b-instruct.task /sdcard/Android/data/com.nila/files/models/"
adb shell rm -f /data/local/tmp/*.gguf /data/local/tmp/*.litertlm /data/local/tmp/*.task
```

A download straight from Hugging Face can stop partway (it did, at 152 MB, with
`curl: (56)`) and still leave a file behind. Compare the size and the hash, never
just the exit code.


Then start the watch and tap **Look now**.

```bash
adb logcat -s NpuVlm:V SceneDescriber:V WatchService:V NpuArbiter:V
```

Expect `npu reply in NNNNms: ...`. Record: how many of the 8 retry attempts the
load took, and the per-look latency.

**If the load fails every attempt:** the known cause is the NPU session's 277 MB
IO-buffer limit — the first cDSP session cannot map the encoder plus 4 shards,
which shows as `fastrpc_mmap_validate failed`, QNN `err 1007`, `memRegister 8002`.
QNN usually succeeds on a later attempt by opening session 2 (effective domain 11).
**Do not spend the session chasing this** — it was a long dead end before. If 8
attempts will not do it, fall back to a smaller ready-made NPU model.

### 6.4 NPU arbitration

Start the watch, then ask a question in **Ask** while a look is due. Expect:

- Vision wins; `NpuLlm` logs that it picked the CPU unit.
- A chat reply already generating is **not** cut off — vision skips that look.
- 45 s after the chat goes quiet, chat may take the NPU back.

Watch `NpuArbiter` and `NpuLlm` in logcat. Check the reply is not visibly slower in
a way a parent would notice.

### 6.5 The light and sound outputs

> Sound here is Nila's soother, which was explicitly requested for distraction.
> The no-sound rule applies to the **babytest Baby Guard** app, not to Nila.

Settings → *What the camera may do* → turn **Try to distract the baby** on, then
tap **Distract** on the Watch screen. Expect: the soother plays, the ring light
flashes the playful 8-colour neon cycle at 180 ms, and — if the flash switch is on
— the torch strobes at 400 ms.

Ring-light notes that cost time to find: colours must be `0xRRGGBB` (a value ≤ 0 is
read as unset), and the record is
`VivoLightRecord(0, pkg, lightType=502, isPreview=true, cycle=true, durationMs)`
with `ConstantDurSegment(rgb, stepMs)`. Notification lights are suppressed while
the screen is on and unlocked — **test with the screen off**.

### 6.6 The full hazard chain

With the bundle installed, put a hazard in frame (the staircase and cable photos in
`ml/vision-eval/images/danger/` on a second screen will do). Expect:

1. Two looks naming the same hazard (the confirmer wants 2 of 3).
2. A notification.
3. A `HAZARD_SEEN` row in the database.
4. Distraction firing, if it is switched on.

Then run the safe set and confirm **no** alarm — false alarms are the failure mode
that gets a monitor switched off.

### 6.7 Overnight

Leave it watching a cot overnight with the screen off. Record battery at start and
morning. Count looks (`adb logcat -s WatchService | grep "look ("`) and check the
count respects `maxPerHour = 30`. Confirm the model was idle while the baby was
still — that is the whole economic argument of `WatchPolicy`.

### 6.8 Collecting new eval data

Every look is logged. Harvest `image,reply` pairs into a CSV and score them:

```bash
adb logcat -s WatchService | grep "look ("
cd ml/vision-eval && python score.py answers.csv
```

New photographs are the only way to improve on 17/18. The set needs more *near
misses* — baths, teething toys, meals — than more staircases, because confident
alarm is the failure mode of a small vision model. `score.py` is a direct port of
`HazardRules.kt`; change one, change the other, and run both.

---

## 7. How this came about — the session record

Condensed from the working sessions, so a cold session has the context without the
transcript.

### 7.1 The two workspaces

- `C:\Users\visha\Desktop\babytest` — **experimentation only.** Where the NPU work,
  the ring light and the hazard rules were figured out. Not the product.
- `C:\Users\visha\Desktop\Nila-By-Just_Us` — **the real repo.** Ask was already
  integrated and current; the vision part was still the initial version. This
  integration brought the experiments across.

### 7.2 The experiments that fed this

1. **Intern3.5-VL-2B on the NPU.** Got it running through GenieX. The load needs
   several attempts; the cause is the session IO-buffer limit, not our code. The
   instruction after a long investigation was explicit: don't get stuck in that
   loop. Hence a bounded 8-attempt retry rather than a fix.
2. **Prompting.** A free-text prompt scored 11/18. Forcing a four-field form
   (`SEES/DOING/HAZARD/SAFE`) took it to 14/18. Guard rails over the hazard line
   took it to 17/18 with zero false alarms.
3. **The ring light.** Broke, then was fixed, and was confirmed working. The
   findings (colour format, the record shape, screen-on suppression) are in §6.5
   and in the babytest README.
4. **Night mode.** Flash and external light were ruled out as unusable on a
   sleeping baby. The vivo Night camera extension exists but needs CameraX 1.6 +
   compileSdk 36, so software enhancement was chosen instead.
5. **Safe zone.** A drawn line with a cheap geometric check, deliberately *not* the
   vision model — it does not need one.
6. **A separate night variant app** was built in babytest rather than modifying the
   working one, by instruction.

### 7.3 Rules that were set and still hold

- **No sound in the babytest Baby Guard app.** Absolute: no melody, no
  `AudioTrack`/`MediaPlayer`, no alarm volume, no vibration. Alerts there are light
  only. **This does not apply to Nila**, where a soother for distraction was
  explicitly requested.
- **Do not commit or push** anything in Nila until told to. Still in force.
- **Do not destabilise the app.** The app is stable; the integration must not crash
  it. This is why the model lane is additive and every failure degrades to the old
  behaviour.
- **The night variant and safe zone work belongs in the new babytest app**, never in
  `baby-guard-npu`.

### 7.4 Bugs found and fixed during integration

| Bug | Fix |
|---|---|
| `WatchPolicy.decide` applied `minGapMs` before any look had happened, so the first look was blocked and the watch was blind for 12 s | Guard the gap check with `everLooked` |
| `lastLookAt == 0` and `stillSince == 0` both meant "never", but 0 is a valid timestamp — "the watch started at t=0" became "still since the beginning of time" | `NEVER = Long.MIN_VALUE` sentinel |
| `score.py` reported 14/18 instead of 17/18 | The archived answers use `ACTIVITY:` where the app emits `DOING:`; the rule context was empty. `doing()` now reads either |
| "Look now" silently did nothing with no bundle installed | "No model installed" chip, disabled button, an explanation |
| `UnsatisfiedLinkError` on a device with no Hexagon libraries would have escaped a bare `catch` | `runCatching { initSdk() }` |

### 7.5 Environment notes that cost time

- `export MSYS_NO_PATHCONV=1` and `cygpath -m` are needed for adb under Git Bash.
- `ANDROID_HOME` must be exported; `local.properties` is gitignored.
- Android 16 can hide adb-pushed files in `Android/data` from the owning app.
- On the phone, another tool was clearing logcat — the babytest app writes its own
  log file as a result.
- The phone rejects a torch strobe faster than about 300 ms.
- `connectedDebugAndroidTest` uninstalls the app when it finishes.

---

## 8. First five minutes of the next session

1. `adb devices` — confirm `10BFAU1X7Z000XR`.
2. 2.5.0 debug is already installed, and so are both models (§6.3). Rebuild and
   reinstall only if the code has changed since `264ddf7`.
3. Unlock the phone, open **Watch**, start the watch, and confirm the "No model
   installed" chip has gone.
4. Tap **Look now** with `adb logcat -s NpuVlm:V SceneDescriber:V` running. That one
   tap settles items 1 and 2 of §5.
5. Work down §6.4 → §6.7.

Commit to this repo as **Vishal-AS-0407**. It is set in the repo's local git
config; this laptop's global identity is a different, unlinked one.

---

## 9. Releasing — why v2.5.0 has a tag and no GitHub Release yet

`v2.5.0` is pushed and CI on `main` passed. Pushing the tag also starts
`.github/workflows/release.yml`, which builds the three APKs and publishes them. It
failed at **"Restore the signing key"**: the repository secret
`ANDROID_DEBUG_KEYSTORE_BASE64` has never been set. It failed the same way for
`v2.3.0` and `v2.4.0`, which is why those were published by hand.

The key matters more than it looks. Every release so far is signed with **one
particular debug keystore** (SHA-256 `628f29acd2f4…`), and Android will not install
an update signed with any other key. A user would have to uninstall first, which
deletes the care log, the health documents and the recordings. The workflow knows
this: it pins `EXPECTED: 628f29…` and refuses to publish anything else. This
laptop's debug key is `0328e754…`, so **an APK built here must never be published as
a release.**

`v2.4.0` was published by `nandini-kuppala`, so the original keystore is on her
machine. Adding an Actions secret needs the repo owner (Vishal-AS-0407 has write,
not admin). Once she does:

1. On the machine that built 2.4.0, copy the key as base64:
   - Windows: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard`
   - macOS: `base64 -i ~/.android/debug.keystore | pbcopy`
2. Repo → **Settings → Secrets and variables → Actions → New repository secret**,
   named `ANDROID_DEBUG_KEYSTORE_BASE64`.
3. Re-run the failed **Release** run for **`v2.5.1`** (not `v2.5.0` -- see §10.6).
   Write access is enough for this.

The workflow publishes with `--generate-notes`. The written notes for 2.5.0 are
kept at `C:\Users\visha\Desktop\nila-2.5.0-release-notes.md`; put them over the
generated ones once the release exists.

After that, every tag publishes itself.

---

## 10. On the phone, 2026-10-09 — what it found

The first run on real hardware. Three bugs, all in the integration, none visible on
an emulator, all fixed and re-run on the phone.

### 10.1 The vision model could not load at all — GenieX 0.3.5

Every attempt failed in ~3 ms, which is too fast to be the NPU. The native log:

```
dispatch: no VLM factory matches model_id 'intern3_5_vl_2b'
```

The integration kept Nila on **GenieX 0.3.5** because that version already has
`VlmWrapper`, and that was taken to mean it could run this model. It cannot: the
QAIRT plugin in 0.3.5 has VLM pipelines for `qwen2_vl`, `qwen2_5_vl` and `qwen3_vl`
only. **0.4.0 adds `intern3_5_vl` and `internvl`** -- confirmed by reading both
`libgeniex_plugin_qairt.so` binaries. The babytest app that ran this model was on
0.4.0.

0.4.0 changed four call sites: `LlmCreateInput` and `VlmCreateInput` lost
`model_name`, and `ModelConfig` lost `max_tokens` and `enable_thinking`. Both were
duplicates in Nila: the reply cap is set per call in `GenerationConfig.maxTokens`,
and reasoning is switched off in `applyChatTemplate(…, null, false)`, whose
signature did not change. Checked on the phone: Ask's answers carry no reasoning
trace.

### 10.2 Two models on the NPU crashed the app

With Qwen on the phone, opening the app built it on the NPU; starting the watch
then loaded the vision model on top, and the process died:

```
signal 11 (SIGSEGV), fault addr 0x8 in tid nila-npu-vlm
#00 libQnnHtpV81Stub.so … #12 libgeniex_core.so (QnnApi::createDevice()+384)
#15 libgeniex_vlm.so (geniex::intern3_5_vl::makeModel …)
```

`NpuArbiter` as first written decided where the chat model *builds* and when the
camera may *look*, but nothing ever took the chat model *off* the NPU. The design
said it would; the code did not; the unit tests only checked flags. Fixed with a
residency rule -- see `vision-npu-night.md` §6. Six new tests pin it, and the exact
crash sequence was re-run on the phone: Qwen on the NPU, watch started, Qwen
stepped off, vision loaded first time, looks ran, a question was answered on the
CPU. Same process, no crash.

### 10.3 The ring light was unreachable — a dropped line

`no vivo ring light on this phone: …VivoLightManager.getInstance []` -- on an iQOO,
which has one. The method exists (read out of `vivo-framework.jar` on the phone);
the `NoSuchMethodException` is Android's hidden-API restriction for an app
targeting SDK 35. The babytest app gets through with LSPosed's HiddenApiBypass,
exempting exactly `Lvivo/app/vivolight/` and `Lcom/vivo/framework/vivolight/`. The
port to Nila dropped that line. Restored, scoped the same way. On the phone:
`ring light started, id=10`.

### 10.4 Measured

| | |
|---|---|
| Vision model load, NPU clear | **5.1 s, 1 attempt** |
| Vision model load, Qwen resident (before the fix) | 15.9 s, 3 attempts -- then the crash |
| A look, model loaded | **~1.15-1.2 s** |
| Qwen on the NPU | built in 1.2 s |
| Qwen rebuilt on the CPU after eviction | 3.9 s |
| Night-time cadence, scene not settled | one look a minute, as designed |
| Unit tests | **392 pass** |

### 10.5 Two things seen and not yet acted on

- **"Night vision" in a 115 lx room.** Day needs both the light sensor and the
  frame to agree; the camera was facing something dark, so the frame said night.
  Working as designed, but worth re-checking with the camera on a real cot.
- **The vision model's format drifts.** One reply came back as `- Position: … |
  - Face: …` instead of `POSITION: …`. The parser coped -- it was read as the same
  answer -- but it is the kind of drift the eval set should include.

### 10.6 v2.5.0 should not be published

The `v2.5.0` tag predates all three fixes: on a phone its vision model never loads,
its ring light is dark, and once Ask's model is installed it can crash. These fixes
ship as **2.5.1**; when the signing secret is in place (§9), re-run the release for
`v2.5.1`, not `v2.5.0`.
