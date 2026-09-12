# Baby Cry Playbook

**Paper + deck + repo review · literature · market research · hackathon build plan**
Prepared 5 Sept 2026 — target: iQOO City Battles 2026 (Grand Finale, Bengaluru, 9–11 Oct)

Live version: https://claude.ai/code/artifact/d33cf6ad-3aeb-4cb8-8460-29e025c102e6

---

## The one-paragraph version

Your paper reports **98.76%** on the Donate-A-Cry corpus and claims a new benchmark. A 2026 controlled study reproduced almost exactly that number — **97.9%** — using the same augment-before-split protocol your Table II describes, then showed that under a correct subject-wise split the same models collapse to **0.38–0.54 macro AUC**, which is chance across five classes. Worse, the paper's own Table I contains the number that undoes the headline: **382 of 457 clips are "hungry," so guessing that one class scores 83.6%**. And the confusion matrix gives it away arithmetically — the test set holds **35 belly-pain samples when only 16 exist in the entire corpus**.

**And then the deck settles it.** Slide 16 reports a full model comparison — SVM, KNN, Decision Tree, Random Forest, AdaBoost, Gradient Boosting — where Random Forest scores **0.783 accuracy and 0.687 F1**. That number appears nowhere in the paper. It is almost certainly the clean run, and it sits *below* the 83.6% baseline, exactly where the literature says this task actually lands. **You already ran the honest experiment. It's on slide 16.**

What *is* solid: **cry detection** reaches **0.988 AUC**, and it already ships inside Alexa, Nanit, Cubo AI and Miku. Which means the winning hackathon build is not a better cry translator. It is **an on-device infant safety and context system** that treats cry detection as a reliable primitive, adds the camera-based safety layer currently locked inside ₹18,000–₹35,000 hardware, and is honest about uncertainty where the science is honest about uncertainty.

---

# Part 1 — The paper, the deck and the code

**"Why is My Baby Crying? Explainable Emotion Recognition with Domain-Aware Ensemble Models"** — Vishal A S, Sarvesh S, Durai Singh, Kishor S and Jyothish Lal G, Amrita School of Artificial Intelligence. An 8-page IEEE-format paper, backed by the repository reviewed below. A classic pre-transformer pipeline: hand-engineered acoustic features feeding a Random Forest, explained with LIME, deliberately avoiding deep learning to stay light enough for edge deployment.

| | |
|---|---|
| **461** | handcrafted features per clip |
| **457** | real corpus clips, before augmentation |
| **98.76%** | reported accuracy, F1 and recall |
| **239/242** | what that accuracy actually is: 3 errors on the test set |
| **78.3%** | what slide 16 of the deck reports for the same model |

## What the paper claims

The argument is coherent and well-motivated: the Donate-A-Cry corpus is severely imbalanced, so fix it with signal-level augmentation plus SMOTE; classical ensembles beat deep nets on small tabular feature spaces; add LIME so caregivers and clinicians can see why a prediction was made; ship it on edge devices. Preprocessing is pre-emphasis (α = 0.97) then a 5th-order Butterworth band-pass at 142.91–6620.12 Hz. Random Forest with 500 trees at depth 20. Five classes, five per-class LIME figures, a feature-importance ranking, and a comparison table concluding the method sets "a new benchmark on the Donate-a-Cry dataset."

| Class | Real clips (Table I) | After augmentation (Table II) | In the test set (Fig. 3) | |
|---|---:|---:|---:|---|
| hungry | 382 | 382 | 77 | |
| discomfort | 27 | 297 | 59 | **> all real clips** |
| tired | 24 | 264 | 53 | **> all real clips** |
| belly_pain | 16 | 176 | 35 | **> all real clips** |
| burping | 8 | 88 | 18 | **> all real clips** |
| **Total** | **457** | **1,207** | **242** | |

**Read the last two columns together.** The test set contains 35 belly-pain samples, but only 16 belly-pain recordings exist in the entire corpus. 18 burping samples, from 8 real clips. That is arithmetic proof that augmented copies of training recordings are sitting in the test set — the leakage isn't a suspicion, it's forced by the numbers the paper itself prints. Three of the four minority classes are in the same position.

## Errors in the paper a reviewer will catch

Separate from the evaluation problem, these are concrete and cheaply fixed. Every one is checkable against the paper's own text.

- **[ARITHMETIC] Table III doesn't sum to 461.** As printed it totals **458**. The error is the spectral-contrast row: it lists `7 × 3 = 21`, but `librosa.feature.spectral_contrast(n_bands=7)` returns **n_bands + 1 = 8** sub-bands, so it is `8 × 3 = 24`. With 24 the table sums to exactly 461 — the real figure, confirmed by unpickling the model (461 `feature_names`) and by `extracted_features.xlsx` having 461 columns. Fix the row, not the total.
- **[CLAIM] "Setting a new benchmark" rests on roughly one test sample.** Table IV's best prior result is Deep-SVM at 98.4%; 98.76% − 98.4% = 0.36 points, which on a 242-sample test set is **0.87 samples**. No confidence interval, no repeated runs, no significance test. Either report mean ± std over multiple seeds, or drop the claim.
- **[CITATION] The dataset citation is wrong.** Reference [17] is used throughout for the DonateACry corpus, but [17] is Lavner et al., *Baby cry detection in domestic environments* (IEEE/ACM TASLP) — a detection paper, not the corpus.
- **[CITATION] Duplicate and malformed references.** [6] and [7] carry identical titles with different authors and years; [11] and [21] are both "Machine learning-based infant crying interpretation, *Frontiers in AI*" under different authors. Two `[?]` unresolved citations survive in Sections IV-C and VI, and `Fig. reffig:1` is a broken LaTeX cross-reference on page 3.
- **[CONSISTENCY] The band-pass range has two contradictory origins.** Section III-A says 142.91–6620.12 Hz "was determined from the spectral concentration of cry signals... using Fourier analysis," but the literature review attributes the same range to [11] (printed there as "42.91–6,620.12 Hz" — a typo). Pick one story.
- **[METADATA] Author metadata is incomplete.** Two ORCIDs are blank; the last author's ORCID field contains `00034-019-01253-4`, a DOI fragment. The title promises "Ensemble Models" plural, but only Random Forest is evaluated.

