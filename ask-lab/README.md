# Ask lab

A standalone Android app that runs Nila's **Ask** pipeline against
**Qwen3.5-2B Q4_0 on the Hexagon NPU**, so the pipeline can be measured before
any of it is put into the app.

It is a separate Gradle project. It does not appear in `android/settings.gradle`,
it builds its own APK under its own package (`com.nila.asklab`), and it shares
nothing with the app at build time — the two files it reuses, `KnowledgeIndex.kt`
and `QueryRouter.kt`, are copies. Nothing here can change what the app does.

---

## Why a lab and not a branch

The answers this pipeline gives cannot be judged by reading the code. Every
question that matters — is a retrieved passage actually about the question, does
a 2B model keep the mother's allergies separate from the baby's, does a guard
that was tuned for a 0.5B rephraser reject a 2B model's good work — is an
empirical question about a particular model on a particular phone.

So the lab answers one question per run, over a fixed set of 41 probes, and
writes a JSON report `adb pull` can fetch. Eight runs produced the numbers below, and
four of the decisions in the pipeline were changed by them — one of them twice,
after a JVM test over a larger sample showed the first answer was overfitted.

---

## The pipeline

One pass, four leaves. No agent loop, no re-ranker, no second retrieval round:
the corpus is 27 guidance entries, which fits in a prompt several times over,
so there is nothing for another hop to improve on.

```
question
  |
  +-- GUARD        emergency / distress / dose / diagnosis / off-topic / greeting
  |                -> a written-in-advance reply. No retrieval, no model.
  |
  +-- EXACT        "when did she last feed"  -> the log, to the minute
  |                "is amoxicillin safe"     -> the medicine entry, verbatim
  |
  +-- RETRIEVE     BM25 over the sourced corpus, with a floor (see below)
  +-- REMEMBER     profile + today's log + the records this question reaches
  |                + the last two turns of this conversation
  |
  +-- one of:
       A FAQ_DIRECT   strong hit, nothing personal asked
                      -> the sourced passage itself. No model. 12-40 ms.
       B GROUNDED     hit + something personal
                      -> model(question, passages, selected facts)
       C MEMORY_ONLY  no hit, but this family's facts bear on it
                      -> model(question, -, selected facts)
       D GENERAL      no hit, nothing personal
                      -> model(question, -, -), and the card says so
```

Every leaf has a complete answer *before* the model is asked for anything. A
rejected generation costs latency and nothing else.

---

## What the runs changed

Four things in this pipeline are the way they are because a run said so.

### 1. Retrieval is gated on topic, not on score

BM25 over 27 documents always returns its nearest neighbour, and something has
to decide whether that neighbour is an answer. A weak passage shown to a reader
is one they can dismiss; the same passage handed to a language model becomes a
fluent, sourced, confident answer to a question nobody asked.

The first attempt was a score floor of 6.5, set from the probe runs, where every
hit that answered its question scored 7.0 or more and every hit that did not
scored 6.1 or less. **That was 41 questions and it was wrong.** Run against the
app's own 46 retrieval cases, a floor of 6.5 throws away five correct
retrievals, including *"when should I take my baby to hospital"* at **5.50** —
while *"what is a good bedtime routine"* returns the wrong document at **5.51**.
There is no floor between them.

What does separate them is **which field matched**. Every entry carries a title
and a hand-written keyword line saying what it is about. The wrong hits all
matched on body text alone:

| question | matched on | returned |
|---|---|---|
| Is my asthma a problem for breastfeeding? | `problem` | How much babies sleep |
| Did she pass her hearing test? | `test` | Colic and the rule of threes |
| Why do babies get hiccups so often? | `so` | How much crying is normal |
| Is she growing well? | `grow`, `well` | Colic and the rule of threes |

So a hit is usable when the question meets the document's **topic words** twice,
or once on a term rare enough to be a subject rather than scenery — three
documents out of sixty-six. `colic`, `honey`, `grapes`, `smile` and `fever`
qualify; `night` (7) and `so` (6) do not.

**Keeps 46 of 46 correct retrievals. Refuses 8 of 8 questions the corpus cannot
answer.** The app's own floor of 2.8 is unchanged and still runs underneath.

`RetrievalGateTest` is that comparison, and it runs on the JVM without a device.

### 2. The facts are retrieved, not dumped

The first version handed the model everything it knew on every question. A 2B
model given a fact will use it:

> **Did she pass her hearing test?**
> Yes, Meera passed the newborn hearing screen. … *I also know that you have
> asthma and iron deficiency anaemia, so please take care with your diet and
> exercise regularly.*

> **My iron tablets upset my stomach, what can I do?**
> *Iron supplements are not known to cause stomach upset in breastfed babies,
> so this is likely unrelated to your baby Meera.*

`FactSelector` picks the log lines, the profile lines and the records this
particular question reaches, the same way retrieval picks passages. The baby's
name and age always survive, because they are the frame the answer is written
in rather than a fact the answer is about.

### 3. The two subjects are kept apart in the prompt

A flat bullet list of facts produced two failures that are the same failure:

> *"With only six weeks and eight minutes passed since your last feed"*
> — from `Mother's notes: Six-week check normal` and `Last feed 8 minutes ago`.

> *"Since she is exclusively breastfed and not allergic to penicillin"*
> — the **mother's** allergy, moved to the baby and inverted on the way.

The prompt now has `ABOUT MEERA`, `ABOUT THE MOTHER`, `FILED RECORDS FOR MEERA`
and `FILED RECORDS FOR THE MOTHER` as separate blocks, with a rule that a fact
may not move between them. Both failures stopped.

### 4. The grounding floor came down, from a measurement

The app's `Grounding.isGrounded` requires half the answer's content words to
come from its source. That was set against a 0.5B model that stayed close to
its source because it could not do much else, and at 0.5 it rejected **every**
personalised answer a 2B model produced, good ones included.

Measured: answers that used their source scored **0.30 – 0.82**; the one that
had stopped using it scored **0.26**. The floor is 0.30.

It is a weaker check than it was, and that is only acceptable because it is no
longer the only thing standing between the model and the screen — retrieval now
refuses to supply a passage it is not confident in, and the facts are selected
per question.

---

## Running it

```bash
cd ask-lab
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Put the weights where the app can read them:

```bash
adb push Qwen3.5-2B-Q4_0.gguf /data/local/tmp/Qwen3.5-2B-Q4_0.gguf
adb shell "run-as com.nila.asklab sh -c 'mkdir -p files/local && cp /data/local/tmp/Qwen3.5-2B-Q4_0.gguf files/local/'"
```

Then drive it from the screen, or from the command line:

```bash
adb shell am start -n com.nila.asklab/.MainActivity --es ask "'Is my asthma a problem for breastfeeding?'"
adb shell am start -n com.nila.asklab/.MainActivity --es run eval
adb pull /storage/emulated/0/Android/data/com.nila.asklab/files/eval-latest.json
```

The scripted route is not a convenience. The phone this was measured on keeps
another app in the foreground and kills a backgrounded process holding 1.2 GB
of weights, so a run that depends on taps landing where a screenshot said they
would is not a measurement. The eval runs in a foreground service and writes its
report after **every** probe, because two runs were lost whole before it did.
