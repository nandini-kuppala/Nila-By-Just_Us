"""Build the demo photo sets in skin-ui/src/main/assets/demo/.

Usage: python tools/make_demo_assets.py <commons download dir>

Two kinds of picture:
  * Synthetic, made here: skin on six tones with five kinds of mark, nappies in
    the stool card colours under daylight and a warm bulb, and eye whites with
    a white paper reference. Real infant photos with known answers don't exist
    in the open, and these let the demo show the maths doing something checkable.
  * Real, openly licensed photos from Wikimedia Commons (meals, flash eyes, a
    few skin marks), credited in demo/CREDITS.md and on screen.
"""
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "skin-ui/src/main/assets/demo"

TONES = {"I": (70, 9, 15), "II": (64, 11, 17), "III": (57, 12, 19), "IV": (48, 12, 20), "V": (39, 11, 17), "VI": (30, 8, 11)}


def lab_to_rgb(L, a, b):
    fy = (L + 16) / 116
    fx = fy + a / 500
    fz = fy - b / 200
    def finv(t):
        return np.where(t ** 3 > 216 / 24389, t ** 3, (116 * t - 16) / (24389 / 27))
    X = finv(fx) * 0.95047; Y = finv(fy); Z = finv(fz) * 1.08883
    r = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z
    g = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z
    bb = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z
    rgb = np.stack([r, g, bb], -1)
    return rgb


def to_srgb(lin, gain=(1, 1, 1)):
    lin = np.clip(lin * np.array(gain), 0, 1)
    c = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * lin ** (1 / 2.4) - 0.055)
    return (np.clip(c, 0, 1) * 255 + 0.5).astype(np.uint8)


