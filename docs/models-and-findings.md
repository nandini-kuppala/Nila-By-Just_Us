# Nila: what runs where, and what actually works

*A plain-language record of every model in the app, which chip each one runs on,
and what we found when we measured them on a real iQOO 15.*

Last updated 12 September 2026. Written to be readable by someone who has not
seen the code.

---

## 1. The one-paragraph version

Nila runs **nine models entirely on the phone**, with no network calls. Two of
them are excellent, four are solid, one is honestly broken and we say so, and
two are large language models that are currently more trouble than they are
worth. The cry detector — the model the whole product rests on — runs on the
iQOO's **NPU**, the chip built for exactly this. The chat model does not, and
this document explains why that is a limitation of the software libraries
available rather than of the phone.

The newest of the nine, a drug-interaction classifier over 1,710 drugs, is also
the clearest example of the rule the rest of this document keeps arriving at:
**measure what a model can actually be trusted with, then let it answer only
that.** It is allowed to say what kind of interaction two drugs have, where it
is right 96.6% of the time. It is not allowed to say whether they interact at
all, because on that question it is barely better than a coin toss, and §3.5b
shows the measurement that settled it.

---

## 2. Three chips, and why it matters

A modern phone has three processors that can run AI, and they are not
interchangeable:

| Chip | What it is | Good at |
|---|---|---|
| **CPU** | The general-purpose processor | Anything, slowly |
| **GPU** | The graphics processor | Lots of simple maths at once |
| **NPU** | A block built only for neural networks | AI maths, fast and on very little power |

The iQOO 15's NPU is the Hexagon block inside its Snapdragon chip. Getting a
model onto it is the difference between a monitor that lasts a night on a
battery and one that does not — and it is the whole reason a phone can do this
job at all.

**You do not choose the chip directly.** You ask a software library to run your
model, and the library decides what it can offer. Different libraries offer
different chips, and that single fact explains most of what follows.

---

## 3. Every model in the app

### 3.1 The cry detector — the one that matters

| | |
|---|---|
| **What it does** | Decides ten times a second whether the sound in the room is a baby crying |
| **Size** | 36 KB. Smaller than a photograph |
| **Runs on** | **NPU** |
| **Speed** | 0.2 ms per window |
| **Accuracy** | 0.992 AUC on infants it has never heard, 97.3% recall on crying |

This is a small convolutional network trained on the Donate-a-Cry corpus plus
household noise. It was tested on a **subject-wise split** — no infant appears
in both training and test — which is the only kind of test that predicts how it
behaves on *your* baby. It does not fire on a vacuum cleaner, a washing machine,
a door knock, a cough, a cat, a siren or a car horn.

**A bug we found and fixed.** For a long time this model was silently running on
the **CPU**, not the NPU, and nothing in the app said so. The cause was in how
the model file was exported: the batch dimension was left unspecified, so every
tensor in the file was marked "size unknown". The CPU tolerates that. NNAPI —
the Android interface that reaches the NPU — does not, so it refused the model
and the app quietly fell back:

```
Attempting to use a delegate that only supports static-sized tensors with a
graph that has dynamic-sized tensors (tensor#34 is a dynamic-sized tensor)
NNAPI unavailable; falling back
GPU unavailable (GPU delegate unsupported on this device); falling back
Created TensorFlow Lite XNNPACK delegate for CPU
```

The fix was to pin that dimension when exporting. Same weights, same
quantisation, same numerical agreement with the original (1.892e-03), and the
phone now reports:

```
TfliteRunner: NNAPI  in=[1, 96, 64] out=[1, 2]
MonitorService: monitoring started on NNAPI
```

The export script now **asserts** that no dimension is left unspecified, because
the symptom of getting this wrong is not an error message — it is a model that
works perfectly on the CPU of the only device anybody tested.

**A second bug on the same trail.** The GPU rung was being skipped because of a
compatibility list shipped inside TensorFlow Lite, which does not know hardware
newer than itself and answered "unsupported" for a GPU that works fine. That
list is now used for *tuning* rather than *permission* — the app tries the
delegate and lets a real inference decide.

