"""Export the trained checkpoint to ONNX and prove the export is faithful."""
import os
import numpy as np, torch, os
from ddi_infer import DdiPredictor, HERE

d = DdiPredictor()
model = d.model

class Wrapped(torch.nn.Module):
    """One (1, ...) pair in, 86 logits out. Batch is fixed at 1: the phone asks
    about one drug pair at a time, and a static shape is what lets the mobile
    runtimes accept the graph at all."""
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x_raw, x_w, blocks, stats, d1, d2):
        return self.m(x_raw, x_w, blocks, stats, d1, d2)

w = Wrapped(model).eval()
pairs = [(334, 219)]
raw, xw, bl, st, i1, i2 = d.features(pairs)
args = tuple(torch.from_numpy(a) for a in (raw, xw, bl, st, i1, i2))

out = os.path.join(HERE, "ddi_model.onnx")
torch.onnx.export(
    w, args, out,
    input_names=["x_raw", "x_weighted", "blocks", "stats", "drug1_id", "drug2_id"],
    output_names=["logits"],
    dynamic_axes=None,            # static batch of 1
    opset_version=17,
    do_constant_folding=True,
    dynamo=False,
)
print("exported", out, os.path.getsize(out) / 1e6, "MB")

import onnxruntime as ort
sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
rng = np.random.RandomState(1)
worst = 0.0
mismatch = 0
for _ in range(200):
    a, b = rng.randint(0, 1710, 2)
    f = d.features([(int(a), int(b))])
    ref = d.logits([(int(a), int(b))])[0]
    got = sess.run(None, dict(zip(
        ["x_raw", "x_weighted", "blocks", "stats", "drug1_id", "drug2_id"], f)))[0][0]
    worst = max(worst, float(np.abs(ref - got).max()))
    if int(ref.argmax()) != int(got.argmax()):
        mismatch += 1
print(f"max |torch - onnx| logit difference over 200 pairs: {worst:.3e}")
print(f"argmax disagreements: {mismatch}/200")