def smooth_noise(rng, size, scale):
    small = rng.standard_normal((max(2, size // scale), max(2, size // scale))).astype(np.float32)
    im = Image.fromarray(((small - small.min()) / (np.ptp(small) + 1e-9) * 255).astype(np.uint8)).resize((size, size), Image.BICUBIC)
    v = np.asarray(im, np.float32) / 255.0
    return (v - v.mean()) / (v.std() + 1e-6)


def blob(rng, size, cx, cy, r, rough=0.35):
    yy, xx = np.mgrid[0:size, 0:size]
    d = np.sqrt((xx - cx * size) ** 2 + (yy - cy * size) ** 2) / (r * size)
    n = smooth_noise(rng, size, 40)
    m = np.clip((1.0 + rough * n - d) * 4, 0, 1)
    return np.asarray(Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.2)), np.float32) / 255


def dots(rng, size, n, cx, cy, spread, rmin, rmax):
    yy, xx = np.mgrid[0:size, 0:size]
    m = np.zeros((size, size), np.float32); hi = np.zeros_like(m)
    for _ in range(n):
        x = (cx + (rng.random() - 0.5) * spread) * size; y = (cy + (rng.random() - 0.5) * spread) * size
        r = rmin + rng.random() * (rmax - rmin)
        d = np.sqrt((xx - x) ** 2 + (yy - y) ** 2)
        m = np.maximum(m, np.clip((r - d) / 2.5, 0, 1))
        hi = np.maximum(hi, np.clip((r * 0.35 - np.sqrt((xx - x + r * 0.3) ** 2 + (yy - y + r * 0.3) ** 2)) / 1.5, 0, 1))
    return m, hi


def skin_photo(tone, kind, seed, size=900, gain=(1, 1, 1)):
    rng = np.random.default_rng(seed)
    L0, a0, b0 = TONES[tone]
    L = L0 + 1.6 * smooth_noise(rng, size, 60) + 0.9 * smooth_noise(rng, size, 12) + rng.standard_normal((size, size)) * 0.9
    yy, xx = np.mgrid[0:size, 0:size] / size
    L += (0.5 - yy) * 5 + (0.5 - xx) * 2  # window light from top-left
    a = a0 + 0.6 * smooth_noise(rng, size, 30) + rng.standard_normal((size, size)) * 0.3
    b = b0 + 0.6 * smooth_noise(rng, size, 30) + rng.standard_normal((size, size)) * 0.3
    pores = rng.random((size, size)) < 0.004
    L -= pores * 3.5
    red = 1.0 if L0 > 55 else 0.55 + 0.45 * (L0 - 30) / 40
    if kind == "patch":
        m = blob(rng, size, 0.5, 0.5, 0.2); a += m * 13 * red; L -= m * 2
    elif kind == "spots":
        m, hi = dots(rng, size, 22, 0.5, 0.5, 0.42, 7, 13); a += m * 15 * red; L -= m * 3; L += hi * 10
    elif kind == "dry":
        m = blob(rng, size, 0.5, 0.5, 0.22, 0.5); flake = (rng.random((size, size)) < 0.06) * m
        L += m * 8 + flake * 8; a += m * 3 * red
    elif kind == "birthmark":
        m = blob(rng, size, 0.5, 0.5, 0.13, 0.25); lob = smooth_noise(rng, size, 25) * m
        a += m * 26 * red + lob * 3; L -= m * 9; b -= m * 2
    elif kind == "nappy":
        m = blob(rng, size, 0.5, 0.55, 0.32, 0.45); s, _ = dots(rng, size, 14, 0.5, 0.5, 0.85, 4, 8)
        a += (m * 12 + s * 10) * red; L -= m * 2
    rgb = to_srgb(lab_to_rgb(L, a, b), gain)
    return Image.fromarray(rgb).filter(ImageFilter.GaussianBlur(0.6))


STOOL = {"2": (78.9, -0.1, 19.0), "5": (79.8, 2.4, 38.3), "7": (85.2, -4.6, 72.8), "8": (73.3, 6.3, 68.1), "G": (38.0, -12.0, 30.0), "B": (42.0, 14.0, 38.0)}


def nappy_photo(lab, seed, gain=(1, 1, 1), size=900):
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:size, 0:size]
    quilt = ((np.sin((xx + yy) / 22) > 0.97) | (np.sin((xx - yy) / 22) > 0.97)).astype(np.float32)
    L = 92 - quilt * 4 + smooth_noise(rng, size, 50) * 1.2 - ((yy / size - 0.5) ** 2) * 10
    a = np.full((size, size), 0.4); b = np.full((size, size), 2.0)
    m = blob(rng, size, 0.5, 0.52, 0.2, 0.5)
    seeds = (rng.random((size, size)) < 0.01) * m
    L = L * (1 - m) + (lab[0] + smooth_noise(rng, size, 15) * 2 - seeds * 6) * m
    a = a * (1 - m) + lab[1] * m
    b = b * (1 - m) + (lab[2] + smooth_noise(rng, size, 20) * 2) * m
    return Image.fromarray(to_srgb(lab_to_rgb(L, a, b), gain)).filter(ImageFilter.GaussianBlur(0.7))


def eye_white_photo(yellow_b, tone, seed, gain=(1, 1, 1), size=900):
    rng = np.random.default_rng(seed)
    L0, a0, b0 = TONES[tone]
    yy, xx = np.mgrid[0:size, 0:size] / size
    L = L0 + smooth_noise(rng, size, 50) * 1.5; a = np.full((size, size), float(a0)); b = np.full((size, size), float(b0))
    eye = ((xx - 0.55) / 0.33) ** 2 + ((yy - 0.55) / 0.15) ** 2 < 1
    L = np.where(eye, 86 + smooth_noise(rng, size, 30) * 1.5, L); a = np.where(eye, 1.5, a); b = np.where(eye, yellow_b, b)
    iris = (xx - 0.55) ** 2 + (yy - 0.55) ** 2 < 0.11 ** 2
    L = np.where(iris, 28, L); a = np.where(iris, 6, a); b = np.where(iris, 14, b)
    pupil = (xx - 0.55) ** 2 + (yy - 0.55) ** 2 < 0.045 ** 2
    L = np.where(pupil, 8, L); a = np.where(pupil, 0, a); b = np.where(pupil, 0, b)
    paper = (xx < 0.22) & (yy < 0.24)
    L = np.where(paper, 94, L); a = np.where(paper, 0, a); b = np.where(paper, 1, b)
    return Image.fromarray(to_srgb(lab_to_rgb(L, a, b), gain)).filter(ImageFilter.GaussianBlur(1.0))