## The deck — 30 slides, and the experiment the paper left out

`SPEECH PROCESSING.pptx`, Batch B Group 1: Sarvesh K, Vishal A S, Durai Singh K, Kishor S. Structure is sound — problem, motivation, objectives, dataset, literature survey, novelty, architecture, preprocessing, augmentation, features, model comparison, results, feature importance, five LIME slides, TabNet, agentic system, references. Three things in it matter a great deal, and none made it into the paper.

### Finding 1 — slide 16 is your real result

- **The number:** the model-comparison charts report **Random Forest at 0.783 accuracy / 0.687 weighted F1**, with SVM and KNN level at 0.783, AdaBoost 0.765, Gradient Boosting 0.748, Decision Tree 0.696. Runtimes too: RF at 0.19–1.42s against Gradient Boosting's 11.2s — a genuinely useful efficiency argument.
- **Why it matters:** the accuracy/F1 gap is the tell. 0.783 accuracy against 0.687 F1 is the classic signature of a model leaning on the majority class — and 0.783 is *below* the 83.6% you'd get by always answering "hungry." That is precisely the picture the 2026 controlled study predicts for a clean split, and it's nowhere in the paper. Confirm which script produced this chart before quoting it, but the numbers line up with the un-augmented 366/91 split slide 5 describes.

### Finding 2 — the deck and paper describe two different experiments

| Item | The deck says | The paper says | Reading |
|---|---|---|---|
| **The split** | Slide 5: **366 train / 91 test** — 80/20 of the raw 457 clips | Table II + Fig. 3: a **242-sample** test set, 20% of the 1,207 augmented pool | **Slide 5 describes the correct protocol.** The paper reports the leaky one. 366+91 = 457 exactly. |
| **RF performance** | Slide 16: **0.783 acc / 0.687 F1** | 98.76% accuracy, F1 and recall | Two runs, 20 points apart, never reconciled anywhere. |
| **SMOTE step** | Slide 13: "from **966** samples to 1525" | 1,207 → 1,525 | 966 = 80% of 1,207 — arithmetic proof the split came *after* augmentation in the run behind the paper. |
| **Band-pass origin** | Slide 11: FFT analysis of **50 random cry samples** gave 142.91–6620.12 Hz | §III-A says derived; §II credits it to [11] | **The deck resolves this.** It was derived, n=50. Put that in the paper and delete the [11] attribution. |
| **TabNet** | Slide 24: ~91% acc, ~88% F1 | absent | A deep tabular baseline you actually ran — belongs in the paper, especially as 91% undercuts the "we beat deep learning" framing. |
| **References** | Slides 28–29: 12 IEEE papers, 2003–2019 | 28 references, a different set | Neither bibliography cites the other's. Both skew old. Slide 2's [1]–[3] also collide with slides 28–29's [1]–[3]. |

### Finding 3 — the deck's strongest asset is slide 2, and the paper doesn't use it

- **Keep, and lead with this:** the problem statement carries real, sourced stakes — **5.6% of parents of 6-month-olds reported smothering, slapping or shaking their baby over crying**; up to **27%** report problems with infant crying in the first four months; excessive crying affects **~20%** of infants and is linked to parental anxiety and depression. Far better motivation than "parents find it hard to tell cries apart," and it's your hackathon opening slide almost verbatim. Verify each citation before it goes on stage.
- **Rename:** the **"Agentic System"** slides are screenshots of the Streamlit app calling Gemini once with a formatted prompt. Good demo — the advice reads calmly and specifically, and it showed **81.6% confidence on a real hungry clip**, a much more honest number than 98.76% and worth showing for that reason. But it is single-shot prompting, not an agent: no tools, no loop, no planning. Call it what it is, or make it genuinely agentic for the hackathon.
- **Gap:** slides 3, 9, 10, 14, 15 carry their entire content in images with no speaker notes anywhere in the file, so motivation, architecture and feature tables exist only as pictures — fine live, a problem for anyone reading cold, and none of it is searchable or reusable.

## The code behind it

3 commits, 3 notebooks, 2 app scripts, 4 trained checkpoints (15–29 MB pickles committed straight to git).

## The signal-to-advice pipeline

```
Cry recording (.wav/.mp3)
  → Pre-emphasis (α = 0.97)
  → Butterworth band-pass (142.9 – 6620 Hz, order 5)
  → librosa feature bank
      MFCC(40) + Δ + ΔΔ, ZCR, RMS,
      spectral centroid/bandwidth/rolloff/contrast,
      chroma, mel-spectrogram, tonnetz
  → 461-d statistical vector (mean/std/skew/max/min)
  → SMOTE (train fold) + class-balanced Random Forest (500 trees, depth 20)
  → Predicted cause + class probabilities
  → Gemini 1.5 Flash prompt (cause + baby age/feeding/sleep + last 10 chat turns)
  → Streamlit UI
```

