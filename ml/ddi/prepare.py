"""Recover everything the app needs from the DDI-Bench release, and check it.

Run this before [build_assets.py]. It produces, in this directory:

    drug_counts.npy     1710 x 1024 Morgan count vectors, read out of the
                        competition CSVs
    bench_morgan.npy    the same thing taken from DDI-Bench's own feature file,
                        which also covers the 183 drugs no CSV row mentions
    idf.npy             the inverse document frequency vector the training run
                        fitted, reproduced from the seed
    train_seen.npy      which drugs the model was actually trained on
    label_descriptions.json   what each of the 86 classes means, in words

Nothing here trains anything. Every step is a lookup or a check.

Inputs:
    ./input/train.csv, ./input/test.csv   from the competition bundle
    ./ref/*                               fetched by fetch_reference.sh
"""
import csv, glob, json, os, sys
import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
IN = os.path.join(HERE, "input")
REF = os.path.join(HERE, "ref")
SEED, N_CLASSES, FP, N_DRUGS = 2024, 86, 1024, 1710
VAL_FRACTION = 0.10


def out(name):
    return os.path.join(HERE, name)


# ---------------------------------------------------------------- 1. features
#
# The task description calls these "binary Morgan fingerprints"; they are not.
# The columns hold Morgan *counts* (0 to 70). Reading them as bits gets you a
# fingerprint that is a subset of the real one and a model that quietly scores
# worse, so they are parsed as floats and then checked against the benchmark's
# own feature file below.
def read_drug_counts():
    fps = {}

    def scan(path):
        with open(path, newline="") as fh:
            r = csv.reader(fh)
            header = next(r)
            i1, i2 = header.index("drug1_id"), header.index("drug2_id")
            i0 = header.index("f0")
            for row in r:
                d1, d2 = int(row[i1]), int(row[i2])
                if d1 not in fps:
                    fps[d1] = np.array([float(row[i0 + j]) for j in range(FP)],
                                       dtype=np.float32)
                if d2 not in fps:
                    fps[d2] = np.array([float(row[i0 + FP + j]) for j in range(FP)],
                                       dtype=np.float32)

    scan(os.path.join(IN, "train.csv"))
    scan(os.path.join(IN, "test.csv"))
    mat = np.zeros((N_DRUGS, FP), dtype=np.float32)
    seen = np.zeros(N_DRUGS, dtype=bool)
    for i, v in fps.items():
        mat[i] = v
        seen[i] = True
    return mat, seen


# ------------------------------------------------------- 2. the benchmark copy
def read_benchmark_features():
    import pickle
    d = pickle.load(open(os.path.join(REF, "DB_molecular_feats.pkl"), "rb"))
    arr = np.stack([np.asarray(x, dtype=np.uint8) for x in d["Morgan_Features"]])
    nid = np.asarray(d["Node ID"])
    bench = np.zeros((N_DRUGS, arr.shape[1]), dtype=np.uint8)
    for row, n in enumerate(nid):
        bench[int(n)] = arr[row]
    return bench[:, :FP]


