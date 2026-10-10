# Little Signs: the pitch

## One line

**Doctors diagnose. Parents notice. Nila helps parents notice the right things earlier, stay calm about the harmless ones, and walk into the clinic with evidence, entirely on the phone.**

## The problem

1. **Babies can't say what's wrong, so what parents can see is the early warning.** Several serious conditions first show up as something visible at home:

   | What a parent can see | What it can be | Evidence that early detection matters |
   |---|---|---|
   | Pale poo | Biliary atresia | Taiwan's national stool card (2004) raised 5-year survival from **55.7% to 89.3%**, and Kasai surgery before 60 days rose from 60% to 74% (Lien et al., Hepatology 2011). |
   | White pupil in a flash photo | Retinoblastoma, cataract, Coats' disease | Baylor's CRADLE study saw white-eye in family photos for **80%** of affected children, **about 1.3 years before diagnosis** (Munson et al., Sci Adv 2019). |
   | Yellow palms or soles | Severe neonatal jaundice | WHO/India IMNCI classes this as "refer urgently". |
   | A birthmark that is growing | Infantile hemangioma | AAP 2019: a changing mark in the first 2 months needs prompt evaluation, and photos are acceptable. |
   | Eczema | The first step of the atopic march to food allergy | LEAP / NIAID 2017: early peanut cut peanut allergy by **81%** in high-risk infants, and severe eczema means talk to the doctor first. |
   | A rash that doesn't fade under a glass | Meningococcal sepsis | NICE NG143 lists it as a red feature. |

2. **Most baby skin changes are harmless.** Seborrheic dermatitis, nappy rash and newborn rash each affect around half of infants. So parents fail in two directions:
   - **Fright:** diagnostic apps measurably raise anxiety (Jungmann et al., *Clinical Psychological Science*).
   - **Delay:** they miss the few signs that matter.
3. **Too few specialists.** India has about 1 dermatologist per 130,000–226,000 people, concentrated in cities, while about 70% of people are rural. Patient-taken photos are lower quality and agree less with in-person diagnosis (JMIR Dermatology 2022), so guiding the photo is value in itself.
4. **Doctors ask what parents can't answer from memory.** "When did it start? Is it spreading? After which food?"

## Why we don't diagnose, and why that's the strong position

- **Regulation:** FDA's January 2026 decision-support guidance excludes software that analyses images from the non-device carve-out. Image-based disease flagging is a medical device. Building that would be dishonest at a hackathon and dangerous in a parent's hand.
- **Accuracy and fairness:**
  - Skin AI trained mostly on light skin loses 27–36% of its accuracy on darker skin (DDI, Daneshjou et al. 2022).
  - A purpose-built jaundice app caught only 64% of high bilirubin levels in dark-skinned newborns (Picterus, Botswana 2026).
- **Our split instead:**
  - **The AI describes** (closed vocabulary: colour, texture, pattern, foods). It is never asked what condition a baby has, and parents confirm what it says.
  - **The maths measures** against the *baby's own skin*, so it reads the same on every skin tone. We test this on six tones on the phone itself.
  - **Published guidelines decide** the next step. Only a parent's checklist answer can trigger "get help now"; photos can at most say "show your doctor". Every piece of advice names its source.
  - **The doctor diagnoses**, with a one-page record that makes the visit better.

## Not scary by design

- Calm colours, with red reserved for real emergencies.
- "What we noticed", never "Detected: ...".
- Change over time ("smaller than Monday") rather than a score.
- Every screen ends in one next step: *keep an eye on it / check again tomorrow / doctor this week / doctor today / help now*.
- A "common and harmless" guide (milia, newborn rash, cradle cap, Mongolian spots, and more).
- One white-eye photo means "take 2–3 more", not "worry": CRADLE notes false positives from off-angle flash.

## What runs where (on the phone, offline)

| Chip | Work |
|---|---|
| NPU (Hexagon) | Intern3.5-VL-2B (Qualcomm AI Hub, GenieX): skin descriptions, food names |
| GPU (Adreno) | MediaPipe Face Landmarker: pupils for the eye check and the gallery scan |
| CPU | CIELAB maths, CIEDE2000, white balance, spot finder, trends, guideline rules, nutrition, PDF |

## Proof, on the phone (open "How Nila decides", then "Run the self-test")

