# Drug–drug interactions

How the five files in `android/app/src/main/assets/ddi/` are produced, and what
each of the numbers quoted about them means. Nothing here trains anything —
there is one checkpoint, and everything below either converts it or measures it.

## What the app ships

| file | size | what it is |
|---|---|---|
| `ddi_model.onnx` | 19.7 MB | the classifier, exported from the PyTorch checkpoint |
| `ddi_graph.bin` | 366 KB | which ordered pairs DrugBank records an interaction for |
| `ddi_drugs.bin` | 247 KB | per drug: Morgan counts, and whether it was trained on |
| `ddi_vocab.json` | 25 KB | 1,710 drug names |
| `ddi_types.json` | 6 KB | the 86 interaction types, in DrugBank's wording |

## The model

An 86-way classifier over the DrugBank interaction ontology. Two drugs in as
Morgan fingerprints, one interaction type out. The architecture is a
substructure cross-attention network — each drug's 1,024-bit fingerprint is cut
into 64 blocks of 16, each block becomes a token, the two drugs attend to each
other in both directions, and a dense pathway carries the exact bit overlap
alongside. 4.9 M parameters.

It was trained on the **DDI-Bench** random split:

> Shen et al. (2025), *Benchmarking drug-drug interaction prediction methods: a
> perspective of distribution changes*, Bioinformatics.
> <https://github.com/LARS-research/DDI-Bench>

## Rebuilding the assets

```
./fetch_reference.sh          # drug names, relation wording, the graph itself
cp <competition bundle>/*.csv input/
python prepare.py             # features, IDF, class descriptions -- all checked
python export_onnx.py         # ONNX, with a parity check against PyTorch
python build_assets.py        # the five files above
python evaluate.py            # the numbers quoted in docs/models-and-findings.md
```

`prepare.py` asserts rather than trusts. Three things are worth knowing about,
because each was a wrong answer before it was a check:

**The features are counts, not bits.** The task description says the 2,048
columns are "0.0 or 1.0". They are Morgan *counts*, 0 to 70. Read as bits you
get a strict subset of the real fingerprint, and a model that quietly scores
worse. `prepare.py` reads them as floats and then checks all 1,527 drugs that
appear in the CSVs against DDI-Bench's own `DB_molecular_feats.pkl` — they agree
exactly, which is also what makes the benchmark's file usable for the other 183
drugs no row mentions.

**The labels are relation ids.** The competition anonymises the 86 classes to
`DDI_type_0 … DDI_type_85`, which is unusable in a sentence a person reads. They
are DDI-Bench's relation ids directly: all 99,283 training rows carry the same
drug pair *and* the same relation as the corresponding line of the benchmark's
`train.txt`. That recovers the real wording — *"#Drug1 may increase the
anticoagulant activities of #Drug2"* — and `prepare.py` re-checks the alignment
row by row every time it runs.

**The IDF vector is refitted, not shipped.** The checkpoint holds weights and
nothing else, and the first feature the model sees is the fingerprint weighted
by an inverse document frequency fitted on the training fold. That fold is a
deterministic function of seed 2024, so it is reproduced rather than stored —
and reproducing it is itself the check that the checkpoint belongs to this data.
`evaluate.py` confirms it reaches the 0.9156 validation macro-F1 the training
run recorded.

## What was measured, and what it decided

`evaluate.py`, on the benchmark's held-out test splits:

| | both drugs seen in training (S0) | one novel (S1) | both novel (S2) |
|---|---|---|---|
| n | 12,411 | 31,984 | 1,992 |
| top-1 | **96.6%** | 69.7% | 43.2% |
| macro-F1 | 0.852 | 0.585 | 0.183 |

`gate_eval.py` asks the question the app actually needs answered — *can the
model tell an interacting pair from a non-interacting one?* It cannot: **AUC
0.78**, and at a threshold keeping 90% of real DrugBank edges, 54% of pairs with
no recorded interaction pass too. So the app never asks it. Existence comes from
`ddi_graph.bin`; the model is asked only what kind.

`threshold.py` asks whether confidence can stand in for the split. It cannot
either — S1 accuracy goes from 69.7% to 76.1% at confidence ≥ 0.9, which is not
a rescue. So the app gates on the training status of the two drugs, which is
known exactly, and withholds the interaction type whenever either drug is one of
the 372 the model never saw.

## What the phone does with all this

`android/.../ddi/DrugInteractions.kt`. One ONNX session, one thread, 7.2 ms per
pair on an iQOO 15. The feature transform — IDF weighting, the 64-block popcount
sketch, four density statistics — is reimplemented there rather than shipped,
because it is cheaper to derive than to store, and `DrugInteractionDeviceTest`
pins the result against the numbers this checkpoint produces on a workstation.
