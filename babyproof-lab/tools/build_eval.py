"""Assemble eval/images + labels.csv + CREDITS.md from the hand-picked candidates.

Labels are image-level: which catalogue hazards are present and need fixing, and
which are present but already protected. They were set by looking at each photo;
a hazard is labelled only if it is clearly visible (a socket that is a few pixels
of an indistinct wall is not labelled, so the detector is not punished for it).
"""
import csv, json, os, shutil, glob

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.join(HERE, "..", "eval")

# (candidate file prefix, hazards to fix, already protected, note)
PICKS = [
    ("c002", "window", "", "Indian sitting room, low wide window"),
    ("c004", "dresser;candle", "", "Indian room, tall cabinet, shrine lamp"),
    ("c008", "candle", "", "home shrine with oil lamp"),
    ("c011", "window;tv;plant", "", "living room, big windows, TV on low stand"),
    ("c022", "", "", "negative: cane chair close-up"),
    ("c025", "tv;window", "", "modern bedroom, wall TV, window"),
    ("c026", "", "", "negative: floor bed, spinning wheel"),
    ("c034", "window", "", "bedroom with window"),
    ("c040", "window", "", "bedroom with window"),
    ("c046", "stove;cylinder;window", "", "Indian kitchen, stove on counter, cylinder below"),
    ("c065", "stairs", "", "staircase with railing"),
    ("c067", "stairs", "", "narrow home staircase"),
    ("c077", "cable", "", "speaker with loose cable on floor"),
    ("c078", "cable;power_strip", "", "tangle of cables on a power strip"),
    ("c079", "cable;power_strip;socket", "", "power strip, wall sockets, cables"),
    ("c090", "heater;window", "", "dining room, radiator under window"),
    ("c104", "", "socket", "protected: socket with safety cover"),
    ("c105", "socket", "socket", "one socket bare, one with plug covers"),
    ("c107", "power_strip", "", "power strip close-up"),
    ("c110", "socket", "", "wall sockets"),
    ("k003", "stove;window", "", "Indian kitchen, cooking at gas stove"),
    ("k016", "window", "", "bedroom, windows, table lamps"),
    ("k021", "bookshelf", "", "Indian living room, tall bookshelf"),
    ("k022", "window", "", "Indian room, low window"),
    ("k025", "stairs;balcony", "", "Indian house, stairs and inner balcony"),
    ("k029", "", "window", "Indian living room, high window with a grill (protected)"),
    ("k032", "socket;switchboard", "", "Indian modular switchboard with plug"),
    ("k034", "socket", "", "desk pop-up socket"),
    ("k038", "power_strip;charger", "", "power strip with chargers plugged in"),
    ("k039", "power_strip", "", "Indian extension board"),
    ("k046", "power_strip;cable", "", "socket cube under a desk, cables"),
    ("k049", "power_strip;charger;cable", "", "socket cube on a table with chargers"),
    ("k065", "stove;cylinder", "", "Indian kitchen, stove on the floor, cylinder"),
    ("k066", "cylinder", "", "two gas cylinders"),
    ("k069", "cylinder", "", "gas cylinder with regulator"),
    ("k071", "tv;small_toys", "", "children's room, TV on stand, toys"),
    ("k074", "window", "", "bedroom with cradle and window"),
    ("k077", "bookshelf", "", "child's room, tall shelf unit"),
    ("k083", "stairs", "", "modern living room, open stairs"),
    ("k088", "tv;window", "", "living room, large TV, window"),
    ("k089", "heater;plant", "", "living room, tower heater, plant"),
    ("k090", "plant", "", "living room, plants, low tables"),
    ("k095", "bookshelf;lamp", "", "study, bookshelves, floor lamp"),
    ("k096", "bookshelf;lamp", "", "study, bookshelves, floor lamp"),
    ("k100", "cable;charger", "", "desk with laptop, chargers, cables"),
    ("c003", "", "", "negative-ish: Indian room, doors, stand"),
    ("c020", "bookshelf", "", "chair and bookshelf"),
]

def find(prefix):
    for d in ("candidates", "candidates_cat"):
        for f in glob.glob(os.path.join(EV, d, prefix + "*")):
            if not f.endswith(".json"):
                return d, f
    raise SystemExit("missing " + prefix)

def meta_for(d, f):
    m = json.load(open(os.path.join(EV, d, "candidates.json")))
    base = os.path.basename(f)
    for k, v in m.items():
        if v.get("file") == base or k == base:
            return v
    for k, v in m.items():
        if base.startswith(v.get("file", "~")):
            return v
    return {}

def main():
    out = os.path.join(EV, "images"); os.makedirs(out, exist_ok=True)
    for f in glob.glob(os.path.join(out, "*.jpg")): os.remove(f)
    rows, credits = [], []
    for i, (pre, fix, prot, note) in enumerate(PICKS, 1):
        d, src = find(pre)
        name = f"e{i:02d}.jpg"
        shutil.copy(src, os.path.join(out, name))
        m = meta_for(d, src)
        rows.append({"image": name, "source_id": pre, "fix": fix, "protected": prot, "note": note})
        credits.append(f"| {name} | {m.get('author','?')[:60]} | {m.get('license','?')} | {m.get('page') or ''} |")
    with open(os.path.join(EV, "labels.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["image", "source_id", "fix", "protected", "note"]); w.writeheader(); w.writerows(rows)
    with open(os.path.join(out, "CREDITS.md"), "w") as fh:
        fh.write("# Eval photo credits\n\nAll from Wikimedia Commons under free licences. Resized copies.\n\n"
                 "| File | Author | Licence | Source |\n|---|---|---|---|\n" + "\n".join(credits) + "\n")
    print(len(rows), "photos")

if __name__ == "__main__":
    main()
