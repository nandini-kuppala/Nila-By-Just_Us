"""How often is the named interaction type right, given the model's own
confidence? The app shows the type only above a threshold, so this is the
number that decides where the threshold goes."""
import csv, os
import numpy as np
from ddi_infer import DdiPredictor, HERE
d = DdiPredictor()

rows = {"S0": [], "S1": [], "S2": []}
with open(os.path.join(HERE, "input/test.csv")) as fh:
    r = csv.reader(fh); h = next(r)
    i1, i2, isp = h.index("drug1_id"), h.index("drug2_id"), h.index("test_split")
    for row in r:
        rows[row[isp]].append((int(row[i1]), int(row[i2])))
truth = {s: np.array([int(l.split()[2]) for l in open(os.path.join(HERE, f"ref/test_{s}.txt"))])
         for s in rows}

for split in ("S0", "S1", "S2"):
    pairs = rows[split]
    lg = np.concatenate([d.logits(pairs[s:s+2048]) for s in range(0, len(pairs), 2048)])
    e = np.exp(lg - lg.max(1, keepdims=True)); p = e / e.sum(1, keepdims=True)
    conf, pred = p.max(1), p.argmax(1)
    ok = pred == truth[split]
    print(f"\n{split}  n={len(pairs)}  overall top-1 {ok.mean():.4f}")
    for t in (0.0, 0.5, 0.6, 0.7, 0.8, 0.9):
        m = conf >= t
        if m.sum() == 0: continue
        print(f"   conf >= {t:.1f}: covers {100*m.mean():5.1f}% of pairs, "
              f"correct {ok[m].mean():.4f}")