Feature code is hand-copied into every file that needs it — `pre_emphasis()` and `bandpass_filter()` appear verbatim in five places.

### The 461 features, verified by unpickling `baby_cry_model2.pkl`

| Feature family | librosa call | Coeffs | Stats | Count |
|---|---|---:|---|---:|
| MFCC | `mfcc(n_mfcc=40)` | 40 | mean, std, skew, max, min | 200 |
| MFCC Δ | `feature.delta` | 40 | mean, std | 80 |
| MFCC ΔΔ | `delta(order=2)` | 40 | mean, std | 80 |
| Zero-crossing rate | `zero_crossing_rate` | 1 | mean, std, max | 3 |
| RMS energy | `rms` | 1 | mean, std, max, min | 4 |
| Spectral centroid | `spectral_centroid` | 1 | mean, std, max | 3 |
| Spectral bandwidth | `spectral_bandwidth` | 1 | mean, std, max | 3 |
| Spectral rolloff (95th pct) | `spectral_rolloff` | 1 | mean, std | 2 |
| Spectral contrast | `spectral_contrast(n_bands=7)` | 8 | mean, std, max | 24 |
| Chroma (STFT) | `chroma_stft(n_chroma=12)` | 12 | mean, std | 24 |
| Mel-spectrogram | `melspectrogram(n_mels=13)` | 13 | mean, std | 26 |
| Tonnetz | `tonnetz` | 6 | mean, std | 12 |
| **Total** | | | | **461** |

### The four checkpoints

| Checkpoint | Source | Classes | Accuracy | Used by |
|---|---|---|---:|---|
| `baby_cry_model2.pkl` | Donate-A-Cry | 5 (need) | **98.76%** | `sample2.py` |
| `baby_chill.pkl` | Donate-A-Cry, re-run | 5 (need) | 97.14% | — unused |
| `baby_chill1.pkl` | merged folder | 8 (need + pathology) | 97.66% | — unused |
| `baby_chillsec.pkl` | merged, tighter STFT | 8 (need + pathology) | 96.04% | — unused |

Evidence images in the repo:
- `Why-is-my-Baby-Crying/confusion_matrix.png` — 8-class run, Donate-A-Cry need labels merged with Chillanto pathology labels (`FullNormal`, `Full_asphyxia`, `Full_deaf`)
- `Why-is-my-Baby-Crying/feature_importance.png` — six of the top eight features are MFCC or mel-spectrogram coefficients in the low-order cepstral region
- `Why-is-my-Baby-Crying/lime_explanation.png` — **keep this.** Per-prediction explanations are one of the few genuinely differentiating assets you already own; no consumer cry app shows why it decided what it decided.

## Carry-over issues to fix before this becomes an app

- **[RISK] A live Gemini API key is hardcoded and committed to a public repo** — plaintext in `ai.py` and as `GEMINI_API_KEY` in `sample2.py`. Revoke it in Google AI Studio now; it is in git history even if you delete the line. The hackathon build should have no cloud key at all.
- **[METHODOLOGY] Augmentation runs before the train/test split.** Each clip is expanded into up to 10 pitch/time/noise variants, then the pooled set is split 80/20 — so variants of the same recording sit on both sides. This is the exact protocol the literature has now identified as the source of inflated infant-cry results.
- **[BUG] The "Model File Path" sidebar field in `sample2.py` does nothing** — `load_model()` ignores its argument and hardcodes `baby_cry_model2.pkl`.
- **[NOTE] `shl.ipynb` is unrelated** — an XGBoost regressor against a continuous 0–5 `score`, reusing the feature code. Looks like a different competition's notebook.

---

# Part 2 — Research review

Infant cry analysis splits into two tasks that get conflated constantly. **Detection** — is that sound a baby crying — is a solved, deployable problem. **Reason classification** — why is the baby crying — has a decade of 95–99% claims sitting on top of datasets and protocols that cannot support them.

## The datasets everyone uses

| Dataset | Size | Classes | The catch |
|---|---|---|---|
| **Donate-A-Cry** (yours) | 457 clips (433 after cleaning, 204 infants) | belly_pain, burping, discomfort, hungry, tired | **84% of clips are "hungry"** — a majority-class guess scores 83.8%. Parent-labelled, no clinical ground truth. |
| **Baby Chillanto** (yours) | 2,268 recordings | normal, asphyxia, deaf, hunger, pain | Restricted access, small, clinical recordings from one setting — models don't transfer to home audio. |
| **Dunstan Baby Language** | ~83 cries (39 infants) | hungry, burp, sleepy, pain, discomfort | Tiny. Built on a commercial claim never independently validated. |
| **DA-IICT (India)** | 1,190 samples | healthy, asthma, HIE | In-house, not publicly redistributable. |
| **ICSD** (2024, open) | — | infant cry + snoring detection | Newer open dataset aimed at *detection*, which is the task that actually works. |

A 2025 systematic review pooled 17 studies covering roughly **113,677 cries**, and found self-recorded datasets scored 44.5–97.5% while public ones scored 86.1–99.82% — a gap that says more about dataset difficulty and leakage than about model quality. It also flags that **chance level varies from 21.6% to 61.7%** across these tasks, so a bare accuracy figure is close to meaningless without its baseline.

