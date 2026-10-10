#!/usr/bin/env python3
"""Give AI Hub's Whisper wrappers float inputs and outputs where they use uint16.

ONNX Runtime's Java API has no uint16 tensor type, and the precompiled_qnn_onnx
Whisper bundles take uint16 features and attention mask and return uint16
logits. Each of those already passes through a DequantizeLinear/QuantizeLinear
pair beside the QNN EPContext node, so the fix is to cut the graph at the float
end of that pair:

  input  uint16 -> DequantizeLinear -> float -> QuantizeLinear -> EPContext
  becomes  float -------------------------------> QuantizeLinear -> EPContext

  EPContext -> DequantizeLinear -> float -> QuantizeLinear -> output uint16
  becomes  EPContext -> DequantizeLinear -> output float

The quantisation parameters stay inside the graph, so the app never needs them
for these tensors. The uint8 KV caches are left alone; Java can make uint8.
The context binaries are not touched -- the wrappers refer to them by path.

usage: patch_whisper.py <bundle dir>   (writes encoder_f.onnx and decoder_f.onnx)
"""
import os, sys
import onnx
from onnx import TensorProto

def patch(path_in, path_out):
    """
    The QNN graph inside the context binary names its tensors without any
    suffix ("input_features", "logits"). ONNX Runtime matches the EPContext
    node's inputs and outputs to those names, and in the original wrapper it
    gets them because graph optimisation removes each DequantizeLinear ->
    QuantizeLinear pair as a no-op, leaving the EPContext wired to the plain
    names. A lone QuantizeLinear cannot be removed, so the cut tensors keep the
    plain names at the EPContext and the new float tensors get "_f32":

      input_features_f32 (float) -> QuantizeLinear -> input_features -> EPContext
      EPContext -> logits -> DequantizeLinear -> logits_f32 (float)
    """
    m = onnx.load(path_in, load_external_data=False)
    g = m.graph
    consumers, producers = {}, {}
    for n in g.node:
        for i in n.input: consumers.setdefault(i, []).append(n)
        for o in n.output: producers[o] = n
    remove = []
    for inp in g.input:
        if inp.type.tensor_type.elem_type != TensorProto.UINT16: continue
        name = inp.name
        dq = next(n for n in consumers[name] if n.op_type == "DequantizeLinear")
        q = next(n for n in consumers[dq.output[0]] if n.op_type == "QuantizeLinear")
        old_q_out = q.output[0]
        for n in consumers.get(old_q_out, []):
            for k, v in enumerate(n.input):
                if v == old_q_out: n.input[k] = name
        q.input[0] = name + "_f32"
        q.output[0] = name
        remove.append(dq)
        inp.name = name + "_f32"
        inp.type.tensor_type.elem_type = TensorProto.FLOAT
        print("  input ", inp.name, "float -> QuantizeLinear ->", name)
    for out in g.output:
        if out.type.tensor_type.elem_type != TensorProto.UINT16: continue
        name = out.name
        q = producers[name]
        dq = producers[q.input[0]]
        ep_out = dq.input[0]
        ep = producers[ep_out]
        for k, v in enumerate(ep.output):
            if v == ep_out: ep.output[k] = name
        dq.input[0] = name
        dq.output[0] = name + "_f32"
        remove.append(q)
        out.name = name + "_f32"
        out.type.tensor_type.elem_type = TensorProto.FLOAT
        print("  output", name, "-> DequantizeLinear ->", out.name, "float")
    for n in remove: g.node.remove(n)
    onnx.save(m, path_out)

d = sys.argv[1]
for name in ("encoder", "decoder"):
    print(name)
    patch(os.path.join(d, f"{name}.onnx"), os.path.join(d, f"{name}_f.onnx"))
