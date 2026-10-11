# Nila · Little Signs

An on-device helper for the little things parents wonder about between check-ups. It lets parents **notice** early visible signs in their baby, **stay calm** about the many harmless ones, and **show their doctor** a clear record. It is built as a separate feature module for Nila, and it does not diagnose.

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
| **Quick demo** | Every feature as photo in → result out (outlines, circles, colour chips, texture map, food checks), pre-computed so it opens instantly | — |
| **Demo lab** | 15 everyday moments, each run live in one tap: is this poo normal, tiny spots on the nose, a mark to watch, red cheeks after a new food, enough iron, and the quiet catches (a night nappy under a lamp, one white pupil in a flash photo) | — |
| **Gentle reminders** | Opt-in, silent: check-in two hours after a first food, photo the same spot tomorrow (WorkManager, on the phone) | — |
| **How Nila decides** | Every rule and source, plus a **self-test** that runs on the phone | — |
| **Common and harmless** | Education on milia, newborn rash, cradle cap, Mongolian spots and similar | NHS parent pages |

**Demo mode for judges:** one tap builds a demo baby with a week of history from real, openly licensed photos (Wikimedia Commons, credited in `skin-ui/src/main/assets/demo/CREDITS.md`). The one simulated series (tummy spots spreading) is labelled as such. Every number is measured on the phone.

## What the maths and the AI do

- **Skin:**
  - CIELAB against the baby's own skin in a ring, with a robust plane fit that removes uneven light;
  - per-pixel ΔE / Δa* thresholds from that skin's own spread;
  - spots by morphology and connected components;
  - a texture ratio (fine detail inside vs the ring) for scales and crusts;
  - a Theil–Sen trend over all photos.
- **Stool and eye whites:** the white reference (least-coloured bright pixels) corrects colour and brightness, and dots show where it came from. Then CIEDE2000 against the Ontario card, with a pale rule (light, weakly coloured, not reddish-brown).
- **Eyes:** MediaPipe landmarks give pupils, gaze and head turn. The reflex is read against the iris. An off-axis white glow is explained, never ignored.
- **Foods:**
  - Intern3.5-VL-2B suggests the foods;
  - three cross-checks test each one: self-consistency across two prompts, a yes/no question, and a colour match (per-pixel CIEDE2000);
  - colour proposes distinctive foods the AI missed;
  - the parent confirms.
  - Measured on 10 real baby-food photos on the phone: precision 48% → 65%, recall 56% kept.
- **Reactions:** exposure windows, a half rule and a one-sided Fisher exact test (pattern / possible / one-off). Allergen upkeep follows ASCIA and NIAID.

## Architecture

The project is split into modules ("micro-services" inside one app), so Nila can take the feature as-is:

```
skin-core   pure Kotlin/JVM: colour maths (CIELAB, CIEDE2000, white balance), measurements
            (SkinMeasure, SpotFinder, StoolCard, EyeReflex, Yellowness, Trend), guideline rules,
            food table + nutrition + reaction patterns, vision-model prompts/parsers, synthetic images.
            Tested on the JVM (73 tests, including real photos).
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
- `python tools/fetch_commons.py <dir> "File:Name.jpg" ...` fetches openly licensed photos with their licence and author. The demo photos and their credits are listed in `skin-ui/src/main/assets/demo/CREDITS.md`. `tools/make_demo_assets.py` makes the one stool test pattern.

## Tested on the phone (iQOO 15, Snapdragon 8 Elite Gen 5)

- **Self-test:** the same patch is found on six skin tones at 29.5–30.3% coverage against a true 29.8%, one spot each time, and redness within 0.4 a* of the truth. The stool card is right in 52 of 52 readings across warm, daylight, cool and dim-warm light. A brown mark under side light is found 6 of 6 (flat reference 0 of 6). Eye reflex is 3 of 3.
- **Intern3.5-VL on the NPU:** describes a real strawberry mark as "red, patch" in 0.9 s. On 10 real baby-food photos, precision is 48% for the AI list alone and 65% after the cross-checks, with recall 56% both ways (How Nila decides → Test the AI).
- **Real flash photos (Wikimedia Commons):**
  - The Coats-disease photo reads white/yellow in one eye and dark in the other.
  - The red-eye photo reads red in both eyes.
- **Gallery scan:** run with limited photo access on three demo photos, it flagged two.
- **Real photos:** a strawberry mark reads "much redder, one patch with 1 small spot nearby"; a Mongolian spot reads "a blue-grey tone"; cradle cap reads "rougher texture"; real normal stools read normal; yellow eye whites read "clearly yellow, b* 29" with the circle placed by face landmarks.
- **Reminders:** a scheduled reminder fired as a silent, low-importance notification.
- **Live camera:** capture with the ghost overlay works, and the quality check flags a dark photo.
- **Stool:** a pale stool under a warm bulb looks mustard to the eye but reads pale after white correction. This is the case the correction exists for.
- **PDF report:** generated, three pages.

See `docs/PITCH.md` for the problem, the evidence, the positioning and judges' questions. See `docs/INTEGRATION.md` for adding the feature to Nila.