### 3.2 The cause classifier — and why we ship it broken

| | |
|---|---|
| **What it does** | Guesses *why* the baby is crying: hungry, tired, belly pain, burping, discomfort |
| **Size** | 7.4 MB (500 decision trees) |
| **Runs on** | CPU (it is not a neural network) |
| **Accuracy** | **0.444 AUC — below chance** |

This is the most important honest thing in the project.

Cry *detection* works. Cry *cause* does not. We trained the same model twice:

- Split the recordings **randomly**: 0.95 AUC. Looks superb.
- Split them so **no infant appears on both sides**: 0.44 AUC. Below chance.

The 0.51 difference was the *evaluation method*, not the model. A completely
unrelated approach — a 461-feature random forest, different algorithm, no shared
code — lands in the same place (0.46). Two independent methods failing
identically is what turns "our model did not work" into "this is not learnable
from this data".

So the app shows the guess **with its confidence attached and the word
"probably"**, never as a fact, and the Insights screen draws both models against
the 0.50 chance line so you can see one sitting above it and the other below.

### 3.3 Face detection — BlazeFace

224 KB, runs on GPU through MediaPipe. Answers one question: can the camera still
see the baby's face? Not vitals, not breathing.

### 3.4 Pose landmarks — MediaPipe Pose

9.2 MB, on device. Turns body position into coarse events: rolled onto her front,
crawling, sat up, stood up, left the safe zone. The accelerometer and light
sensor override it — a knocked phone or a dark room suppresses the verdict rather
than reporting the baby missing.

### 3.5 Reading a medicine strip — PP-OCRv4

Three ONNX models totalling 15.8 MB: detection (finds the text), angle
classification (works out which way up), recognition (turns pixels into
characters). Runs on device, handles rotated, upside-down and white-on-foil
printing. Feeds staged agents that check the medicine against the mother's own
allergies and conditions.

Two ML Kit recognisers run alongside it — Latin and Devanagari — because Indian
packaging carries the brand in one script and the manufacturer in the other, and
because the three engines fail on different photographs. Every pass is pooled
rather than replaced, so a hard image is read jointly by all of them.

**A scan that never ended, and how we found it.** Scanning thirty real
photographs back to back turned up a scan that stops: no error, no verdict, the
process at 0% CPU. On the Scan screen that is a spinner under "Reading the
label" that stays there.

The first guess was ML Kit, which answers through a listener and could plausibly
never call it. That guess was wrong, and the way it was settled is the
interesting part. This phone throttles logcat for ordinary apps and will not let
the shell signal another process for a thread dump, so both usual routes were
closed — but an instrumentation test runs *inside* the app process, so it can
dump its own stacks. A watchdog in the test did that after ninety seconds and
named the culprit exactly:

```
Instr: androidx.test.runner.AndroidJUnitRunner [RUNNABLE]
    at ai.onnxruntime.OrtSession.run(Native Method)
    at com.nila.ocr.PaddleOcr.recognise(PaddleOcr.kt:349)
    at com.nila.assistant.TextScanner.scan(TextScanner.kt:174)
```

**PP-OCR's own recogniser.** Its input width was computed straight from the
aspect ratio of whatever the detector found, and nothing bounded it. A long thin
box — small print running the length of a bottle, or a seam read as one region —
gives a ratio in the hundreds, and at 48 pixels high that is a tensor tens of
thousands of pixels wide. `OrtSession.run` then sits in native code for minutes
at almost no CPU, or the allocation takes the process out; both were seen. The
width is now capped at twenty times the crop height, about forty characters,
and anything longer is squeezed to fit rather than padded.

Separately, each ML Kit pass now gets a deadline too — an unbounded wait on a
third-party callback in the one path a person is watching is worth a guard even
though it was not the fault here. A pass that runs out of time contributes
nothing, that recogniser is not asked again for the same image, and whatever
earlier passes read is kept.

