"""Inference-only DDI predictor: two drug names in, an interaction type out.

Nothing here trains. It rebuilds the exact feature transform Step 1 of
solution.py applied (IDF weighting, block popcounts, density statistics) from
the saved IDF vector, and runs the shipped checkpoint.
"""
import json, os, numpy as np, torch
from model_def import SubstructureCrossAttentionDDIClassifier

HERE = os.path.dirname(os.path.abspath(__file__))
FP_HALF, N_BLOCKS, BLOCK_SIZE, N_CLASSES = 1024, 64, 16, 86


class DdiPredictor:
    def __init__(self, ckpt=os.path.join(HERE, "best_model.pt")):
        self.feats = np.load(os.path.join(HERE, "bench_morgan.npy")).astype(np.float32)
        self.idf = np.load(os.path.join(HERE, "idf.npy")).astype(np.float32)
        self.desc = {int(k): v for k, v in
                     json.load(open(os.path.join(HERE, "label_descriptions.json"))).items()}
        gpt = json.load(open(os.path.join(HERE, "ref/drug_DDI_GPT.json")))
        self.names = {int(k): v["name"] for k, v in gpt.items()}
        self.by_name = {v.lower(): k for k, v in self.names.items()}
        ck = torch.load(ckpt, map_location="cpu", weights_only=False)
        self.model = SubstructureCrossAttentionDDIClassifier()
        self.model.load_state_dict(ck["model_state"], strict=True)
        self.model.eval()

    def node(self, name):
        return self.by_name.get(name.strip().lower())

    def features(self, pairs):
        """pairs: [(id1, id2)] -> the six tensors the model takes."""
        n = len(pairs)
        raw = np.empty((n, 2 * FP_HALF), dtype=np.float32)
        for i, (a, b) in enumerate(pairs):
            raw[i, :FP_HALF] = self.feats[a]
            raw[i, FP_HALF:] = self.feats[b]
        w = np.empty_like(raw)
        w[:, :FP_HALF] = raw[:, :FP_HALF] * self.idf
        w[:, FP_HALF:] = raw[:, FP_HALF:] * self.idf
        b1 = raw[:, :FP_HALF].reshape(n, N_BLOCKS, BLOCK_SIZE).sum(2)
        b2 = raw[:, FP_HALF:].reshape(n, N_BLOCKS, BLOCK_SIZE).sum(2)
        blocks = np.concatenate([b1, b2], 1).astype(np.float32) / BLOCK_SIZE
        p1 = raw[:, :FP_HALF].sum(1); p2 = raw[:, FP_HALF:].sum(1)
        stats = np.stack([p1 / FP_HALF, p2 / FP_HALF, np.abs(p1 - p2) / FP_HALF,
                          np.minimum(p1, p2) / np.maximum(np.maximum(p1, p2), 1.0)], 1)
        d1 = np.array([a for a, _ in pairs], dtype=np.int64)
        d2 = np.array([b for _, b in pairs], dtype=np.int64)
        return raw, w, blocks, stats.astype(np.float32), d1, d2

    @torch.no_grad()
    def logits(self, pairs):
        t = [torch.from_numpy(x) for x in self.features(pairs)]
        return self.model(*t).numpy()

    def predict(self, name1, name2, topk=3):
        a, b = self.node(name1), self.node(name2)
        if a is None or b is None:
            return None
        lg = self.logits([(a, b)])[0]
        p = np.exp(lg - lg.max()); p /= p.sum()
        order = np.argsort(-p)[:topk]
        return [{"label": int(c), "prob": float(p[c]),
                 "text": self.desc[int(c)].replace("#Drug1", self.names[a])
                                          .replace("#Drug2", self.names[b])}
                for c in order]


if __name__ == "__main__":
    import sys
    d = DdiPredictor()
    pairs = [("Ibuprofen", "Warfarin"), ("Warfarin", "Ibuprofen"),
             ("Acetaminophen", "Warfarin"), ("Ciprofloxacin", "Warfarin"),
             ("Fluconazole", "Warfarin"), ("Tramadol", "Sertraline"),
             ("Acetaminophen", "Ibuprofen"), ("Prednisolone", "Ibuprofen"),
             ("Clarithromycin", "Amlodipine"), ("Metronidazole", "Warfarin")]
    for x, y in pairs:
        out = d.predict(x, y)
        print(f"\n{x} + {y}")
        if out is None:
            print("   (one of these is not in the vocabulary)")
            continue
        for r in out:
            print(f"   {r['prob']:.3f}  [{r['label']:2d}] {r['text']}")
