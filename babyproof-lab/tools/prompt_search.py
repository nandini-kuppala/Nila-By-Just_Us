"""Which wording should the detector use for a hazard? Desktop search on the eval set.

For each candidate phrase, the best score per photo (whole photo + four quarters) is
compared between photos labelled with that hazard and photos without it: the AUC
says how well the phrase separates them, and "gap" is the margin at the best split.
"""
import csv, os, sys, numpy as np, onnxruntime as ort
from PIL import Image, ImageOps
sys.path.insert(0, os.path.dirname(__file__))
from owl_probe import prep, ids, ROOT

EV = os.path.join(os.path.dirname(__file__), "..", "eval")
TILES = [(0, 0, 1, 1), (0, 0, .6, .6), (.4, 0, 1, .6), (0, .4, .6, 1), (.4, .4, 1, 1)]
CANDIDATES = {
    "cable": ["an electric cable on the floor", "electrical wires", "a tangle of cables", "a power cord", "an electric wire"],
    "power_strip": ["an extension board power strip", "a power strip", "a multi plug extension socket", "an extension cord with sockets"],
    "cylinder": ["a gas cylinder", "a red LPG gas cylinder", "a gas bottle"],
    "heater": ["a room heater", "a portable space heater", "an electric room heater", "a halogen heater", "an electric heater"],
    "glass_table": ["a glass top table", "a glass coffee table"],
    "table_corner": ["a sharp table corner", "a coffee table", "a table"],
    "socket": ["an electrical wall socket", "an electrical outlet", "a wall plug socket"],
    "candle": ["a lit candle or oil lamp", "an oil lamp", "a diya lamp flame"],
    "lamp": ["a floor lamp", "a standing lamp"],
}
GROUP = {"switchboard": "socket", "charger": "cable", "dresser": "bookshelf", "table_corner": "glass_table"}

def main(only=None):
    labels = list(csv.DictReader(open(os.path.join(EV, "labels.csv"))))
    sess = ort.InferenceSession(f"{ROOT}/models/float/owl_vit-onnx-float/owl_vit.onnx", providers=["CPUExecutionProvider"])
    tiles = {}
    for r in labels:
        im = ImageOps.exif_transpose(Image.open(os.path.join(EV, "images", r["image"]))).convert("RGB")
        W, H = im.size
        tiles[r["image"]] = [prep(im.crop((int(a * W), int(b * H), int(c * W), int(d * H))))[0] for a, b, c, d in TILES]
    for hz, phrases in CANDIDATES.items():
        if only and hz not in only: continue
        pos = [r["image"] for r in labels if hz in {GROUP.get(x, x) for x in r["fix"].split(";")} | {x for x in r["fix"].split(";")}]
        for ph in phrases:
            a, m = ids(f"a photo of {ph}")
            best = {img: max(float(sess.run(None, {"pixel_values": t, "input_ids": a, "attention_mask": m})[1].max()) for t in ts)
                    for img, ts in tiles.items()}
            if not pos: print(hz, "no labelled photos"); break
            p = np.array([best[i] for i in pos]); n = np.array([v for k, v in best.items() if k not in pos])
            auc = float((p[:, None] > n[None, :]).mean()) if len(p) and len(n) else float("nan")
            print(f"{hz:12s} {ph!r:42s} AUC {auc:.2f}  pos median {np.median(p):.3f} min {p.min():.3f}  neg p90 {np.percentile(n, 90):.3f} max {n.max():.3f}", flush=True)

if __name__ == "__main__":
    main(set(sys.argv[1:]) or None)