**What the thirty photographs actually score.** They are Wikimedia photographs
of real packaging — blister foil, Cyrillic and Japanese cartons, names debossed
into bare tablets, a shop shelf with four other products in frame — chosen in
`data/README.md` for the specific ways each defeats OCR. On the phone:

| | |
|---|---|
| Reached the verdict its folder claims | **19–20 of 30** |
| Read as the **wrong medicine** | **0** |
| Could not be read at all | 10–11 |

The second row is the one that matters, and it is the assertion the test fails a
build over. Every miss is an honest *"I could not check this one"*, which for a
name debossed into a bare tablet is the correct answer rather than a failure —
one image in the set has no readable text at all and is there precisely to check
that nothing is invented from it. The set has caught a wrong drug twice, and
both are in the bug table below.

It also caught a subtler thing worth recording: amoxicillin and Augmentin came
back CAUTION where the table says *likely safe*, because the phone had the demo
profile on it and that mother has a **penicillin allergy on file**. The app was
right and the comparison was wrong. The test now holds stored records out, so a
table that knows nothing about her is compared against a verdict that also does.

### 3.5b Drug–drug interactions — a substructure cross-attention classifier

| | |
|---|---|
| **What it does** | Names the interaction between two drugs — the scanned one and something she is already taking |
| **Size** | 19.7 MB, plus 620 KB of tables |
| **Library** | ONNX Runtime, the same one PP-OCR uses |
| **Runs on** | CPU, one thread |
| **Speed** | **7.2 ms** per pair on the iQOO |
| **Accuracy** | 96.6% top-1 where it is allowed to answer |

An 86-way classifier over the DrugBank interaction ontology, trained on the
**DDI-Bench** random split (Shen et al. 2025, *Bioinformatics*). Its input is a
pair of Morgan fingerprints; its output is one of 86 interaction types, in
DrugBank's own wording — *"The risk or severity of adverse effects can be
increased when Ibuprofen is combined with Prednisolone."*

It exists because the curated corpus in `knowledge.json` covers **34** medicines
and the interaction lists in it are hand-written. What a mother is actually
taking — her thyroid tablet, her metformin, an antidepressant — is outside that
corpus, and outside it the app had nothing to say. This covers **1,710** drugs
and **188,240** recorded interactions.

**The thing that shapes the whole design: it has no "these two do not interact"
class.** Given any two drugs it returns one of 86 interaction types, including
for two drugs with no relationship whatsoever. The obvious patch — trust it only
when it is confident — does not work, and we measured that rather than assuming
it:

| separating a real DrugBank interaction from a pair that has none | AUC **0.78** |
|---|---|
| at a threshold keeping 90% of real interactions | **54%** of non-interacting pairs also pass |

So confidence is not a gate, and the model is **never asked whether an
interaction exists**. That question is answered by DrugBank's own interaction
graph, shipped as a 366 KB bitset. The model is asked only *what kind*, and only
about pairs the graph already says are real. Two questions, two answers, one of
them a fact and the other a prediction — and they are kept apart in the code as
well as in the prose.

**When it declines to answer.** Accuracy depends almost entirely on whether the
model was trained on both drugs, and barely at all on how confident it sounds:

| | both drugs trained on | one novel | both novel |
|---|---|---|---|
| top-1 | **96.6%** | 69.7% | 43.2% |
| macro-F1 | 0.852 | 0.585 | 0.183 |
| at confidence ≥ 0.9 | 98.0% | 76.1% | 50.0% |

That last row is the one that decided it. Raising the confidence bar moves the
middle column by six points — it does not rescue it. So the gate is the training
status of the two drugs, which is known exactly for all 1,710 of them (1,338 were
trained on), rather than a threshold on the model's opinion of itself. For a
pair involving a drug the model never saw, the recorded interaction is still
reported and **the type is left unnamed**: *"DrugBank records an interaction with
Tramadol, but I cannot say what kind."*

