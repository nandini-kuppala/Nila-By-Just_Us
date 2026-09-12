"""Turn the verified tables into the files the app ships.

  ddi_drugs.bin   per drug: the Morgan count vector (sparse -- only ~46 of
                  1024 slots are non-zero), and whether the model was actually
                  trained on that drug
  ddi_graph.bin   which ordered pairs DrugBank records an interaction for. The
                  classifier has 86 classes and no "they do not interact" one,
                  so it must never be the thing that decides whether to speak.
  ddi_vocab.json  drug id -> name
  ddi_types.json  the 86 interaction descriptions
"""
import glob, json, os, struct
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "assets")
os.makedirs(OUT, exist_ok=True)
N, FP = 1710, 1024

feats = np.load(os.path.join(HERE, "bench_morgan.npy")).astype(np.uint8)
idf = np.load(os.path.join(HERE, "idf.npy")).astype(np.float32)
trained = np.load(os.path.join(HERE, "train_seen.npy"))
assert feats.shape == (N, FP) and idf.shape == (FP,)

with open(f"{OUT}/ddi_drugs.bin", "wb") as f:
    f.write(b"NDDI")
    f.write(struct.pack("<III", 2, N, FP))
    f.write(idf.tobytes())
    for i in range(N):
        nz = np.nonzero(feats[i])[0]
        f.write(struct.pack("<BH", 1 if trained[i] else 0, len(nz)))
        f.write(nz.astype("<u2").tobytes())
        f.write(feats[i][nz].astype(np.uint8).tobytes())
print("ddi_drugs.bin", round(os.path.getsize(f"{OUT}/ddi_drugs.bin")/1e3), "KB",
      "| trained drugs", int(trained.sum()), "of", N)

edges = set()
for path in glob.glob(os.path.join(HERE, "ref/*.txt")):
    for line in open(path):
        h, t, _ = map(int, line.split())
        edges.add((h, t))
bits = bytearray((N * N + 7) // 8)
for a, b in edges:
    k = a * N + b
    bits[k >> 3] |= 1 << (k & 7)
with open(f"{OUT}/ddi_graph.bin", "wb") as f:
    f.write(b"NDDG")
    f.write(struct.pack("<II", N, len(edges)))
    f.write(bytes(bits))
print("ddi_graph.bin", round(os.path.getsize(f"{OUT}/ddi_graph.bin")/1e3), "KB",
      "| ordered edges", len(edges))

desc = json.load(open(os.path.join(HERE, "label_descriptions.json")))
json.dump({"types": [desc[str(i)] for i in range(86)]},
          open(f"{OUT}/ddi_types.json", "w"), separators=(",", ":"))

gpt = json.load(open(os.path.join(HERE, "ref/drug_DDI_GPT.json")))
json.dump({"names": [gpt[str(i)]["name"] for i in range(N)]},
          open(f"{OUT}/ddi_vocab.json", "w"), separators=(",", ":"))
for n in ("ddi_types.json", "ddi_vocab.json"):
    print(n, round(os.path.getsize(f"{OUT}/{n}")/1e3), "KB")
