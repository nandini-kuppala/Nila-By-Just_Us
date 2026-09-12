"""Held-out test performance, per split, with the labels recovered from
DDI-Bench. The benchmark's own test set -- not the internal validation fold."""
import csv, os
import numpy as np
from collections import defaultdict
from sklearn.metrics import f1_score
from ddi_infer import DdiPredictor, HERE

d = DdiPredictor()
rows = []
with open(os.path.join(HERE, "input/test.csv")) as fh:
    r = csv.reader(fh); h = next(r)
    i1, i2, isp = h.index("drug1_id"), h.index("drug2_id"), h.index("test_split")
    for row in r:
        rows.append((int(row[i1]), int(row[i2]), row[isp]))

y = {}
for split in ("S0", "S1", "S2"):
    y[split] = [int(l.split()[2]) for l in open(os.path.join(HERE, f"ref/test_{split}.txt"))]

by = defaultdict(list)
for a, b, s in rows:
    by[s].append((a, b))

APP = ["Acetaminophen","Ibuprofen","Amoxicillin","Azithromycin","Cetirizine",
       "Chlorpheniramine","Pantoprazole","Metronidazole","Ciprofloxacin","Codeine",
       "Acetylsalicylic acid","Fluconazole","Domperidone","Ondansetron","Levocetirizine",
       "Clavulanic acid","Prednisolone","Ranitidine","Diclofenac","Tramadol"]
app_ids = {d.node(a) for a in APP} - {None}

def run(pairs, bs=2048):
    out = np.empty((len(pairs), 86), dtype=np.float32)
    for s in range(0, len(pairs), bs):
        out[s:s+bs] = d.logits(pairs[s:s+bs])
    return out

for split in ("S0", "S1", "S2"):
    pairs = by[split]; truth = np.array(y[split])
    lg = run(pairs)
    pred = lg.argmax(1)
    acc = (pred == truth).mean()
    f1 = f1_score(truth, pred, labels=np.arange(86), average="macro", zero_division=0)
    print(f"{split}: n={len(pairs):6d}  top-1 {acc:.4f}  macro-F1 {f1:.4f}")
    m = np.array([a in app_ids or b in app_ids for a, b in pairs])
    if m.sum():
        print(f"     pairs touching a medicine the app knows: n={int(m.sum()):5d} "
              f"top-1 {(pred[m] == truth[m]).mean():.4f}")

# top-3 on the split the app will actually be in
pairs = by["S0"]; truth = np.array(y["S0"]); lg = run(pairs)
top3 = np.argsort(-lg, axis=1)[:, :3]
print(f"S0 top-3 accuracy: {np.mean([truth[i] in top3[i] for i in range(len(truth))]):.4f}")
