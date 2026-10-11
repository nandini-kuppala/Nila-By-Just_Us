"""Run the AI Hub OWL-ViT ONNX export on a photo with a few queries (desktop, CPU)."""
import sys, time, numpy as np, onnxruntime as ort
from PIL import Image, ImageOps, ImageDraw
from tokenizers import Tokenizer

ROOT = "/Users/dev/Desktop/Nila-By-Just_Us/babyproof-lab/work"
tok = Tokenizer.from_file(f"{ROOT}/tokenizer/tokenizer.json")
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)

def ids(q):
    e = tok.encode(q).ids[:16]
    a = np.zeros((1, 16), np.int32); m = np.zeros((1, 16), np.int32)
    a[0, :len(e)] = e; m[0, :len(e)] = 1
    return a, m

def prep(img):
    w, h = img.size; s = max(w, h)
    sq = Image.new("RGB", (s, s), (124, 116, 104)); sq.paste(img, (0, 0))
    x = np.asarray(sq.resize((768, 768), Image.BICUBIC), np.float32) / 255.0
    x = (x - MEAN) / STD
    return x.transpose(2, 0, 1)[None], s

if __name__ == "__main__":
    path = sys.argv[1]; queries = sys.argv[2:]
    sess = ort.InferenceSession(f"{ROOT}/models/float/owl_vit-onnx-float/owl_vit.onnx", providers=["CPUExecutionProvider"])
    img = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    px, s = prep(img)
    draw = ImageDraw.Draw(img)
    for q in queries:
        a, m = ids(q)
        t = time.time()
        boxes, scores, labels = sess.run(None, {"pixel_values": px, "input_ids": a, "attention_mask": m})
        dt = time.time() - t
        order = np.argsort(-scores[0])[:3]
        print(f"{q!r} ({dt*1000:.0f} ms): top scores {scores[0][order].round(3).tolist()} labels {labels[0][order].tolist()}")
        print("   boxes", boxes[0][order].round(3).tolist())
        b = boxes[0][order[0]]
        print("   box range", float(boxes.min()), float(boxes.max()))
