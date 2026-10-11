"""Cut AI Hub's OWL-ViT export into its image half and its text half.

The export takes one image and one query per run, so 32 queries over 5 tiles were
160 runs of the vision encoder per photo -- and the phone overheated. The two halves
only meet at the very end:

    logit = (image_class_embed . query_embed + shift) * scale     (per patch)

so the image half (pixels -> boxes, class embeds, shift, scale) runs on the phone once
per tile, and the text half runs here once per query (tools/build_hazards.py stores the
32 query embeddings in hazards.json). Same scores as the fused model to 1e-7.

    python3 tools/split_owl.py      # writes work/models/split/owl_{image,text}.onnx
"""
import os, onnx
from onnx.utils import Extractor

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "work/models")
SRC = os.path.join(ROOT, "float/owl_vit-onnx-float/owl_vit.onnx")
OUT = os.path.join(ROOT, "split")

def main():
    e = Extractor(onnx.load(SRC))
    # Node names from the v0.64.0 export: div_2 = normalised class embeds [1,576,512],
    # linear_147 = shift [1,576,1], add_77 = scale (elu + 1) [1,576,1], div_3 = query embed.
    halves = {
        "owl_image": e.extract_model(["pixel_values"], ["boxes", "div_2", "linear_147", "add_77"]),
        "owl_text": e.extract_model(["input_ids", "attention_mask"], ["div_3"]),
    }
    os.makedirs(OUT, exist_ok=True)
    for name, m in halves.items():
        onnx.save(m, os.path.join(OUT, f"{name}.onnx"), save_as_external_data=True,
                  all_tensors_to_one_file=True, location=f"{name}.data")
        print(name, len(m.graph.node), "nodes")

if __name__ == "__main__":
    main()