- The same patch on six skin tones is found at 29.5–30.2% coverage against a true 29.8%, one spot each time, with redness within 0.4.
- The stool card is right in 39 of 39 readings across warm, daylight and cool light.
- Real Wikimedia Commons flash photos:
  - The Coats-disease child reads a white/yellow glow in one eye and dark in the other.
  - The red-eye photo reads red in both eyes.

## 3-minute demo script

1. **Home, then "Demo mode for judges"** (5 s). Aarav, 6 months, with a week of history. Today shows "Tummy spots: doctor this week", worked out from the photos.
2. **Tummy spots.** The trend reads "Bigger (about 199% larger), more spots over 2 days", with charts and first-vs-latest photos. The reason is measured, not guessed.
3. **Skin diary, then a new spot, then the "Red patch" demo photo.** The circle places itself. Measure shows Δa* against the baby's own skin, and the AI describes it on the NPU. Answer the NICE/NHS checklist and get a calm result with cited rules. Point at the safety-net card.
4. **Nappy colour, then "Pale sand · warm bulb".** It looks mustard to the eye, but white correction reads it pale: talk to a doctor today, rule cited. Say the Taiwan numbers.
5. **Eye flash, then the Coats-disease photo.** Face landmarks find the pupils: white/yellow glow on one side, dark on the other, so "take 2–3 more photos". Mention the on-device gallery scan.
6. **Food & reactions.** A peanut check-in is due. Egg is flagged 2 of 3 times, while khichdi is not blamed despite being on the table every day. The allergen tracker comes with NIAID guidance.
7. **Meal plate, then "Idli with sambar".** The AI names idli and sambar, the parent removes the extras, and the screen shows ICMR-NIN bars and WHO food groups.
8. **Doctor report.** A 3-page PDF made offline.
9. **How Nila decides, then the self-test.** The fairness table runs live.

## Judges' questions

- **"Isn't this diagnosing?"** No. We never name a condition. Decisions come from WHO/NICE/NHS/AAP checklists that health workers already use, and the AI only describes. That is deliberate: FDA treats image-based disease flagging as a device.
- **"What if the AI is wrong?"**
  - It can't escalate to an emergency.
  - A parent confirms what it says.
  - The maths and checklist work without it. The app runs fully with the model removed.
- **"Does it work on Indian skin?"** We measure against the baby's own skin and verify on six tones on the phone. We refused to estimate bilirubin precisely because camera apps fail on darker skin.
- **"Where's the data from?"**
  - Food values come from USDA FoodData Central (each row states its source), plus NIN for ragi.
  - Needs come from ICMR-NIN 2020.
  - Swatches are sampled from the Ontario card.
  - Demo photos are synthetic, or openly licensed from Commons with credits.
- **"Privacy?"**
  - No network.
  - Photos stay in private app storage.
  - The AI's temporary copy is deleted after each look.
  - The PDF leaves only when the parent shares it.
- **"Why not a cloud model?"** A baby's body photos should never need to leave the phone, and the NPU makes that possible.

## Honest limits

- The synthetic photos test the maths, not clinical accuracy. Clinical validation with paediatricians would be the next step.
- Gallery scanning depends on face detection, which is weaker on very small or partial faces. The parent can tap the pupils instead.
- The 2B vision model sometimes adds foods that aren't there, so the parent always confirms.

## Sources

Lien TH et al., Hepatology 2011 (Taiwan stool card) · Newborn Screening Ontario stool colour card 2024 · PoopMD, PLOS ONE 2015 · Munson MC et al., Sci Adv 2019 (CRADLE) · WHO/India IMNCI chart booklet · NICE NG143; NICE CG57 · NHS rash, glass test, jaundice, weaning pages · AAP infantile hemangioma CPG 2019 · NIAID 2017 peanut addendum / LEAP · Daneshjou R et al., Sci Adv 2022 (DDI) · Picterus Botswana, Acta Paediatrica 2026 · Jungmann SM et al., Clin Psych Sci · FDA CDS and general wellness guidance, Jan 2026 · ICMR-NIN Nutrient Requirements for Indians 2020 · WHO/UNICEF IYCF indicators 2021 · PAHO/WHO complementary feeding principles 2003 · USDA FoodData Central SR Legacy 2018 · Cugmas & Štruc 2020 (smartphone colour calibration).
