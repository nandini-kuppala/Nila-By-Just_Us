# Babyproofing — the lab

**Status: integrated into Nila (Watch → Babyproof a room), awaiting review.** This folder is the standalone Android app (`com.nila.babyproof`, "Nila Babyproof Lab") it was built and measured in first, the same way the doctor visit was. The pipeline, prompts, rules and catalogue in `android/app/src/main/java/com/nila/babyproof/` are copies of the ones here; the lab stays as the bench for changing them. Plan and model research: [`docs/BABYPROOFING_PLAN.md`](../docs/BABYPROOFING_PLAN.md).

## The idea

A parent photographs a room. Nila marks what needs babyproofing on the photo (sockets, extension boards, stairs, windows, gas cylinders, shelves that can tip, flames, buckets...) and lists each item with a fix and the published guidance it comes from. Things already babyproofed (a socket with a cover) go to an "Already done" list. Everything runs on the phone.

## How it works

```
photo ─► 1 Find ────────────────────► 2 Confirm ─────────────────► 3 Room ──────────► 4 Decide
         OWL-ViT (AI Hub, NPU)        Intern3.5-VL-2B (NPU)         same VLM, once      Rules.kt
         32 hazard words × 5 views    4-line form on a crop          "list what could    fix / check /
         (whole + 4 quarters)         around each strong hit         hurt the baby"     already done
```

