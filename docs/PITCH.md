# Little Signs: the pitch

## One line

**Doctors diagnose. Parents notice. Nila helps parents notice the right things earlier, stay calm about the harmless ones, and walk into the clinic with evidence, entirely on the phone.**

## The problem

1. **Babies can't say what's wrong, so what parents can see is the early warning.** Several serious conditions first show up as something visible at home:

   | What a parent can see | Why early matters |
   |---|---|
   | Pale poo | Taiwan's national stool card (2004) raised 5-year survival in biliary atresia from **55.7% to 89.3%**; surgery before 60 days rose from 60% to 74% (Lien et al., Hepatology 2011). |
   | White pupil in a flash photo | Baylor's CRADLE study saw white-eye in family photos for **80%** of affected children, **about 1.3 years before diagnosis** (Munson et al., Sci Adv 2019). |
   | Yellow palms or soles in a newborn | WHO/India IMNCI: refer the same day. |
   | A birthmark that grows in the first months | AAP 2019: see early; photos are acceptable. |
   | Eczema and first foods | LEAP / NIAID 2017: early peanut cut peanut allergy by **81%** in high-risk infants; severe eczema means talk to the doctor first. |
   | A rash that doesn't fade under a glass | NHS / NICE NG143: see a doctor straight away. |

2. **Most baby skin changes are harmless.** Seborrhoeic dermatitis, nappy rash and newborn rash each affect around half of infants. Parents fail both ways: **fright** (symptom apps measurably raise anxiety) and **delay** (missing the few signs that matter).
3. **Too few specialists.** India has about 1 dermatologist per 130,000–226,000 people, mostly in cities. Patient-taken photos are lower quality and agree less with in-person diagnosis (JMIR Dermatology 2022), so guiding and correcting the photo is value in itself.
4. **Doctors ask what parents can't answer from memory:** "When did it start? Is it spreading? After which food?"

## Real moments it is built for

- **11 pm, a dim bedroom, a 3-week-old's nappy looks "yellow-ish".** Under a warm lamp, pale poo looks darker and more coloured. Nila corrects colour *and brightness* from the white nappy: in our dim-light test the uncorrected reading got 8 of 13 card colours right, corrected 13 of 13. Pale reads pale; the parent calls the doctor today.
- **Grandma's flash photo shows one white pupil.** Face landmarks find both pupils, the reflex is read, and the gaze is checked: the commonest false alarm is an off-angle eye lighting the optic nerve. One photo means "take 2–3 more"; the same eye white twice means "book a red reflex check".
- **"Is it spreading?"** One photo a day with a faint overlay of the last one; size and redness against the baby's own skin; a robust trend through every photo.
- **Hives after egg, but khichdi is on the table every day.** Exposure windows plus Fisher's exact test separate the real pattern (egg, "about 1 in 90 by chance") from coincidence (khichdi, 2 of 11).
- **Peanut started at 6 months, then forgotten.** The allergen tracker nudges "keep it in the diet about twice a week" (ASCIA) and counts peanut protein against NIAID's 6–7 g a week.
- **"What did my baby actually eat?"** Photo the bowl; the on-device AI suggests foods, three cross-checks test each one, the parent confirms; ICMR-NIN 2020 needs and WHO food groups follow.
- **A 3-day-old looks yellow.** The WHO checklist, answered by the parent, decides: yellow soles mean "see a doctor now"; the camera keeps a colour-corrected record, never a bilirubin guess.

## Why we don't diagnose, and why that's the strong position

- **Regulation:** FDA's January 2026 decision-support guidance excludes image-analysing software from the non-device carve-out. Image-based disease flagging is a medical device.
- **Fairness:** skin AI trained mostly on light skin loses 27–36% of its accuracy on darker skin (DDI, Daneshjou et al. 2022). A purpose-built jaundice app caught 64% of high bilirubin levels in dark-skinned newborns (Picterus, Botswana 2026).
- **Our split:**
  - **The AI describes**: colour, texture, pattern, foods. It is never asked what condition a baby has.
  - **The maths measures** against the baby's own skin, so it reads the same on every tone.
  - **Published guidelines decide.** Only a parent's checklist answer can say "see a doctor now"; photos can at most say "show your doctor this week". Every reason names its rule.
  - **The doctor diagnoses**, with a PDF record that makes the visit better.

## How it is better than a plain phone photo

A phone photo is a guess at colour: auto white balance, auto exposure, and the light in the room all change it. Nila turns it into a measurement:

| Plain photo | What Nila does |
|---|---|
| Colour shifts with the bulb | White reference (nappy, tissue, or the shine on skin) corrects colour **and brightness**; the dots show which pixels were used |
| "Is this red?" depends on the skin tone | Every pixel compared with the same baby's skin in a ring around the spot |
| Window light makes one side brighter | A plane fitted to the surrounding skin removes the slope (brown mark under side light: found 6/6 vs 0/6) |
| Size depends on distance | Ghost overlay to frame the same way; coin for mm²; robust trend so one badly framed photo can't swing it |
| Scales and crusts don't change colour | Texture ratio (fine detail inside vs the baby's own skin): cradle cap reads "rougher texture, 1.9×" |
| A blurry or dark photo still gives a "result" | Photo quality check first: "It's too dark. Move near a window" |

## The maths and the models (what's novel)