Both worked examples of that are real misses we can point at. On
Sertraline + Tramadol the model says *serotonergic* where DrugBank says
*neuroexcitatory*; on Ondansetron + Azithromycin it misses the QTc prolongation
entirely. Both involve a drug outside the training set. Both are withheld.

**Why it is an addon and not a replacement.** It runs last, after the four
curated stages have already produced a verdict. It may raise that verdict to
*check with your doctor* and it can never lower one. If a table is missing, a
name does not resolve, the model will not load or inference throws, the stage
contributes nothing and the review is byte-for-byte what it was before. Nothing
in this path can put an error on the screen.

**Honesty about what was not done.** The 188,240 true interaction types could
have been shipped as a lookup table instead of a model — about 900 KB, and exact.
The model earns its place by being 20 MB that generalises rather than 900 KB that
memorises, and by producing a probability per class; it does not earn it by being
more accurate on the pairs in the table, because it is not. Int8 quantisation
would have taken it from 19.7 MB to 7.1 MB at a cost of 2.2 macro-F1 points
(0.852 → 0.830), concentrated in the rare interaction types. That is the wrong
trade for a medicine check, so it ships as float32.

### 3.6 The vision language model — FastVLM-0.5B

| | |
|---|---|
| **What it does** | Describes what the camera can see, when asked |
| **Size** | 1.1 GB, downloaded on demand |
| **Library** | **LiteRT-LM** |
| **Runs on** | Text on CPU, image encoder on **GPU** |

Apple's FastViTHD encoder is why this is usable at all: 0.55 s to first token.
It is loaded only for a single look and released afterwards, because a gigabyte
of resident weights is not something a monitor should hold all night.

### 3.7 The chat model — Qwen 2.5, 0.5B

| | |
|---|---|
| **What it does** | Writes answers in the Ask tab |
| **Size** | 547 MB, downloaded on demand |
| **Library** | **MediaPipe LLM Inference** |
| **Runs on** | **CPU** |

This one has the most problems, and section 5 is entirely about it.

---

## 4. Why the chat model cannot use the NPU

Three separate walls, in the order we hit them.

### Wall 1 — MediaPipe does not offer an NPU

MediaPipe's LLM runtime has exactly two backends:

```
LlmInference.Backend: DEFAULT, CPU, GPU
```

There is no NPU entry. A model in MediaPipe's `.task` format is barred from the
Hexagon block **by the software**, not by the hardware. No amount of
configuration changes this.

### Wall 2 — the GPU crashes on this phone

We tried the GPU. Two findings:

First, the token limit was wrong. The model file is named
`..._ekv1280.task`, and that suffix is the size of its memory cache: 1280
tokens. Asking for more makes the GPU refuse to start:

```
Max number of tokens is larger than the maximum cache size supported: 2048 > 1280
```

The **CPU backend accepts the same wrong number without complaining**, which is
how a bad setting looked fine for as long as nothing tried to accelerate it. The
app quietly fell back and got slower.

With that corrected, the GPU **loads** in 1.6 seconds — and then crashes during
generation:

```
signal 11 (SIGSEGV), null pointer dereference
  in libllm_inference_engine_jni.so (nativePredictSync)
```

The same prompt on the CPU answers in three seconds. It is the backend, not the
input. This is a bug inside Google's native library, not in our code.

**How we handled it.** A crash like this cannot be caught — the whole app is
killed instantly, so there is no code that runs afterwards. The guard therefore
lives *outside* the process: a flag is written to disk immediately before the
call and cleared immediately after. Finding it still set at startup can only
mean one thing — the last attempt never returned. The GPU is then written off
permanently and the app uses the CPU from then on. Verified working on the
phone:

```
LlmBridge: GPU generation crashed previously; CPU from now on
LlmBridge: loaded on CPU in 198ms
```

The cost of a GPU that turns out to be unusable is therefore one crash, once,
ever — and on hardware where it works, nothing at all.

### Wall 3 — LiteRT-LM has an NPU, but not the parts it needs