1. **Find.** [OWL-ViT](https://huggingface.co/qualcomm/OWL-ViT) from Qualcomm AI Hub (v0.64.0, ONNX, float, Apache-2.0) runs on the Hexagon NPU through ONNX Runtime's QNN provider, in fp16, over the whole photo and four overlapping quarters (so small sockets stay visible).
   - **Split in two** (`tools/split_owl.py`). The export takes one text query per run, so 32 words × 5 views was 160 runs of the vision encoder per photo: 3.6 s, and the phone overheated within minutes. The image and text halves only meet at the very end (`logit = (patch · query + shift) × scale`), so the phone runs only the image half, once per view, and `tools/build_hazards.py` stores the 32 query embeddings from the text half in `hazards.json`. Same scores as the fused model to 1e-7; **0.5 s per photo**.
   - The box head ignores the text, so box *i* is the same for every word. Each box is therefore given only to the word it matches best, relative to that word's own threshold. Without this, a window would also be a "socket", a "lamp" and a "TV".
   - The compiled NPU graph is cached after the first load (19 s once, then 0.3 s).
2. **Confirm.** The same Intern3.5-VL-2B bundle Nila's Watch uses looks at a square crop around each strong hit, at most 12 per photo. Every critical hazard's best hit gets a look first. It fills four lines: `OBJECT`, `MATCH` (is it the thing the detector said?), `PROTECTED`, `REACHABLE`. The model is not asked whether something is dangerous (a 2B model calls most windows and stairs safe); that a socket is a hazard is the catalogue's knowledge.
3. **Room.** One look at the whole photo lists up to six things that could hurt a baby. This catches what the word list has no word for.
4. **Decide.** Plain, unit-tested code in [`Rules.kt`](app/src/main/java/com/nila/babyproof/pipeline/Rules.kt):
   - a confirmed hazard becomes a fix; the model's object name can move it to a sibling in the same family (a "socket" that is an extension board);
   - a "yes" that names a harmless thing (a bed, a sofa, a frying pan) and nothing in the catalogue is not believed;
   - a protected one goes to "Already done";
   - a critical hit the model rejects stays as "Check this", if the detector was sure;
   - a hit that is out of reach only drops for choking, poison and sharp items;
   - duplicates merge;
   - room-only items are added as "Check this".

**The models only see.** Every fix the parent reads is written in advance in [`tools/build_hazards.py`](tools/build_hazards.py) from AAP, CPSC and WHO guidance, with the source linked.

## Running it

```
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
# open the app once (it creates its folders), then:
python3 tools/split_owl.py   # once: work/models/split/owl_image.{onnx,data}
adb push work/models/split/owl_image.onnx work/models/split/owl_image.data \
    /sdcard/Android/data/com.nila.babyproof/files/models/owl_vit/
# for Nila itself (open Watch → Babyproof a room once first, so the folder is the app's own):
adb push work/models/split/owl_image.onnx work/models/split/owl_image.data \
    /sdcard/Android/data/com.nila/files/models/owl_vit/
# the VLM bundle, copied from Nila's own folder on the phone:
adb shell 'cd /sdcard/Android/data/com.nila/files/models/intern-vl && for f in *; do [ -f "$f" ] && cp "$f" /sdcard/Android/data/com.nila.babyproof/files/models/intern-vl/; done'
```

The models are not in git (`work/` is ignored). To get OWL-ViT, download `owl_vit-onnx-float.zip` from the [AI Hub release](https://huggingface.co/qualcomm/OWL-ViT) and unzip it into `work/models/float/`.

The app has a **Choose a photo** button, and seven **sample rooms** built in (`assets/babyproof/samples`). It can run with or without the vision model.

### Evaluation (headless, from the Mac)

```
python3 tools/run_eval.py detector     # detector only, ~0.5 s a photo
python3 tools/run_eval.py full         # detector, then the VLM over every photo (loaded once)
python3 tools/score.py eval/results/full --verbose
python3 tools/replay.py eval/results/full --sweep   # re-decide saved answers under other rules, in seconds
python3 tools/draw.py eval/results/full/e04.json eval/images/e04.jpg /tmp/e04.jpg cand
```

`run_eval.py` keeps the lab's activity in front (dimmed): vivo freezes an app whose screen is off, even one running a foreground service. The lab pauses for up to 10 minutes when the phone reaches thermal status "critical", and prints the battery temperature as it goes. Every full run saves each crop's raw answer, so `replay.py` (which reads its word lists straight from `Rules.kt`) can tune the rules without the phone.

## The evaluation set

**47 photos** in `eval/images`, all from Wikimedia Commons under free licences. Credits are in `eval/images/CREDITS.md`. They cover:
- Indian homes: kitchens with gas stoves on the counter and on the floor, LPG cylinders, a modular switchboard, an extension board, living rooms, bedrooms, a shrine lamp;
- stairs, windows, balconies, power strips and cable tangles, heaters, tall shelves, floor lamps;
- two sockets fitted with covers (protected);
- three rooms with nothing to fix.

Labels (`eval/labels.csv`) are per photo: which hazards are clearly visible and need fixing, and which are already protected. `score.py` compares by group, so a switchboard counts as a socket and a chest of drawers as a tall shelf.

**What it can't tell you yet:** stock and museum photos are tidier than real homes, and none show a room photographed by a parent on this phone. The next step is 10–20 photos of real rooms or the office, taken on the iQOO.

## Results

All on the iQOO 15, 47 photos, 63 labelled hazards (40 critical). "False items" are hazards listed that the labels do not name. Many are real objects the labels left out (a second socket, a lamp), so it overstates the noise.

| Version | Found | Critical found | False items / photo | Median time |
|---|---|---|---|---|
| Detector only, fused model, first word list | 69% | 73% | 2.45 | 3.6 s |
| Detector only, split model, tuned word list | 68% | 70% | 1.70 | 0.5 s |
| v1: free-form hazard question | 51% | 70% | 1.81 | 16.2 s |
| v2: MATCH question, same-family relabel | 83% | 75% | 1.57 | 9.1 s |
| **v2 + word fixes + "harmless yes" rule (shipped)** | **83%** | **75%** | **1.34** | 9.1 s |

In Nila the whole scan takes about 40 s, most of it loading the vision model (25 s). The detector loads in 0.3 s once its NPU graph is cached.

**What v1 got wrong, and v2 fixed.** Asked "what is the hazard?", the 2B model answered "none" for windows and stairs. Asked only "is this a window?", it says yes. Room words were matched as substrings, so "pillows" became *pills* and "plastic stool" a *plastic bag*.

**Still weak:**
- **Already protected.** 0 of 3 labelled cases: the model answers "PROTECTED: no" for plug covers and a window grill. A kitchen with grilled windows (a sample room) did come out as three windows "already done".
- **Extension boards.** 3 of 7 found. They are named "socket" or "cable" and land in the right family, but not always the right card.
- **Candles and diyas.** 0 of 2.
- **Room-wide list.** It adds 5 correct items (2 critical) for about 20 extra ones, which is why its items are shown as "Worth a check" with no ring on the photo.
