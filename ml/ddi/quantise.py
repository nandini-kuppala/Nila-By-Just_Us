r"""Measure what int8 dynamic quantisation would cost.

It halves the file and takes 2.2 points off macro-F1, concentrated in the rare
interaction types. The app ships float32; this is the measurement behind that
sentence in the docs.
"""
import os, csv, numpy as np, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from sklearn.metrics import f1_score
from ddi_infer import DdiPredictor, HERE

FP32 = os.path.join(HERE, "ddi_model.onnx")
INT8 = os.path.join(HERE, "ddi_model_int8.onnx")
quantize_dynamic(FP32, INT8,
                 weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
print("fp32", round(os.path.getsize(FP32)/1e6, 1), "MB -> int8",
      round(os.path.getsize(INT8)/1e6, 1), "MB", flush=True)

d = DdiPredictor()
rows = []
with open("test.csv") as fh:
    r = csv.reader(fh); h = next(r)
    i1, i2, isp = h.index("drug1_id"), h.index("drug2_id"), h.index("test_split")
    for row in r:
        if row[isp] == "S0":
            rows.append((int(row[i1]), int(row[i2])))
truth = np.array([int(l.split()[2]) for l in open("ref/test_S0.txt")])
names = ["x_raw","x_weighted","blocks","stats","drug1_id","drug2_id"]
feats = [d.features([p]) for p in rows]

for path in (FP32, INT8):
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    pred = np.array([s.run(None, dict(zip(names, f)))[0][0].argmax() for f in feats])
    print(f"{path}: S0 top-1 {(pred==truth).mean():.4f}  macro-F1 "
          f"{f1_score(truth, pred, labels=np.arange(86), average='macro', zero_division=0):.4f}",
          flush=True)
