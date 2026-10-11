"""Draw a lab result JSON over its photo: findings (thick) and raw candidates (thin, optional)."""
import json, sys, os
from PIL import Image, ImageDraw, ImageOps

COL = {"FIX": (200, 40, 40), "CHECK": (60, 110, 190), "PROTECTED": (40, 140, 80)}

def draw(result_path, image_path, out_path, candidates=False):
    r = json.load(open(result_path))
    im = ImageOps.exif_transpose(Image.open(image_path)).convert("RGB")
    im.thumbnail((1400, 1400))
    W, H = im.size; d = ImageDraw.Draw(im)
    if candidates:
        for c in r["candidates"]:
            l, t, rr, b = c["box"]
            d.rectangle([l * W, t * H, rr * W, b * H], outline=(250, 200, 0), width=1)
            d.text((l * W + 2, t * H + 2), f'{c["id"]} {c["score"]:.2f}', fill=(250, 200, 0))
    for i, f in enumerate(r["findings"]):
        if f["box"] is None: continue
        l, t, rr, b = f["box"]
        c = COL[f["status"]]
        d.rectangle([l * W, t * H, rr * W, b * H], outline=c, width=4)
        d.rectangle([l * W, t * H, l * W + 150, t * H + 16], fill=c)
        d.text((l * W + 3, t * H + 2), f'{i+1} {f["id"]} {f["score"]:.2f}', fill="white")
    im.save(out_path, quality=85)

if __name__ == "__main__":
    draw(sys.argv[1], sys.argv[2], sys.argv[3], len(sys.argv) > 4)
