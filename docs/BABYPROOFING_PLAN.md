# Babyproofing a room — research and plan

**The feature:** a parent photographs a room. Nila marks every spot that needs babyproofing on the photo and lists each one with a fix (cover this socket, anchor this shelf, gate these stairs). It also keeps a checklist the parent can tick off and re-scan later. Like everything else in Nila, it runs entirely on the phone.

Status (2026-10-10): **built, measured and integrated, awaiting review.** It was built as a separate lab app first (`babyproof-lab/`), following the doctor-visit route. The recommended design (section 3) held up, with one change: OWL-ViT is split into its image and text halves so the phone runs five NPU passes a photo instead of 160. Shipped in Nila under Watch → *Babyproof a room*: 83% of labelled hazards found (75% of critical ones) on 47 photos, about 40 s a scan. Results and what is still weak: [`babyproof-lab/README.md`](../babyproof-lab/README.md#results).

The plan as written before the build follows.

---

## 1. Why this is a different job from the Danger watch

The Watch tab already finds danger with Intern3.5-VL-2B: 17 of 18 test photos right, about 1.2 s per look. That model answers *"is this baby in danger right now?"*. Babyproofing asks something else:

| | Danger watch (today) | Babyproofing (new) |
|---|---|---|
| Question | Is the baby touching something dangerous? | What in this room *could* hurt a baby, and where? |
| Baby in frame | Yes, and the model is anchored on the baby | Usually no baby; the whole room is the subject |
| Output | One verdict + one hazard | **Many** hazards, each **located** on the photo |
| Small objects | The baby's hand shows the model where to look | Sockets, cords, coins and batteries are tiny in a wide room shot |
| Time budget | About 1 s, again and again | One photo; 10–30 s with a progress bar is fine |
| Mistakes | A false alarm wakes a parent | A missed socket is the costly error; a few extra items are tolerable |

Two consequences follow:

- **Localisation matters.** The parent needs to see *which* socket.
- **Small objects get lost.** Intern3.5-VL-2B sees a 448×448 image, so a socket in a 4,000-pixel room photo shrinks to a few pixels. Asking a single question of the whole photo will miss exactly the hazards that matter most.

## 2. What the research says

- **General VLMs can spot child-safety hazards, but they miss small ones and invent some.** Two lines of work cover this:
  - Recent studies apply VLMs to child-safety hazards in home photos: *Utilizing VLMs for Identifying Child Safety Hazards* (ACM, 2026) and a cross-cultural comparison of VLM vs human safety judgements in child environments (Algorithms, 2025).
  - **HomeSafeBench** (2025) tests VLMs on home safety inspection, with child safety as one of five hazard categories, and finds that current models struggle, especially smaller ones.

  The lesson matches what we measured on the Watch: the model needs structure (a form) and checking (guard rails), not free text.
- **Pixel-precise localisation from a VLM is weaker than from a dedicated detector.** The Qwen3-VL technical report notes that its box outputs lag specialised detectors. Open-vocabulary detectors find "any object you name" with real boxes, in milliseconds.

### Candidate models, all checked against Qualcomm AI Hub for our chip (Snapdragon 8 Elite Gen 5)

**Vision-language models (reason about the hazard):**

| Model | On our phone | Speed on SD 8 Elite Gen 5 | Grounding (boxes) | License | Notes |
|---|---|---|---|---|---|
| **Intern3.5-VL-2B** | ✅ already installed (Watch) | ~54 tok/s, ~1.2 s per look | yes (InternVL box tokens), untested in our bundle | Apache-2.0 | Zero new download. 448×448 input → needs tiling |
| **Intern3.5-VL-4B** | AI Hub, GenieX QAIRT w4a16 | ~16 tok/s, TTFT 0.1–0.4 s (512 ctx) | yes | Apache-2.0 | Same family, same loader code; better reasoning, ~3× slower decode |
| **Qwen3-VL-2B** | AI Hub, **GenieX llama.cpp q4_0 (GGUF)** | ~44–50 tok/s | **strong**, boxes as text | Apache-2.0 | Runs through the same llama.cpp plugin as Ask's Qwen3.5; best grounding per size |
| **Qwen3-VL-4B** | AI Hub, GenieX QAIRT w4a16 / llama.cpp | ~15.5 tok/s (QAIRT), ~20 tok/s (llama.cpp, 512 ctx) | strong | Apache-2.0 | Best quality that still fits; a 2–3 GB download |
| Gemma 3n E2B | LiteRT-LM, CPU/GPU only | ~15 tok/s decode | weak | Gemma terms | No Qualcomm NPU path today → not recommended |
| FastVLM-0.5B | ✅ installed (fallback) | CPU, several seconds | no | Apple research | Too weak for this |

**Open-vocabulary detectors (find and box the objects):**

| Model | AI Hub on SD 8 Elite Gen 5 | Latency | Size | License | Verdict |
|---|---|---|---|---|---|
| **OWL-ViT** | ✅ ONNX / QNN / TFLite, float + w8a16 | **7.5–20 ms** at 768×768 | 613 MB float, much less at w8a16 | **Apache-2.0** | **Recommended**: free-text queries, permissive license |
| YOLO-World | ✅ (export it yourself; no pre-built files) | ~31 ms at 640×640 | 12–48 MB | **GPL-3.0** | Tiny and fast, but GPL; avoid unless the app is GPL |
| YOLOE / YOLOE-26 | Ultralytics export to QNN | ~6 ms (GPU class) | ~26M params | **AGPL-3.0** | Licence problem for an app; avoid |
| Grounding DINO | not on AI Hub | heavy | ~170M+ | Apache-2.0 | Too heavy for the phone |

## 3. Recommended design: detector proposes, VLM confirms, code advises

```
photo ─► 1 Find ───────────────► 2 Look closer ────────────► 3 Decide ───────► 4 Advise
        OWL-ViT, ~35 hazard      VLM on each crop              guard rails      curated fixes
        words, 768×768, NPU      + 2×2 tiles + whole photo    + dedupe +       (AAP / IAP /
        ~20 ms                   4-line form per look          severity         CPSC checklist)
```

1. **Find (detector).** OWL-ViT runs over the photo at 768×768 with a fixed vocabulary of about 35 hazard words:
   - electric: socket, power strip, charger, cable, extension cord
   - falls: stairs, window, balcony, railing, bed edge
   - tip-over: bookshelf, dresser, TV, tall lamp
   - sharp corners: glass table, table corner, knife, scissors
   - poison: medicine bottle, cleaning bottle, detergent
   - choking: coins, button battery, small toy, plastic bag, magnets
   - burns: heater, iron, kettle, stove, candle
   - water: bucket, tub, toilet
   - strangulation: curtain or blind cord
   - other: plant, door

   The text embeddings are computed once, offline, so the phone only runs the image side. A large photo is run as 2×2 overlapping tiles, so sockets stay several dozen pixels wide.
2. **Look closer (VLM).** Each candidate box is enlarged by 30% and cropped. Each crop goes to the VLM with a four-line form, the same idea that took the Watch from 11/18 to 17/18:
   ```
   OBJECT:    what is it
   HAZARD:    electric / fall / tip-over / sharp / poison / choking / burn / water / strangle / none
   REACHABLE: yes if a crawling or standing baby can reach it
   PROTECTED: yes if a cover, gate, lock or anchor is already fitted
   ```
   One extra whole-photo pass and four tile passes ask "list hazards a baby could reach". These catch *relational* hazards a detector cannot name, such as "stairs with no gate", "unanchored shelf" or "window that opens over a bed". At most about 12 looks per photo.
3. **Decide (plain code, unit-tested, like `HazardRules.kt`).**
   - **Drop** an item if:
     - the VLM says `none`;
     - it says `PROTECTED: yes` (an outlet that already has a cover isn't listed again);
     - a non-critical item lies above the reach line set for the baby's age (crawler, cruiser, climber), using a rough height estimate from the box's place in the photo.
   - **Merge** duplicates (overlapping boxes, IoU > 0.5).
   - **Rank** by severity: electric, falls, poison and water first; then burns, choking and tip-over; then sharp corners.
   - **Never raise an item the detector and the VLM both reject.** Never drop a critical class (socket, stairs, window, medicine) only because the VLM was unsure; show it as "check this".
4. **Advise (no generated advice).** Every hazard category maps to a fix written in advance from AAP / IAP / CPSC childproofing guidance, shown with its source, the same way Ask shows sources. The VLM's job is only to *see*. It never writes safety advice, so it cannot invent unsafe advice.

**Output to the parent:**
- the photo with numbered boxes, coloured by severity, which can be tapped;
- a checklist: *"1. Socket, low on the wall: fit a socket cover · AAP"*, with *Done* ticks;
- a score: "7 things to fix in this room, 3 urgent";
- a room history: re-scan after fixing to see the list shrink. It is saved under Health/Track or the Watch tab, in the phone's private storage and never uploaded.

**NPU sharing:** the scan claims the NPU exactly as the doctor visit does (`NpuArbiter.claimForVisit()`: Ask moves to the CPU and returns 45 s later). It refuses while the camera watch is running.

## 4. Which VLM to ship: decide by measurement, in this order

1. **Baseline: Intern3.5-VL-2B + OWL-ViT.** It needs no new large download: OWL-ViT w8a16 is the only addition. It reuses `NpuVlm`, the arbiter, the form and the guard rails. Expect about 10–20 s per photo (about 12 looks × ~1.2 s, plus tiles).
2. **Challenger A: Qwen3-VL-2B (GGUF, llama.cpp plugin).** Similar speed, better grounding, and it loads like Ask's Qwen3.5. It could also replace OWL-ViT's boxes if its boxes turn out good enough, which would mean one model instead of two.
3. **Challenger B: Qwen3-VL-4B or Intern3.5-VL-4B (QAIRT).** Pick these only if the 2B models miss too many critical hazards. They are 2–3× slower (about 30–45 s per photo) and add another 2–3 GB.

**Pass bar on the lab set:**
- **≥ 90% recall** on critical hazards: sockets, stairs, windows, medicines, cords;
- **≤ 1 false item per photo** on average;
- **under 30 s** per photo on the iQOO 15.

## 5. Evaluation set: needed, because no public set matches

There is no public dataset of real Indian homes photographed for babyproofing. HomeSafeBench is simulated, in VirtualHome. Build our own, like `ml/vision-eval`:

- **40–60 photos** taken on the iQOO 15 across living room, bedroom, kitchen, bathroom, balcony and stairs, with the main and ultra-wide lenses, in day and evening light;
- **labelled** with one box + category + "already protected?" per hazard, in `babyproof-eval/labels.csv`;
- **scored** by `score.py`, which reports recall per category, false items per photo, time per photo, and the confusion between "needs fixing" and "already fixed".

## 6. Complexity and effort

**Overall: medium.** The hard parts already exist in Nila: the NPU VLM loader with its retry loop, the arbiter, the form-plus-guard-rails pattern, sourced answers, a lab-app workflow and screenshot-ready UI. What's new is a detector, tiling, a checklist UI and the eval set.

| Step | Work | Estimate |
|---|---|---|
| 1 | Lab app shell (`babyproof-lab/`): pick or take a photo, show results | 0.5 day |
| 2 | OWL-ViT w8a16 from AI Hub, export with our vocabulary, run on NPU via ONNX Runtime + QNN (already in the app for Whisper) | 1–1.5 days |
| 3 | Tiling, box merge, crop pipeline | 0.5 day |
| 4 | VLM form on crops + whole-photo pass, with `NpuVlm` | 1 day |
| 5 | Guard rails + severity + age reach line (`BabyproofRules.kt`, unit tests) | 1 day |
| 6 | Fix catalogue from AAP / IAP / CPSC (~35 entries, sourced), reviewed by a person | 0.5–1 day |
| 7 | Eval set: 40–60 photos, labels, `score.py` | 1.5 days |
| 8 | Challengers A and B on the same set, pick the model | 1–2 days |
| 9 | UI: boxed photo, checklist, room history; then integrate into Nila (new card on Watch or Health) | 1.5 days |

**Total: about 8–11 working days** for one developer. A first demo-able version (steps 1–4 plus a basic list) takes **about 3 days**.

## 7. Risks and how they are handled

| Risk | Handling |
|---|---|
| Tiny hazards missed (socket, coin, button battery) | Detector + tiling; critical classes never silently dropped |
| Hallucinated hazards | Detector-and-VLM agreement, `HAZARD: none` drops, guard rails, eval false-item budget |
| Unsafe or wrong advice | Fixes come only from the curated, sourced catalogue; never generated |
| "Already babyproofed" not recognised (a socket that has a cover) | `PROTECTED` field + its own eval rows |
| A photo can't show the whole room | Ask for 2–4 photos per room (one per wall) or use the ultra-wide lens; results merge per room |
| NPU contention with the camera watch / Ask | Same arbiter as the doctor visit; refuse while the watch runs |
| Download size | Baseline adds only OWL-ViT w8a16; 4B VLMs are optional |
| Licences | Stay on Apache-2.0 models (OWL-ViT, Intern3.5-VL, Qwen3-VL); avoid YOLO-World (GPL-3.0) and YOLOE (AGPL-3.0) |
| Liability | Same stance as the rest of Nila: an awareness aid, "check the room yourself", no promise that a room is safe |

## 8. Pitch value

It fits **Monitor the baby**: the camera watches the baby *now*, and babyproofing prevents danger *before* the baby can crawl. It uses a third on-device model family (open-vocabulary detection) beside the VLM, and makes a strong one-tap demo: photograph the presentation room and watch the sockets light up.

## Sources

- Qualcomm AI Hub model cards (Snapdragon 8 Elite Gen 5 figures):
  - [Intern3.5-VL-2B](https://huggingface.co/qualcomm/Intern3.5-VL-2B)
  - [Intern3.5-VL-4B](https://huggingface.co/qualcomm/Intern3.5-VL-4B)
  - [Qwen3-VL-2B-Instruct](https://huggingface.co/qualcomm/Qwen3-VL-2B-Instruct)
  - [Qwen3-VL-4B-Instruct](https://huggingface.co/qualcomm/Qwen3-VL-4B-Instruct)
  - [OWL-ViT](https://huggingface.co/qualcomm/OWL-ViT)
  - [YOLO-World](https://huggingface.co/qualcomm/YOLO-WORLD)
- [Qwen3-VL technical report](https://arxiv.org/pdf/2511.21631) (grounding as text tokens; boxes lag specialised detectors)
- [HomeSafeBench](https://arxiv.org/pdf/2509.23690) (VLM home-safety inspection, includes child safety)
- [Utilizing VLMs for Identifying Child Safety Hazards](https://doi.org/10.1145/3772363.3798397) (ACM, 2026)
- [Cross-Cultural Safety Judgments in Child Environments](https://doi.org/10.3390/a18080507) (Algorithms, 2025)
- [YOLOE (Ultralytics, AGPL-3.0)](https://docs.ultralytics.com/models/yoloe)
- [Gemma 3n on LiteRT-LM](https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/)