Google's newer runtime, **LiteRT-LM** (already in this app for the vision
model), does have one:

```
Backend: CPU, GPU, NPU
```

So we wired the chat model through LiteRT-LM with an **NPU → GPU → CPU** ladder,
each rung proved by actually building the engine rather than trusted from a
capability list. That code is in the app now and will take the NPU the moment it
can. Two things stop it today:

1. **The Qualcomm runtime is not bundled.** The shipped native library contains
   an NPU executor (`llm_litert_npu_compiled_model_executor.cc`) but **zero QNN
   symbols** — the Qualcomm HTP driver libraries it needs are not in the alpha
   release. Without them the NPU rung throws and falls through.
2. **There is no NPU-compiled chat model.** NPU acceleration needs weights built
   with an NPU section. The only `.litertlm` file on the phone is the vision
   model.

Both are downloads and licensing, not code. The path would be a Qualcomm AI
Engine Direct / LiteRT-NPU early-access build plus an NPU-compiled chat model.

**Summary:** the cry detector is on the NPU. The chat model is on the CPU, and
the reason is the software stack available today, not the iQOO.

---

## 5. What the chat model actually did, and why we do not trust it

### 5.1 How the Ask tab works

1. You type a question.
2. The app searches **52 hand-written, sourced articles** stored on the phone —
   27 for the mother, 24 for the baby, 20 of them medicine monographs. Call it
   the library.
3. **General question, library has it** → the library text is shown directly,
   with its sources. Fast and checkable.
4. **Question about you specifically** ("given my iron deficiency") → the library
   text *plus* your own profile and records go to the chat model, which is asked
   to write a personal answer.
5. **Nothing in the library** → the model answers from general knowledge alone.
6. Whatever the model writes is then checked. **If a check fails, it is thrown
   away and the library text is shown instead.**

### 5.2 The four things it produced on a real phone

We asked four questions. **All four answers were rejected by the checks.**

| # | What it wrote | Why it was rejected |
|---|---|---|
| 1 | breast milk gives babies "nutrients and **oxygen**" | Factually false |
| 2 | "your baby is not experiencing any health issues, and **your asthma is not a problem** while breastfeeding" | An invented medical all-clear |
| 3 | "I'm sorry, but I can't assist with that." | A refusal, not an answer |
| 4 | ignored the library articles entirely | Only **4%** of its words came from the source |

**Number 2 is the serious one.** A mother with asthma on file asks a question,
and a 500-million-parameter model confidently tells her it is fine. Nothing in
her records supports that. It is the same failure that removed generation from
this app once before, when a model was handed a record saying a newborn hearing
screen was *passed*, inverted it, and told a mother her baby might be deaf.

### 5.3 The four guards now in place

Each one exists because of something we watched happen:

1. **No invented quantities.** A number with a unit that is not in the source is
   rejected. This is the one that stops a fabricated dose.
2. **Vocabulary grounding.** If less than half the content words come from the
   source, the answer is drifting and is rejected. This caught #4.
3. **No polarity flips.** Each output sentence is matched to the source sentence
   it most resembles, and the two must agree about negation. This is what catches
   a sentence built entirely from source words that says the opposite. Crucially,
   the family's own facts are now part of what an answer is checked against, so a
   flipped claim about *her record* is caught the same way.
4. **No unfounded all-clears.** An answer that clears a condition named in her own
   records — "your asthma is not a problem" — is rejected outright. This one had
   to be written *after* seeing the sentence, which is not a strategy.

Plus: model refusals are dropped, because "I'm sorry, I can't assist" is worse
than the app's own honest line.

### 5.4 Speed: 27 seconds → 3.5 seconds

The first measured run took **27 seconds** and then had its output thrown away
for being too long. The reader waited half a minute to be shown the library text
they would have had instantly. Three fixes:

- **Stop when the answer is done.** The model is now streamed and cut off after
  three finished sentences. A 0.5B model asked for three sentences does not stop
  at three sentences.