## The headline results, and what they rest on

| Work | Approach | Reported | Reading |
|---|---|---|---|
| PLSTM-AL (2025) | Pyramidal LSTM + auxiliary layer | 99.73% DCC / 97.88% Chillanto / 92.15% Dunstan | Current headline SOTA claim. Same corpora, same protocol concerns. |
| GMM + CQCC (review's best) | Classical Gaussian mixtures on constant-Q cepstral coefficients | 99.82% (hunger/pain) | A **classical** model tops the deep-learning results — a strong hint the benchmark, not the architecture, is doing the work. |
| GMM / GSFM — deafness | MFCC + CQCC features | 99.42–99.82% | Pathology detection on Chillanto. Clinically framed, single-site data. |
| DNN — sepsis / RDS | GFCC + harmonic ratio | 97.50% | Most promising *medical* direction; needs hospital data you don't have. |
| IncryNet (2025) | Discrete Stockwell Transform → time-frequency images | — | Neo-Cry + Donate-A-Cry. The "turn audio into images" school. |
| ViT vs CNN (2024) | Architecture comparison on cry spectrograms | — | Published in *Future Internet*. Same data limits. |
| Federated transformers (2025) | Privacy-preserving training + denoising regularization | — | The direction that matters commercially: never move baby audio off-device. |
| **Self-supervised pretext tasks** — **the cautionary result** | Controlled comparison, subject-wise vs clip-wise splits | detection **0.988 AUC** / reason **0.38–0.54** macro AUC | **The most important paper for you.** Detection is excellent. Reason classification is at chance once you split by infant. |

## Conference lineage

The task has a real presence at top speech venues, which is useful for your pitch's credibility: **ICASSP 2022** (Yao et al., infant cry detection in real-world environments), **Interspeech 2018** (Turan & Erzin, capsule networks for domestic cry monitoring; Sailor & Patil, ConvRBM learned auditory filterbanks). Recent work clusters on arXiv and in journals like *Biomedical Signal Processing and Control*, *Engineering Applications of AI*, and *Frontiers in Artificial Intelligence*. The through-line: architectures keep changing, the datasets don't.

---

# Part 3 — Are the top papers beating your result?

Short answer: no, and neither are you. You are all on the same rung of the same broken ladder.

| Protocol | Accuracy | Macro AUC | What it means |
|---|---:|---:|---|
| Majority-class guess ("always hungry") | 83.8% | 0.50 | The floor. Any result must beat this to mean anything. |
| Clip-wise split (common in literature) | 85.2% | 0.70 | Barely above the floor once measured properly. |
| **Augment-before-split** (your protocol) | **97.9%** | **0.976** | The controlled study's reproduction of "SOTA" — statistically indistinguishable from your **98.76%**. |
| **Your deck, slide 16** (clean run) | **78.3%** | **0.687 F1** | Random Forest without the leakage. Below the baseline, consistent with everything the literature reports under honest evaluation. |
| Subject-wise split (correct) | — | 0.38–0.54 | Chance. A frozen HuBERT with 94M parameters lands at 0.42. |

**Read that table top to bottom and the story writes itself.** Your two numbers are the two ends of it: 78.3% from the clean run on slide 16, 98.76% from the leaky one in the paper. The 20-point gap between them *is* the finding. Most groups in this literature only ever report the second number, because they never ran the first. You ran both — you just published the wrong one.

## Your Table IV, with the missing rows added

The paper's comparison table lists five prior results and concludes it beats them all. Two rows are missing that change the conclusion — the majority-class baseline, and the higher numbers already published on this same corpus.

| Method | Accuracy | Status in the paper |
|---|---:|---|
| **Majority class ("always hungry")** | 83.6% | **missing** — derivable from the paper's own Table I |
| 4-layer CNN with augmentation [6] | 65% | listed — *18 points below* the baseline |
| GMM [8] | 81.27% | listed — below the baseline |
| AlexNet [15] | 84.78% | listed — statistically level with the baseline |
| Random Forest [5] | 96.39% | listed |
| Deep-SVM [7] | 98.4% | listed — the margin over this is 0.87 samples |
| **This paper** | **98.76%** | claimed as a new benchmark |
| **PLSTM-AL (2025)** | 99.73% | **missing** — already higher, same corpus |
| **GMM + CQCC** | 99.82% | **missing** — higher still |

Three of the five cited comparisons sit at or below the do-nothing baseline, which is the clearest possible sign that these numbers were produced under incompatible protocols and cannot be ranked in one column. And on this corpus, published results above 98.76% already exist. **The benchmark claim fails twice over** — once because the comparison isn't like-for-like, and once because it isn't even the highest number on the board.

## What the paper already says, and doesn't apply to itself

- **In your own text:** Section II states *"Deep learning models are typically not good at real data testing even if they report 96.39% accuracy,"* and notes an **F1 drop of 30%** when CNNs trained in controlled settings met noisy home recordings. The paper diagnoses the generalization gap in others, then reports a controlled-setting number of its own without external validation. Section V-C concedes this — *"external validation on different datasets is warranted"* — one section too late and one caveat too soft.
- **Strength — where the paper is genuinely aligned with the field:** its "classical beats deep here" argument holds up. The best result in the 2025 systematic review is a **GMM with CQCC features at 99.82%** — a classical model topping the deep-learning entries. Per-class LIME explanations are also a real contribution: explainability is thin across this literature, and no consumer cry app offers it at all.
- **Gap — the related work stops in 2024.** Missing: subject-wise vs clip-wise evaluation as a topic at all, self-supervised speech models (HuBERT, wav2vec 2.0) applied to cry, federated/privacy-preserving cry classification, ViT-vs-CNN comparisons, and the ICSD open dataset. For a 2026 submission that's a visible hole.

## Salvaging the paper — a stronger version of the same work

Nothing here means the work was wasted. It means the headline is pointed at the wrong claim. The pipeline, the 461-feature engineering, the LIME analysis and the augmentation study are all real assets. Re-aimed, this becomes a paper that is *harder* to reject than the current one, because its central finding survives scrutiny instead of dissolving under it.

0. **Find the script behind slide 16.** Before writing anything, recover the run that produced 0.783 / 0.687 across six classifiers. That is your clean baseline and it already exists — this rewrite is mostly promoting numbers you have, not generating new ones.
1. **Move augmentation inside the split.** Split first, augment only the training fold — a few lines in `endsem.ipynb`: the loop that builds `features` moves after `train_test_split`. Your SMOTE step is already correctly placed on the training fold; copy that pattern.
2. **Report the baseline in every table.** 83.6% majority class, printed next to every accuracy figure. This alone puts the paper ahead of most of what it cites.
3. **Switch the headline metric.** Accuracy is uninformative at 84% imbalance. Report **macro F1 and macro AUC**, plus per-class recall for the 8-clip burping class.
4. **Add the comparison you can uniquely make.** Run both protocols and publish both columns: augment-before-split vs augment-after-split, identical features and model. That table *is* the contribution — an independent replication of the leakage effect from a group that originally reported the inflated number.
5. **Retitle around what you actually show.** e.g. "How Much of Infant Cry Classification Accuracy Is Data Leakage? A Controlled Study on Donate-A-Cry." Same experiments, same figures, a claim that holds.
6. **Optional — split by infant.** Donate-A-Cry filenames encode a UUID per recording plus gender and age (`…-1.1-m-26-bp.wav`). Grouping by UUID prefix approximates the subject-wise split the 2026 study argues for.

## How to say this in a pitch without losing the room

> "Published infant-cry systems report 96–99% accuracy. We reproduced that. Then we split the data by infant instead of by clip, and it fell to chance — which matches the 2026 controlled study on this corpus. So we stopped building a cry translator and built the part that actually holds up."

That single slide does more for **novelty and technical depth** (35% of the jury score combined) than any accuracy number would. Juries drawn from AI/ML product backgrounds recognize honest evaluation instantly, and most hackathon demos in this category will be claiming 95%+ with a straight face.

Your 8-class merged model still has a legitimate use: **pathology screening as a research demo** (asphyxia / hearing-loss cries), clearly labelled as experimental and non-diagnostic. Don't put it in the main flow.

---

# Part 4 — Market research

The category splits cleanly into **expensive hardware that has real AI** and **free apps that have none**. That split is the entire opportunity.

| | |
|---|---|
| **$1.99B** | smart baby monitor market in 2026, growing 8.4% a year |
| **$3.36B** | interactive baby monitors 2026 → $9.88B by 2034 (14.4% CAGR) |
| **8.9%** | India CAGR to 2030 — fastest-growing market in Asia Pacific |
| **$113.9M** | projected India smart baby monitor market by 2030 |

## Hardware — where the real AI lives

| Product | Price | AI features | Catch |
|---|---|---|---|
| **Cubo AI Plus** | ~$199–299 | Cry detection, **covered-face detection**, **rollover alerts**, **danger-zone alerts**, cough detection, 2.5K QHD | The closest thing to your safety idea — and it needs their camera. |
| **Nanit Pro** | $299 + $100/yr | SoundCare cry alerts in 3–5s, breathing motion via camera, sleep analytics, cry classification above chance | Core analytics locked behind an annual subscription. |
| **Miku** | $399, no sub | Contactless breathing, cry classification, **no cloud video storage** | Privacy is their headline feature — validates that angle. |
| **Owlet Dream Sock** | ~$299 | Pulse-ox wearable; **FDA de novo cleared** | Was pulled from market after a 2021 FDA warning letter. See positioning risks. |

## Apps — free, popular, and AI-free

| App | What it does | What it lacks |
|---|---|---|
| **Bibino** / **Baby Monitor 3G** (TappyTaps) | Turns **two phones into a monitor** — live video over Wi-Fi or mobile data, motion detection, activity log, lullabies, night light | Motion thresholds, not understanding. No pose, no safety AI, no cry reasoning. |
| **Dormi** | Two-device monitor, end-to-end encrypted, works over Wi-Fi Direct / hotspot with **no internet** | Transport is excellent, intelligence is absent. |
| **ChatterBaby** (UCLA) | Research-grade cry analysis: ~90% of pain cries flagged, **71.5%** on the 3-way fussy/hungry/pain split. Built for deaf parents. | Only three categories, and the honest number is the 71.5% one. Still the most credible app in the category. |
| **Cry Analyzer / Baby Translator** clones | A dozen near-identical App Store listings promising cry translation | No published validation. Press coverage calls the category "snake oil and bad science." |
| **Alexa Sound Detection** | Free, built into every Echo: detects baby cry → triggers a routine (play a lullaby) | Proves cry *detection* is commodity. Don't pitch that alone. |

## The six gaps

1. **Safety AI is hardware-locked.** Rollover, covered-face and danger-zone detection exist only inside ₹18,000–₹35,000 cameras. No app does this on hardware you already own.
2. **Phone apps are dumb pipes.** Bibino and Dormi nailed two-device streaming years ago and stopped. They ship motion thresholds, not perception.
3. **A credibility vacuum.** Cry-translation apps overclaim so badly the press dismisses the category. Calibrated honesty is an unoccupied position.
4. **Cloud video on a sleeping baby.** Miku sells "no cloud storage" as a premium feature. Fully on-device beats that, and it's free to do on an NPU.
5. **Nothing built for India.** Co-sleeping and joint families are the norm, budgets rule out $300 cameras, and no product speaks regional languages or works offline on patchy Wi-Fi.
6. **No learning tied to observation.** Parenting content is a separate library you never open. Nobody surfaces the right guidance *at the moment the device saw something*.

---

# Part 5 — Refined concept

## Paalna — the ₹35,000 baby monitor, built from the old phone in your drawer

*Paalna* (पालना, "cradle"). Alternatives: **Lori** (lullaby), **CribSense**, **Doli**. Pick one and put it on every slide — named products read as products, unnamed ones read as class projects.

**The pitch in one line:** two phones, zero cloud, zero subscription — the old phone watches the crib with the same safety AI as a Cubo AI, the iQOO phone is the parent unit, and every model runs on the Snapdragon NPU so no video or audio of a baby ever leaves the room.

Your three original ideas all survive, but reordered by what the evidence supports. **Safety monitoring leads**, because it works and it's what parents actually pay for. **Cry intelligence is second**, reframed from translation to context-grounded reasoning. **Learning is third**, rebuilt as event-triggered micro-guidance rather than a course library nobody opens.

### Architecture

```
GUARDIAN PHONE (old device in the crib — all inference on-device)
  Camera + Mic (CameraX · 16 kHz)
    ├── Audio: cry detector (YAMNet / AST head · NPU · <10 ms)
    │     → cry state + intensity (duration · escalation curve)
    └── Vision: pose + scene (Pose Landmarker · 5–10 fps)
          → safety rules (rollover · face covered · edge approach · climb-out)
                        ↓
             Event fusion — severity tiers
             on-device beep for imminent-fall events (no network needed)
                        ↓
             Local WebRTC (LAN only, E2E)
                        ↓
PARENT PHONE (iQOO device)
  Live view + timeline  →  Context log (last feed · last sleep · diaper · room temp · age)
                        ↓
             On-device LLM — Gemma 3n via MediaPipe
             reasons over evidence + log, answers in the parent's language
                        ↓
             Calm, specific guidance  +  Micro-lesson for what it saw

PRIVACY BOUNDARY: no audio, video or transcript leaves the two devices.
Nothing is uploaded. No account required. Works with the router unplugged.
```

### The four features, in build priority

**P0 — Safety watch.** Rollover onto stomach, face covered by blanket, crib-edge approach and climb-out. Escalating local beep from the Guardian phone the moment a fall looks imminent — no network round trip, because the alert has to fire before the fall, not after. This is the feature Cubo AI charges for.

**P0 — Cry detection + intensity, not translation.** Reliable "your baby is crying" in under a second, plus an **escalation curve**: how long, how loud, is it building or settling. That curve is genuinely informative and honest. No hard label claiming to know why.

**P1 — Context reasoning, on-device.** Gemma 3n takes the acoustic evidence *plus* the log — 3 hours since the last feed, woke 20 minutes ago, room at 31°C — and reasons out loud: "most likely hunger given the gap since the last feed; the cry pattern alone isn't conclusive." Shows its reasoning, states its confidence, answers follow-ups in the parent's language, offline.

**P2 — Learning that arrives on time.** Not a course library. When the Guardian sees a rollover, the parent gets a 30-second safe-sleep card *right then*. Blanket over the face → a suffocation-risk card. Baby-led weaning, choking response, CPR basics — each triggered by what the system actually observed. This is the retention loop and it costs almost nothing to build.

### Cuts I'd make to your original brief

- **[CUT] CCTV / RTSP camera integration.** Tempting, but it's a compatibility rabbit hole that eats hours and scores nothing — the rubric explicitly rewards *phone* use, read off device data. A second phone as the crib camera is faster, more reliable, and directly on-rubric.
- **[REFRAME] "Understand why the baby is crying."** Keep the intent, drop the certainty. Every judge who searches this space will find the "snake oil" coverage; a system that says "here's the evidence, here's my confidence, here's what to check first" wins where a confident classifier loses.
- **[ADD] A one-tap "is this working?" test.** Play a recorded cry from the parent phone and watch the Guardian detect it live. Judges love a demo that proves itself in five seconds, and it doubles as your onboarding step.

---

# Part 6 — Tech stack

## On React Native, honestly

**React Native is the wrong choice for this specific hackathon**, and it's a close call that turns on one fact: every device in the room is Android.

**Why RN looks right** — cross-platform, fast UI iteration, you likely already know it, and `react-native-fast-tflite` is real and maintained (v2.0.0, GPU delegates, Nitro modules). For a normal product, this is a sound default.

**Why it loses here** — the loaner devices are iQOO Android phones. Cross-platform buys you nothing this weekend. Meanwhile 25% of your score is device data on camera, voice, NPU and Office Kit use — precisely the surfaces where RN adds a bridge you have to build and debug under a 30-hour clock.

**My recommendation: native Android — Kotlin + Jetpack Compose.** The NPU path (LiteRT with the Qualcomm AI Engine Direct accelerator), the on-device LLM path (MediaPipe LLM Inference), CameraX and `AudioRecord` are all first-class Kotlin APIs with working sample apps you can crib from at 2am. Every hour you don't spend writing a JSI bridge is an hour on the demo.

**If you want to keep RN anyway** — and there's a legitimate reason to, if this becomes a real product with an iOS future — do it as a hybrid: RN for the parent-phone UI, and a **Kotlin native module for the entire Guardian pipeline** (capture → NPU inference → event fusion). Do not move audio frames across the bridge; keep the pipeline entirely native and pass events up as JSON. Budget three extra hours for setup and decide before the clock starts, not at hour 12.

| Layer | Pick | Why this one |
|---|---|---|
| App shell | **Kotlin + Jetpack Compose** | Single platform, fastest path to the hardware, no bridge tax. |
| Audio capture | `AudioRecord` @ 16 kHz mono → ring buffer | Matches your existing pipeline's sample rate exactly. 960 ms hops with 50% overlap. |
| Cry detection | **YAMNet** (TFLite) baseline; fine-tuned MobileNetV3 on log-mel if time allows | YAMNet's AudioSet classes already include "Baby cry, infant cry" — working detector in hour one. |
| NPU execution | **LiteRT + Qualcomm AI Engine Direct (QNN) accelerator** | Replaces the old TFLite QNN delegate. ~90 LiteRT ops supported; on Snapdragon 8 Elite Gen 5, NPU inference runs up to 100× CPU, 50+ models under 5 ms. **This is your 15% HackTracker score, made visible.** |
| Vision / safety | **MediaPipe Pose Landmarker** + your own rule layer | Keypoints at 5–10 fps are plenty. Rules over landmarks (torso orientation, face-region occlusion, distance to a user-drawn crib boundary) are debuggable at 3am; a trained end-to-end model is not. |
| On-device LLM | **Gemma 3n E2B** via MediaPipe LLM Inference API | Multimodal — text, image and **audio up to 30s**. MatFormer architecture trades size for latency. Ships as an Android API, no server. |
| Device ↔ device | **WebRTC over LAN**, or Wi-Fi Direct for the airplane-mode demo | Dormi already proves the offline path works. LAN-only is also your privacy story. |
| Storage | **Room** (SQLite), local only | No backend to build, deploy or debug. "No account required" is a feature. |
| Model prep | **Qualcomm AI Hub** for conversion + on-device benchmarking | Real latency numbers per model on the actual chip — put that table on a slide. |
| Office Kit | Laptop as live event dashboard + model conversion box | 10% of the score is read from device data, so use the bridge for something real. |

## What to do with your existing 461-feature Random Forest

**Don't ship it as the classifier.** 461 librosa features per window is heavy for real-time on a phone, and the model behind it was evaluated with leakage.

**Do reuse it two ways.** First, as a **cry-character descriptor**: pitch, energy, rhythm and spectral shape are genuinely informative, and feeding those descriptors to Gemma 3n as evidence ("high-pitched, low pause ratio, rising intensity over 40s") is more honest and more useful than a hard label. Second, as the **research slide** — the LIME plots and the leakage finding are your credibility story. You built it, you measured it properly, you changed the design because of what you found. That's the narrative arc a jury remembers.

---

# Part 7 — Thirty hours, mapped to the rubric

Clock starts Saturday 10:00, hacking from 11:00. Assumes a team of three; if solo, cut the vision lane to rollover-only and keep everything else.

| When | What |
|---|---|
| **Before the event** | **Everything that isn't code.** Revoke that Gemini key. Build the log/onboarding screens and the micro-lesson content as static assets. Pre-download Gemma 3n and YAMNet. Rehearse the Office Kit pairing. Pre-cutting this work is legal, decisive, and most teams don't do it. |
| **H0 – H3** | **Skeleton and the spine.** Two-phone pairing over LAN, camera preview streaming, `AudioRecord` loop running. Ship nothing clever yet — just prove the two devices talk. |
| **H3 – H7** | **Audio lane.** YAMNet on the NPU via the QNN accelerator, cry detection firing an event. **Log the measured latency** — you'll quote it on stage. First checkpoint: the phone beeps when a recorded cry plays. |
| **H7 – H13** | **Vision lane.** Pose Landmarker running, crib boundary drawn by the user, three rules live: rollover, face-covered, edge-approach. Test with a doll and a towel — bring both. |
| **H13 – H16** | **Sleep, in shifts.** Non-negotiable. Teams that skip this demo badly at hour 29. |
| **H16 – H21** | **Gemma 3n reasoning layer.** Prompt takes acoustic evidence + context log, returns guidance with an explicit confidence statement. Wire the multilingual answer path — Hindi/Tamil/Kannada in a demo lands hard with an Indian jury. |
| **H21 – H25** | **The learning loop and polish.** Event-triggered micro-lessons, the event timeline, the one-tap self-test. Mirror the timeline to the laptop over Office Kit. |
| **H25 – H28** | **Demo hardening.** Airplane-mode run-through. Record a backup video of every feature working — then demo live anyway, with the video as insurance. |
| **H28 – H30** | **Pitch.** Rehearse the 3–5 minutes out loud, three times, timed. |

## Scoring the plan against their rubric

| Criterion | Weight | How this build scores |
|---|---:|---|
| End product quality | 30% | Works offline, end to end, with a self-test anyone can run. Two P0 features fully finished beats five half-features. |
| Novelty and impact | 20% | Hardware-grade safety AI with no hardware; the honest-evaluation story; India-first framing on a market growing 8.9% a year. |
| HackTracker — creative phone use | 15% | Camera, mic and NPU inference are the product, not a garnish. Two phones in different roles is unusually strong device usage. |
| Technical depth | 15% | QNN delegate, quantized models, measured per-model latency, event-fusion severity tiers, LAN transport. Show the AI Hub benchmark table. |
| HackTracker — Office Kit | 10% | Laptop as live dashboard and conversion box, used throughout — not switched on for five minutes at the end. |
| Demo and presentation | 10% | Open with the doll rolling over and the phone beeping. Then the honesty slide. Then airplane mode. Close on the market gap. |

---

# Part 8 — Positioning risks

**Read before writing a single slide.**

- **[REGULATORY] Never claim SIDS prevention, medical monitoring, or diagnosis.** The FDA sent Owlet a warning letter in October 2021 over exactly this — Owlet had a disclaimer, and the FDA still held that alerting on desaturation made it an unapproved medical device. Owlet pulled the product and only returned two years later with de novo clearance. Say "awareness aid, not a medical device," say it on screen, and say it in the pitch. It reads as maturity, not weakness.
- **[CREDIBILITY] Don't let a demo imply certainty you don't have.** If the UI ever prints "Your baby is hungry" as a fact, one informed judge sinks you. "Most likely hunger — 3h since the last feed, and the cry is rising" is defensible, more useful, and nobody else will be doing it.
- **[SCOPE] The camera lane is where teams die.** If Pose Landmarker isn't stable by hour 13, ship rollover-only and spend the recovered time on the reasoning layer and the demo. A polished two-feature product outscores a broken four-feature one under this rubric, and they say so explicitly.

## Takeaways

1. You already ran the honest experiment — it's slide 16 of your own deck, at 0.783 accuracy / 0.687 F1. Publish that next to the 98.76%, report the 83.6% baseline, and the 20-point gap becomes a contribution instead of an embarrassment.
2. Build on cry *detection* (0.988 AUC, deployable) and camera safety. Treat cry reason as a hypothesis your on-device LLM reasons about with logged context — never as a label.
3. The market gap is precise: real safety AI exists only in $199–$399 hardware, and free two-phone monitor apps have no intelligence at all. Occupy the middle, on-device, in India.
4. Kotlin over React Native for this weekend — every device is Android and a quarter of your score comes from hardware surfaces RN puts a bridge in front of.

---

## Sources

**Read in full** (marked ●). The rest are from search results and abstracts — verify any number you plan to put on a slide.

- ● **Batch B Group 1** — "Speech Processing" project deck, 30 slides (`SPEECH PROCESSING.pptx`)
- ● **Vishal A S, Sarvesh S, Durai Singh, Kishor S, Jyothish Lal G** — "Why is My Baby Crying? Explainable Emotion Recognition with Domain-Aware Ensemble Models," Amrita School of Artificial Intelligence (`speech.pdf`, 8pp)
- ● [Self-Supervised Pretext Tasks for Infant Cry Analysis: A Controlled Comparison and a Cautionary Result on Donateacry](https://arxiv.org/html/2608.30456) — the leakage and chance-level finding
- ● [The application of machine learning for infant cries classification and pathological cries detection: a systematic review](https://pmc.ncbi.nlm.nih.gov/articles/PMC12804663/)
- [A review of infant cry analysis and classification](https://link.springer.com/article/10.1186/s13636-021-00197-5) — J. Audio, Speech & Music Processing
- [Infant cry classification via Discrete Stockwell Transform (IncryNet)](https://www.sciencedirect.com/science/article/abs/pii/S0952197625020160) — Eng. Applications of AI, 2025
- [Evaluating CNNs and Vision Transformers for Baby Cry Sound Analysis](https://doi.org/10.3390/fi16070242) — Future Internet, 2024
- [Privacy-Enhancing Infant Cry Classification with Federated Transformers](https://arxiv.org/pdf/2512.13880)
- [ICSD: An Open-source Dataset for Infant Cry and Snoring Detection](https://arxiv.org/pdf/2408.10561)
- [ChatterBaby (UCLA Health)](https://www.uclahealth.org/news/article/chatterbaby-an-app-that-helps-parents-know-when-and-why-their-baby-is-crying-used-in-new-research) · [underlying Pediatric Research paper](https://www.nature.com/articles/s41390-019-0592-4) · [Sherwood News: do baby cry translators work?](https://sherwood.news/tech/do-baby-cry-translators-work/)
- [FDA warning letter to Owlet Baby Care (Oct 2021)](https://www.fda.gov/inspections-compliance-enforcement-and-criminal-investigations/warning-letters/owlet-baby-care-inc-616354-10052021)
- [LiteRT on Qualcomm NPUs](https://ai.google.dev/edge/litert/android/npu/qualcomm) · [MediaPipe LLM Inference for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) · [Gemma 3n developer guide](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/) · [Qualcomm AI Hub Models](https://github.com/qualcomm/ai-hub-models)
- [India smart baby monitor market outlook](https://www.grandviewresearch.com/horizon/outlook/smart-baby-monitor-market/india) · [Bibino](https://bibinoapp.com/en/android-baby-monitor-app/) · [Dormi](https://play.google.com/store/apps/details?id=com.sleekbit.dormi&hl=en)