# ------------------------------------------- 3. the training fold, and its IDF
#
# Reproduced rather than shipped: the split is a deterministic function of the
# seed, so re-deriving it is also a check that the checkpoint on disk belongs to
# this data. [evaluate.py] confirms the score it reaches.
def fit_idf_and_split():
    fp_cols = [f"f{i}" for i in range(2 * FP)]
    dtypes = {c: np.float32 for c in fp_cols}
    dtypes.update({"id": np.int64, "drug1_id": np.int32, "drug2_id": np.int32})
    df = pd.read_csv(os.path.join(IN, "train.csv"), dtype=dtypes)

    y = df["label"].to_numpy(dtype=np.int64)
    X = df[fp_cols].to_numpy(dtype=np.float32)
    d1 = df["drug1_id"].to_numpy(dtype=np.int32)
    d2 = df["drug2_id"].to_numpy(dtype=np.int32)
    del df

    rng = np.random.RandomState(SEED)
    idx = np.arange(len(X))
    tr_parts, va_parts = [], []
    for c in range(N_CLASSES):
        c_idx = idx[y == c]
        rng.shuffle(c_idx)
        n_c = len(c_idx)
        if n_c >= 2:
            n_val = min(max(1, int(round(n_c * VAL_FRACTION))), n_c - 1)
            va_parts.append(c_idx[:n_val])
            tr_parts.append(c_idx[n_val:])
        else:
            tr_parts.append(c_idx)
    idx_tr = np.sort(np.concatenate(tr_parts))

    X_tr, d1_tr, d2_tr = X[idx_tr], d1[idx_tr], d2[idx_tr]
    u1 = pd.DataFrame({"drug": d1_tr}).drop_duplicates("drug")
    u2 = pd.DataFrame({"drug": d2_tr}).drop_duplicates("drug")
    fps = np.vstack([
        X_tr[:, :FP][u1.index.to_numpy()],
        X_tr[:, FP:][u2.index.to_numpy()],
    ]).astype(np.uint8)
    fps = np.unique(fps, axis=0)
    n = fps.shape[0]
    idf = (np.log((1.0 + n) / (1.0 + fps.sum(axis=0).astype(np.float64))) + 1.0)
    return idf.astype(np.float32), n


def main():
    print("reading per-drug features out of the CSVs ...", flush=True)
    counts, seen = read_drug_counts()
    print(f"  {int(seen.sum())} of {N_DRUGS} drugs appear in train or test")

    bench = read_benchmark_features().astype(np.float32)
    agree = int((bench[seen] == counts[seen]).all(1).sum())
    print(f"  agreement with DDI-Bench's own feature file: {agree}/{int(seen.sum())}")
    assert agree == int(seen.sum()), "the CSV features are not the benchmark's"
    # Every drug the CSVs mention matches, so the benchmark file is the same
    # table and covers the rest too. That is what makes the other 183 usable.
    np.save(out("drug_counts.npy"), counts)
    np.save(out("bench_morgan.npy"), bench.astype(np.uint8))

    print("fitting the training fold's IDF ...", flush=True)
    idf, n_unique = fit_idf_and_split()
    np.save(out("idf.npy"), idf)
    print(f"  {n_unique} unique training fingerprints, "
          f"idf in [{idf.min():.3f}, {idf.max():.3f}]")

    trained = np.zeros(N_DRUGS, dtype=bool)
    for line in open(os.path.join(REF, "train.txt")):
        h, t, _ = map(int, line.split())
        trained[h] = trained[t] = True
    np.save(out("train_seen.npy"), trained)
    print(f"  {int(trained.sum())} drugs appear in training")

    # The 86 class indices are the relation ids, which the competition
    # anonymised to "DDI_type_N". Confirmed row by row: every one of the 99,283
    # training rows has the same drug pair as the corresponding line of
    # DDI-Bench's train.txt, and the same relation id as its label.
    ref = [tuple(map(int, l.split())) for l in open(os.path.join(REF, "train.txt"))]
    with open(os.path.join(IN, "train.csv")) as fh:
        r = csv.reader(fh)
        h = next(r)
        i1, i2, il = h.index("drug1_id"), h.index("drug2_id"), h.index("label")
        rows = [(int(x[i1]), int(x[i2]), int(x[il])) for x in r]
    assert len(rows) == len(ref), "row counts differ"
    bad = sum(1 for i in range(len(rows))
              if rows[i][:2] != ref[i][:2] or rows[i][2] != ref[i][2])
    print(f"  label/relation alignment: {len(rows) - bad}/{len(rows)} rows agree")
    assert bad == 0, "labels are not DDI-Bench relation ids"

    relations = json.load(open(os.path.join(REF, "relation2id.json")))
    desc = {int(k): v for k, v in relations.items()}
    json.dump({str(i): desc[i] for i in range(N_CLASSES)},
              open(out("label_descriptions.json"), "w"), indent=1)
    print(f"  wrote descriptions for {N_CLASSES} interaction types")
    print("\nready -- run export_onnx.py then build_assets.py")


if __name__ == "__main__":
    main()