- **Own-skin colorimetry with a lighting model.** CIELAB, robust plane fit (least squares, outliers trimmed twice), per-pixel ΔE and Δa* thresholds from the baby's own skin spread; 3×3 morphological opening; 8-connected components.
- **Exposure-aware white correction.** Least-coloured bright pixels as the reference; gains bring it to L* 92 within limits.
- **Robust trend.** Theil–Sen slope of log size over time; "spreading fast" = 1.5× within 4 days.
- **Food–reaction statistics.** 2 h / 48 h exposure windows, smoothed rates, a "half" rule, and a one-sided Fisher exact test. Three levels: pattern (p ≤ 0.05), possible (same shape, too few logs), one-off.
- **Gaze-aware leukocoria check.** MediaPipe iris and eye-corner landmarks give gaze and head turn; an off-axis white glow is explained, never ignored.
- **AI that is cross-checked, not trusted.** Intern3.5-VL-2B on the Hexagon NPU. For foods, three independent checks: two differently worded looks (self-consistency), a yes/no question per food, and colour maths (k-means / per-pixel CIEDE2000 against the food's colour). Colour can veto a food and can propose distinctive ones the AI missed (ragi, beetroot, green or orange purée). For skin, the AI's colour, pattern and texture are compared with the measurement: agree / partly / disagree.
- **Asymmetric escalation.** Photos and AI can raise to "this week"; only guideline answers reach "see a doctor now".

## Proof, on the phone

- **Self-test (How Nila decides → Run the self-test):**
  - same patch on six skin tones: 29.5–30.3% found against a true 29.8%, redness within 0.4;
  - stool card 52/52 under warm, daylight, cool and dim-warm light;
  - brown mark under side light 6/6 (flat reference 0/6);
  - eye reflex 3/3.
- **On-device AI test (Test the AI on 10 baby-food photos), measured on the iQOO's NPU:**

  | | Precision | Recall |
  |---|---|---|
  | AI list alone | 48% | 56% |
  | After cross-checks | 65% | 56% |

  With colour suggestions, the parent sees 67% of the main foods. A 2B model on a phone misses real foods (ragi as "chickpea, dal", pumpkin as "egg"), which is why the parent always confirms.
- **Real photos (Wikimedia Commons):**
  - a strawberry mark reads "much redder, darker, one patch with 1 small spot nearby";
  - a Mongolian spot reads "a blue-grey tone, darker";
  - cradle cap reads "rougher texture";
  - real normal stools read normal;
  - the Coats-disease flash photo reads white in one eye with "head turned";
  - the red-eye photo reads red in both, "looking straight";
  - yellow eye whites read "clearly yellow, b* 29".
- **Demo lab:** 11 real moments, each runs live in one tap.

## Judges' questions

- **"Isn't this diagnosing?"** No condition is ever named in a result. Next steps come from WHO, NICE, NHS and AAP checklists that health workers already use. The AI only describes, and it is cross-checked.
- **"How accurate is it?"**
  - The maths is exact on known-answer tests (six tones, 52/52 stool, 6/6 side light).
  - The AI is measured honestly: 65% precision on real baby-food photos after cross-checks, against 48% alone.
  - The decision steps are guideline rules: they are right by construction, and every one is cited.
  - Clinical accuracy needs a study with paediatricians; that is the next step, and we say so.
- **"What if the AI is wrong?"** It can't escalate to "see a doctor now". It is cross-checked, and the parent confirms. The app runs fully with the model removed.
- **"Does it work on Indian skin?"** It measures against the baby's own skin, and the self-test runs on six tones on the phone. We refused to estimate bilirubin precisely because camera apps fail on darker skin.
- **"Privacy?"**
  - No network, ever.
  - Photos stay in private app storage.
  - The AI's temporary copy is deleted after each look.
  - Reminders are scheduled on the phone, silent, and only when asked for.
  - The PDF leaves only when the parent shares it.
- **"Why not a cloud model?"** A baby's body photos should never need to leave the phone, and the NPU makes that possible.

## Honest limits

- **Synthetic tests:** they check the maths, not clinical accuracy. Clinical validation with paediatricians is the next step.
- **Spot finding:** automatic placement abstains on cluttered real photos rather than guess; the parent taps the spot.
- **Pale stool photo:** no openly licensed photo of pale infant stool exists, so that demo is a labelled test pattern.
- **Food AI:** the 2B model misses many foods; the cross-checks cut wrong ones, but the parent confirms.
- **Thin stains:** a stain on an absorbent nappy can read paler than the stool; the app asks to compare the stool itself in daylight.

## Sources

- **Stool card and white eye:** Lien TH et al., Hepatology 2011 · Newborn Screening Ontario stool colour card 2024 · Munson MC et al., Sci Adv 2019 (CRADLE).
- **Guidelines:** WHO/India IMNCI · NICE NG143, CG57 · NHS rash, glass test, jaundice and weaning pages · AAP infantile hemangioma CPG 2019 · AAP red reflex · NIAID 2017 / LEAP · ASCIA infant feeding.
- **Fairness and jaundice apps:** Daneshjou R et al., Sci Adv 2022 (DDI) · Picterus Botswana, Acta Paediatrica 2026.
- **Regulation:** FDA CDS and general wellness guidance, Jan 2026.
- **Nutrition:** ICMR-NIN 2020 · WHO/UNICEF IYCF indicators 2021 · PAHO/WHO complementary feeding 2003 · USDA FoodData Central SR Legacy.
- **Methods:**
  - colour difference: Sharma, Wu & Dalal 2005 (CIEDE2000);
  - trend: Theil 1950 and Sen 1968;
  - food patterns: Fisher's exact test;
  - smartphone colour calibration: Cugmas & Štruc 2020.
