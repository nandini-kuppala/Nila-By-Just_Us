# Nila · Little Signs

**A baby diary that compares now with before, so small, easily missed changes become visible.**

Little Signs is for the little things parents wonder about between check-ups:

- Is this poo colour the same as usual?
- Is this mark the same size as last week?
- Are the eyes as white as yesterday?
- Did anything change after the new food?

It does not detect, classify or diagnose anything. It keeps a careful record and compares it:

- **today with the past days**, for the same baby;
- **a spot with the baby's own skin** right next to it;
- **a colour with a printed reference**: the nappy's white, the stool colour card.

A parent with a clearly unwell baby goes to the doctor; this app is for the quiet in-between.

> Nila describes and measures. Published guidelines suggest when to show a doctor. The doctor diagnoses.

Everything runs on the phone. There is no account and no network, and photos stay in the app's private storage.

This is a separate app on its own branch (`little-changes`), not part of Nila's main app.

---

## Try it on a phone (no build needed)

1. **Download the APK.** On the Android phone, open this repository's **Releases** page and download `LittleSigns-*.apk`. It's for 64-bit Android 8.1 or newer, which covers almost every phone from the last six years.
2. **Install it.** Open the file. If Android asks, allow your browser or file manager to "install unknown apps", then tap **Install**.
3. **Open "Nila Little Signs".** It installs next to any other app and doesn't touch them.

### Show the demo

| Step | What to tap | What appears |
|---|---|---|
| 1 | **Quick demo** on the first screen | Every feature as *photo in → result out*: outlines, circles, colour chips, texture map, food checks. Ready in a few seconds. |
| 2 | **Demo mode for judges** | A demo baby with a week of history made from real photos: a diary of marks, food logs, nappy checks |
| 3 | Any check, e.g. **Skin diary → Track a new spot**, **Nappy colour check**, **Eye flash check**, **Yellow check**, **Meal plate** | Each has a row of **Demo photos: tap one**. Tap a photo and the check runs on it, exactly as on a photo taken with the camera |
| 4 | **Eye flash check → Add the demo flash photos to my gallery**, then **Scan my gallery** | Shows the gallery scan. It only adds three demo photos to `Pictures/NilaDemo` |
| 5 | **How Nila decides → Run the self-test** | The maths checked live on pictures with a known answer |
| 6 | **Doctor report → Make the PDF** | The record a parent would show a doctor |

To start again, use **Reset demo** at the bottom of the first screen.

### About the on-device AI

The optional AI (Intern3.5-VL-2B) needs a phone with a **Snapdragon 8 Elite Gen 5** and a 2.6 GB model file that is not in this repository.

- **Without it, everything else works.** All maths, comparisons, checklists, the diary and the report run on any phone.
- **The Quick demo still shows food.** It shows the food results recorded on an iQOO 15's NPU, labelled as a recording.
- **To add the model** on a supported phone, open the app once, then from a computer:

  ```bash
  adb push <intern3_5_vl_2b-geniex_qairt-w4a16-...> /sdcard/Download/intern-vl-tmp
  adb shell cp -r /sdcard/Download/intern-vl-tmp /sdcard/Android/data/com.nila.skin/files/models/intern-vl
  adb shell rm -rf /sdcard/Download/intern-vl-tmp
  ```

  Use `cp`, not `mv`: a folder moved by adb is invisible to the app. The bundle comes from Qualcomm AI Hub (Intern3.5-VL-2B, GenieX, w4a16).

---

## What it compares

| You photograph or log | It compares | You see |
|---|---|---|
| A mark or patch on the skin | the spot against the baby's own skin in a ring around it; this photo against earlier ones | an outline of what changed, its size, how much redder, darker or rougher, and **same / smaller / bigger** over days |
| The nappy | the poo colour against the nappy's white, then against the printed stool colour card | three colour chips (as photographed → corrected → nearest card colour) |
| A flash photo | each pupil's glow against its iris; left eye against right eye | red / dark / white glow per eye, and whether the baby was looking straight at the camera |
| The white of the eye (newborns) | its colour against a white reference, day by day | a colour-corrected record; never a bilirubin number |
| Foods and any reactions | when reactions happen against which foods came before, compared with every other food | whether a food keeps coming up, and how likely chance alone would be |
| A meal photo | the AI's food suggestions against two more checks | foods pre-ticked only when the checks agree; the parent confirms |
| A day's or week's food | nutrients against ICMR-NIN 2020 needs; groups against WHO's eight | simple bars and food groups |

The next step shown, from *keep an eye on it* to *see a doctor*, comes from published checklists (NICE, NHS, WHO/India IMNCI, AAP, NIAID), and each one is cited on screen. Photos and AI never decide on their own; a parent's answer to a checklist question does.

## How the comparison works (short)

- **Colour as numbers.** CIELAB colour (a* redness, b* yellowness, L* lightness) and CIEDE2000 colour difference.
- **The baby's own skin as the reference,** fitted as a gentle plane so window light on one side doesn't look like a change. The same comparison works on every skin tone.
- **White correction.** The least-coloured bright pixels (the nappy, a tissue) correct the light's colour and brightness. Dots show which pixels were used.
- **Texture.** Fine detail inside the circle against the surrounding skin; scales and crusts show up even when the colour doesn't change.
- **Change over time.** A robust line through every photo (Theil–Sen), so one badly framed photo can't swing it.
- **Food diary.** Exposure windows and Fisher's exact test.
- **On-device AI, cross-checked.** It describes and suggests only: it answers twice with different wording, gets a yes/no per food, and is checked against colour maths.

## Build from source

You need Android Studio (or JDK 17 with the Android SDK):

```bash
./gradlew :skin-core:test :app:assembleDebug      # on Windows: gradlew.bat ...
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

The project is split into modules:

```
skin-core   pure Kotlin: colour maths, comparisons, trends, checklists, food tables (73 JVM tests, incl. real photos)
skin-data   on-phone database and private photo store
skin-ai     on-device AI (Intern3.5-VL on the NPU) and face landmarks (MediaPipe, GPU)
skin-ui     screens, Quick demo, Demo lab, report
app         the standalone app (com.nila.skin)
```

## Tested

Tested on an iQOO 15 (Snapdragon 8 Elite Gen 5), Android 16:

- **Same patch on six skin tones:** found at 29.5–30.3% against a true 29.8%.
- **Stool card:** 52/52 colours right under four lights.
- **Brown mark in side light:** found 6/6 (0/6 without the lighting plane).
- **Food AI, 10 real photos:** precision 48% alone, 65% after the cross-checks.

The tests check the maths, not clinical accuracy.

## Credits and sources

- **Demo photos:** Wikimedia Commons, openly licensed. Authors and licences are in `skin-ui/src/main/assets/demo/CREDITS.md`. One stool image is a labelled test pattern, because no open photo exists.
- **Guidelines:** NICE NG143 and CG57, NHS, WHO/India IMNCI, AAP 2019, NIAID 2017, ASCIA.
- **Food and nutrition:** ICMR-NIN 2020, WHO/UNICEF IYCF 2021, USDA FoodData Central.
- **Stool card:** Newborn Screening Ontario stool card.
- **Evidence:** Lien et al. 2011 (stool card); Munson et al. 2019 (flash photos).

More detail: `docs/PITCH.md` (evidence and demo script) and `docs/INTEGRATION.md` (how it could later plug into Nila).
