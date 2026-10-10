# Nila · Little Signs

An on-device helper that lets parents **notice** early visible signs in their baby, **stay calm** about the many harmless ones, and **show their doctor** a clear record. It is built as a separate feature module for Nila, and it does not diagnose.

> The AI describes. The maths measures. Published guidelines decide the next step. The doctor diagnoses.

Everything runs on the phone:

- Intern3.5-VL-2B on the Hexagon NPU.
- MediaPipe face landmarks on the GPU.
- Colour maths and rules on the CPU.

No account, no network, and no photo ever leaves the phone unless the parent shares a report.

## What it does

| Check | What the phone does | What decides the next step |
|---|---|---|
| **Skin diary** | Guided photo with a ghost of the last one, auto-placed circle, redness/size/spot count against the baby's *own* skin, AI description, trend over days | NICE NG143 red/amber signs, NHS glass test, NICE CG57 infected eczema, WHO IMNCI pustules, AAP hemangioma rules |
| **Food & reactions** | Food log, check-ins after first foods, exposure-window pattern finder, allergen tracker | NHS / Resus Council anaphylaxis signs, NHS weaning, NIAID 2017 (severe eczema and peanut) |
| **Meal plate** | AI names the foods, parent confirms, nutrients from USDA FoodData Central | ICMR-NIN 2020 RDA, WHO energy-from-food, WHO/UNICEF minimum dietary diversity, NHS foods to avoid (honey under 1, whole nuts, whole grapes) |
| **Nappy colour** | Stool colour read against the white nappy and matched to the Ontario stool card swatches (CIEDE2000) | Stool colour card (Taiwan programme; Newborn Screening Ontario) |
| **Eye flash** | Face landmarks find the pupils; reflex read by contrast with the iris; optional on-device scan of recent flash photos | AAP red reflex; CRADLE study (one white photo means retake, a repeated one means see the doctor) |
| **Yellow check** | Colour-corrected eye-white record, with no bilirubin estimate | WHO IMNCI young-infant jaundice rows, NHS 14/21-day rule |
| **Glass test** | Guidance only; the camera deliberately does not judge it | NHS |
| **Doctor report** | One PDF: photos side by side, measurements, trend, answers, foods in the 48 h before each reaction | — |
| **How Nila decides** | Every rule and source, plus a **self-test** that runs on the phone | — |
| **Common and harmless** | Education on milia, newborn rash, cradle cap, Mongolian spots and similar | NHS parent pages |

**Demo mode for judges:** one tap builds a demo baby with a week of history. All skin numbers are measured on the phone from synthetic photos.

## Architecture

The project is split into modules ("micro-services" inside one app), so Nila can take the feature as-is:

```
skin-core   pure Kotlin/JVM: colour maths (CIELAB, CIEDE2000, white balance), measurements
            (SkinMeasure, SpotFinder, StoolCard, EyeReflex, Yellowness, Trend), guideline rules,
            food table + nutrition + reaction patterns, vision-model prompts/parsers, synthetic images.
            Tested on the JVM (43 tests).
skin-data   Room database nila_skin.db (separate from Nila's), private photo store.
skin-ai     VisionModel interface; NpuVisionModel (Intern3.5-VL-2B via GenieX/QAIRT on the NPU,
            Nila's retry-ladder loader); FaceEyes (MediaPipe Face Landmarker, GPU then CPU).
skin-ui     Compose screens using Nila's design system (theme and Kit copied verbatim), navigation,
            PDF report, demo data, self-test.
app         thin standalone host (com.nila.skin), installs beside Nila.
```

| Chip | Work |
|---|---|
| NPU | Intern3.5-VL-2B describes skin and names foods (about 1–2 s per look once loaded) |
| GPU | Face landmarks for the pupils |
| CPU | All measurements, rules, nutrition and the PDF |

## Build and run

```bash
export ANDROID_HOME="C:\Users\visha\AppData\Local\Android\Sdk"
./gradlew :skin-core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

**The vision model (optional):** the app works without it on maths and checklists alone. To add it, push the AI Hub bundle into the app's own external folder, so the app owns the files:

```bash
adb push <intern3_5_vl_2b-geniex_qairt-w4a16-...gen5> /sdcard/Download/intern-vl-tmp
adb shell cp -r /sdcard/Download/intern-vl-tmp /sdcard/Android/data/com.nila.skin/files/models/intern-vl
adb shell rm -rf /sdcard/Download/intern-vl-tmp
```

Launch the app once first so it creates `files/models`. Use `cp`, not `mv`: a folder that adb moves stays owned by the shell user and is invisible to the app.

**Regenerating data:**

- `python tools/build_foods.py <USDA SR Legacy CSV dir>` rebuilds `foods.tsv` (83 foods, each row states its USDA fdc_id or recipe).
- `python tools/make_demo_assets.py <commons download dir>` rebuilds the demo photos. Credits are in `skin-ui/src/main/assets/demo/CREDITS.md`.

## Tested on the phone (iQOO 15, Snapdragon 8 Elite Gen 5)

- **Self-test:** the same patch is found on six skin tones at 29.5–30.2% coverage against a true 29.8%, one spot each time, and redness within 0.4 a* of the truth. The stool card is right in 39 of 39 readings across warm, daylight and cool light. Eye reflex is 3 of 3.
- **Intern3.5-VL on the NPU:** described a skin patch ("pink, patch"), and named idli and sambar on a real plate photo (8.7 s including load).
- **Real flash photos (Wikimedia Commons):**
  - The Coats-disease photo reads white/yellow in one eye and dark in the other.
  - The red-eye photo reads red in both eyes.
- **Gallery scan:** run with limited photo access on three demo photos, it flagged two.
- **Stool:** a pale stool under a warm bulb looks mustard to the eye but reads pale after white correction. This is the case the correction exists for.
- **PDF report:** generated, three pages.

See `docs/PITCH.md` for the problem, the evidence, the positioning and judges' questions. See `docs/INTEGRATION.md` for adding the feature to Nila.