- **Halve the prompt.** One retrieved passage instead of two, tighter limits on
  the facts. 1622 → 1227 characters, which is 298 tokens (now measured, not
  estimated).
- **Fix a session bug.** Closing the session while it was still decoding threw
  *"Previous invocation still processing"*, and that exception discarded answers
  that were already complete.

Result: **3.5 seconds**. The end result is still usually the library text,
because the checks still reject what the model writes.

### 5.5 The honest conclusion

Four generations, zero usable. The safety nets held every time — but that is the
nets working, not the feature working. A 0.5B model is small enough to run
offline on a phone, and that smallness is exactly why it invents things.

**Recommendation:** keep the corpus-backed path, where the model at worst costs
a few seconds on the way to a sourced answer. Consider dropping the
no-corpus path entirely, where a model with no source is improvising about
someone's health and every guard is a specific sentence we caught after the
fact.

None of this weakens the on-device AI story. The cry detector is real on-device
AI, on the iQOO's NPU, and it is the part that works.

---

## 6. Bugs found and fixed while measuring

Listed because each one was invisible until something was measured.

| Bug | Symptom | Cause |
|---|---|---|
| Detector on CPU | Nothing — it just ran slower | Unpinned batch dimension in the exported model; NNAPI refuses non-static shapes |
| GPU never attempted | Nothing | A shipped compatibility list that does not know new hardware |
| LLM token ceiling | GPU refused to load; CPU accepted it silently | Asked for 2048 against a 1280-token model |
| LLM GPU crash | App vanished mid-question | Null dereference inside Google's native library |
| 27-second answers | A long wait, then the library text | Generation never stopped; output then rejected for length |
| Answers discarded | A long wait, then nothing | Session closed while still decoding |
| **Cry recordings never kept** | Timeline showed cries with no player | `currentEventId` was cleared *before* the function that needed it, so it returned immediately. Only cries cut off at the three-minute rung kept audio |
| Cry filed on the wrong day | An hour in the wrong column of the colic count | The seeder wrote a cry's *end* as its start time |
| Colic count disagreed with its own chart | Tile read 1/7 above a chart showing 2 | Rolling 24-hour windows in one place, calendar days in the other |
| Timeline unreadable | 39 camera events buried every cry | Repeated events were not folded |
| **A strip read as the wrong drug** | "Meftal Spas — Mefenamic acid + Dicyclomine" came back as **Aspirin**, an entry marked *avoid while breastfeeding* | Aspirin carries the alias "acetylsalicylic acid", so the lone token `acid` was a four-character name match — exactly the floor. The right answer and the one shown shared nothing but a salt word |
| Half a combination checked | A tablet containing an NSAID reported "generally compatible" | The matcher returned the single best-scoring medicine, so on "Aceclofenac 100mg + Paracetamol 325mg" the paracetamol won and the ulcer and asthma cautions were never reached |
| A brand read perfectly, recognised as nothing | "I could not check this one", under a photograph where the OCR panel plainly showed the name | 20 medicines with four aliases each against an Indian pharmacy; and no path from a brand to its composition |
| A torn name resolved to nothing | Same | "PARACET MOL" across a crease is two tokens, neither of which is a drug |
| **A fluconazole carton read as paracetamol** | A wrong drug, with a hedge attached to it | The new "Indian brands ending -P contain paracetamol" rule allowed the hyphen to be optional, so any word of five letters or more split into a stem and a tail — and **"STRIP"**, printed on every blister ever made, parsed as the brand "Stri" with the suffix "P". The hyphen is now mandatory, which is how real brands write it and ordinary words do not |
| **A black Watch screen for a minute or more** | The camera preview did not appear until long after the screen opened; the demo clip showed white for the same reason | Both detectors -- BlazeFace and a 9.2 MB pose landmarker, each verified with a real probe inference -- were built on the main thread, and in the live path *before* `bindToLifecycle`. So nothing was drawing and there was nothing bound to draw. The preview is now bound first and the models load behind it; the demo runs its decode-and-analyse loop off the UI dispatcher and shows frame zero before loading anything |
| **"Posture is unavailable on this phone"** | The warning appeared *above a posture readout saying "Sitting"* -- the pose model was plainly running | `poseAvailable` was read once, on the line after `camera.start()`. Binding goes through a listener on the main executor, so nothing had been built yet at that moment and the answer was necessarily `false`; nothing asked again for the rest of the session. Now refreshed from every analysed frame, and the warning waits for a frame before it will claim failure -- no frame is analysed until both detectors exist, so a zero frame count means warming up, not broken |
| **A scan that never ended** | The Scan screen sat on "Reading the label" with a spinner, forever — or the process died outright | PP-OCR's recogniser took its input width from the detector's aspect ratio with nothing bounding it, so one long thin box became a tensor tens of thousands of pixels wide. Found by scanning thirty photographs in a row; identified by a watchdog in the test dumping the app's own thread stacks, because this phone throttles logcat and will not let the shell signal another process |

