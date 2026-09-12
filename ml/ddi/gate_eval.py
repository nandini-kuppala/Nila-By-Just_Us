"""Can the classifier tell an interacting pair from a non-interacting one?

It has 86 classes and no "these do not interact" class, so anything the app
asks it about will come back as *some* interaction. This measures whether the
softmax peak separates real DrugBank edges from pairs the graph does not
contain -- the gate the app would need before ever showing a prediction.
"""
import os
import numpy as np, json, glob
from ddi_infer import DdiPredictor, HERE

d = DdiPredictor()

edges = set()
for f in glob.glob(os.path.join(HERE, "ref/*.txt")):
    for line in open(f):
        h, t, r = map(int, line.split())
        edges.add((h, t))
print("ordered edges in the DrugBank DDI graph:", len(edges))
undirected = {tuple(sorted(e)) for e in edges}
print("unordered pairs:", len(undirected))

rng = np.random.RandomState(0)
pos = [e for e in list(edges)[:4000]]
# negatives: unordered pair absent in BOTH directions
neg = []
while len(neg) < 4000:
    a, b = rng.randint(0, 1710, 2)
    if a == b: continue
    if (a, b) in edges or (b, a) in edges: continue
    if not (d.feats[a].any() and d.feats[b].any()): continue
    neg.append((int(a), int(b)))

def maxprob(pairs):
    out = []
    for s in range(0, len(pairs), 512):
        lg = d.logits(pairs[s:s+512])
        e = np.exp(lg - lg.max(1, keepdims=True)); p = e / e.sum(1, keepdims=True)
        out.append(p.max(1))
    return np.concatenate(out)

mp_pos, mp_neg = maxprob(pos), maxprob(neg)
print(f"\nmax softmax  edges    : mean {mp_pos.mean():.3f}  median {np.median(mp_pos):.3f}")
print(f"max softmax  non-edges: mean {mp_neg.mean():.3f}  median {np.median(mp_neg):.3f}")
ys = np.r_[np.ones_like(mp_pos), np.zeros_like(mp_neg)]
ss = np.r_[mp_pos, mp_neg]
from sklearn.metrics import roc_auc_score
print(f"AUC separating edge from non-edge: {roc_auc_score(ys, ss):.4f}")
for t in (0.5, 0.8, 0.9, 0.95, 0.99):
    print(f"  threshold {t:.2f}: keeps {100*(mp_pos>t).mean():5.1f}% of real edges, "
          f"{100*(mp_neg>t).mean():5.1f}% of non-edges")