WARM = (1.0, 0.86, 0.62)


def main():
    commons = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if OUT.exists():
        shutil.rmtree(OUT)
    rows = []
    def save(cat, name, img, label, credit="Synthetic, made by tools/make_demo_assets.py"):
        (OUT / cat).mkdir(parents=True, exist_ok=True)
        img.convert("RGB").save(OUT / cat / name, quality=88)
        rows.append((cat, name, label, credit))

    save("skin", "patch_III.jpg", skin_photo("III", "patch", 1), "Red patch · tone III")
    save("skin", "spots_V.jpg", skin_photo("V", "spots", 2), "Small raised spots · tone V")
    save("skin", "dry_IV.jpg", skin_photo("IV", "dry", 3), "Dry patch · tone IV")
    save("skin", "birthmark_II.jpg", skin_photo("II", "birthmark", 4), "Red birthmark · tone II")
    save("skin", "nappy_VI.jpg", skin_photo("VI", "nappy", 5), "Nappy-area redness · tone VI")
    save("skin", "spots_I.jpg", skin_photo("I", "spots", 6), "Small raised spots · tone I")

    save("stool", "stool_7.jpg", nappy_photo(STOOL["7"], 11), "Yellow (normal) · daylight")
    save("stool", "stool_8_warm.jpg", nappy_photo(STOOL["8"], 12, WARM), "Mustard (normal) · warm bulb")
    save("stool", "stool_2.jpg", nappy_photo(STOOL["2"], 13), "Pale clay · daylight")
    save("stool", "stool_5_warm.jpg", nappy_photo(STOOL["5"], 14, WARM), "Pale sand · warm bulb")
    save("stool", "stool_G.jpg", nappy_photo(STOOL["G"], 15), "Dark green (normal)")

    save("yellow", "eye_white_IV.jpg", eye_white_photo(6, "IV", 21), "White eye · tone IV")
    save("yellow", "eye_yellow_V.jpg", eye_white_photo(30, "V", 22), "Yellow eye · tone V")
    save("yellow", "eye_yellow_warm_II.jpg", eye_white_photo(26, "II", 23, WARM), "Yellow eye · warm bulb")

    if commons:
        credits = json.load(open(commons / "credits.json", encoding="utf-8"))
        labels = {
            "vegetable_khichdi_1.jpg": "Vegetable khichdi", "idli_sambar_2.jpg": "Idli with sambar", "dal_bhat.jpg": "Dal, rice, vegetables",
            "curd_rice.jpg": "Curd rice", "starting_solids_homemade_baby_food_in_a_.jpg": "Baby puree in a bowl", "berry_oatmeal_baby_food.jpg": "Oatmeal with berries",
            "red_eye_effect_2.jpg": "Red glow, both eyes", "coats_disease_camera_with_flash.jpg": "White glow, one eye (Coats disease)",
            "a_child_with_a_white_eye_reflection_as_a.jpg": "White glow, one eye (retinoblastoma)",
            "infantile_hemangioma.jpg": "Real: infantile hemangioma", "irritant_diaper_dermatitis.jpg": "Real: nappy rash",
            "erytherma_toxicum_neonatorum.jpg": "Real: newborn rash", "capillary_haemangioma.jpg": "Real: strawberry mark",
        }
        target = {"meal": "meal", "eyes": "eyes", "skinreal": "skin"}
        for cat, name, title, lic, artist, url in credits:
            if name not in labels:
                continue
            img = Image.open(commons / cat / name)
            img.thumbnail((1000, 1000))
            save(target[cat], name, img, labels[name], f"{title} by {artist}, {lic}, via Wikimedia Commons ({url})")

    with open(OUT / "index.tsv", "w", encoding="utf-8", newline="\n") as f:
        f.write("category\tfile\tlabel\tcredit\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    with open(OUT / "CREDITS.md", "w", encoding="utf-8") as f:
        f.write("# Demo photo credits\n\n")
        for cat, name, label, credit in rows:
            f.write(f"- `{cat}/{name}` ({label}): {credit}\n")
    print(len(rows), "demo photos ->", OUT)


if __name__ == "__main__":
    main()