---

## 7. What is stored on the phone, and what leaves it

**Nothing leaves.** No network requests, no API keys, no account, no analytics.
Cleartext traffic is forbidden in the manifest. The only outbound requests
possible are the two optional model downloads, each behind an explicit tap, each
with a URL that is a constant in the source.

**Audio.** The night is analysed in memory and discarded. Only a declared cry is
written to disk — into app-private storage no other app can read, left out of
every backup. Ordinary cries are deleted after 7 days; the ones that woke you,
ran to the three-minute cap, or came back as pain are kept for 30, because those
are the ones a doctor might want to hear. **Settings → Recordings of difficult
cries** turns the recorder off entirely, and off means the file is never opened,
not that it is written and then deleted.

---

## 8. Numbers you can quote

| | |
|---|---|
| Models on device | 8 |
| Network calls | 0 |
| Cry detection AUC (unseen infants) | 0.992 |
| Cry detection recall | 97.3% |
| Cause classification AUC | 0.444 (below chance — stated, not hidden) |
| Cry detector size | 36 KB |
| Cry detector latency | 0.2 ms, on the NPU |
| Windows reaching the detector in a quiet room | **0 of 106** — the rest stop at the silence gate |
| Memory while monitoring | ~206 MB |
| Sourced corpus entries | 66 |
| Medicines covered by the curated corpus | 34, with 202 brand names |
| Drugs covered by the interaction addon | 1,710 |
| Interactions DrugBank records between them | 188,240 |
| Interaction type named correctly | 96.6% where it answers, and it declines the rest |
| One interaction lookup, on the phone | 7.2 ms |
| Unit tests | 316 |

The silence-gate figure is the battery story, measured rather than claimed:
below −55 dBFS a window is a loudness comparison and nothing else — no log-mel,
no inference, no NPU. In a quiet nursery that is almost every window.

---

## 9. Where to look in the code

| Area | File |
|---|---|
| Accelerator selection for TFLite | `ml/TfliteRunner.kt` |
| Cry detector and the silence gate | `audio/CryEngine.kt` |
| The escalation ladder | `monitor/Escalation.kt`, `monitor/EpisodePipeline.kt` |
| Keeping and retaining cry audio | `audio/EpisodeRecorder.kt`, `data/ClipPolicy.kt` |
| Ask pipeline and the personal fork | `assistant/Assistant.kt`, `assistant/QueryRouter.kt` |
| LLM backends, prompts and guards | `assistant/LlmBridge.kt` |
| Vision language model | `vision/SceneDescriber.kt` |
| Reading a strip, and the five stages | `assistant/TextScanner.kt`, `assistant/agents/MedicineReview.kt` |
| Matching a brand to a medicine | `assistant/KnowledgeIndex.kt` (`findMedicines`) |
| Drug interactions: graph, model, gate | `ddi/DrugInteractions.kt` |
| Building the interaction assets | `ml/ddi/` |
| Model export and the batch pin | `ml/src/export_tflite.py` |
| The measured model card | `audio/ModelCard.kt` (generated, never typed) |
