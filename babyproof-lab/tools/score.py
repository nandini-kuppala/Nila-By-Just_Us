"""Score lab results against eval/labels.csv.

Image-level: does the to-fix list name each labelled hazard, does it name things
that are not there, and does it put protected things in the done list.
Hazards are compared by group, so "socket" and "switch board" count as the same
thing, as do "tall shelf" and "chest of drawers".

    python3 tools/score.py eval/results/full   [--verbose]
"""
import csv, json, os, sys
from collections import Counter, defaultdict

GROUP = {"switchboard": "socket", "dresser": "bookshelf", "table_corner": "glass_table", "charger": "cable",
         "small_toys": "choking", "coins": "choking", "battery": "choking", "plastic_bag": "choking", "lamp": "lamp"}
CRITICAL = {"socket", "power_strip", "stairs", "window", "balcony", "stove", "cylinder", "medicine", "cleaner",
            "bucket", "heater", "iron", "knife", "candle"}

def g(i): return GROUP.get(i, i)

def main(res_dir, verbose=False):
    labels = {r["image"]: r for r in csv.DictReader(open(os.path.join(os.path.dirname(__file__), "..", "eval", "labels.csv")))}
    tp = fn = fp = 0; crit_tp = crit_fn = 0; prot_ok = prot_n = 0
    per_group = defaultdict(Counter); fp_groups = Counter(); times = []
    rows = []
    for img, lab in sorted(labels.items()):
        p = os.path.join(res_dir, img.replace(".jpg", ".json"))
        if not os.path.exists(p): continue
        r = json.load(open(p))
        gt = {g(x) for x in lab["fix"].split(";") if x}
        prot = {g(x) for x in lab["protected"].split(";") if x}
        pred = {g(f["id"]) for f in r["findings"] if f["status"] in ("FIX", "CHECK")}
        pred_prot = {g(f["id"]) for f in r["findings"] if f["status"] == "PROTECTED"}
        hit = gt & pred; miss = gt - pred; extra = pred - gt - prot
        tp += len(hit); fn += len(miss); fp += len(extra)
        for x in gt:
            per_group[x]["tp" if x in hit else "fn"] += 1
            if x in CRITICAL: (crit_tp := crit_tp + (x in hit)); crit_fn += x not in hit
            if x in CRITICAL and x in hit: pass
        crit_tp = crit_tp
        for x in extra: fp_groups[x] += 1
        for x in prot:
            prot_n += 1; prot_ok += x in pred_prot
        times.append(r.get("ms", {}).get("total") or sum(v for k, v in r.get("ms", {}).items() if k in ("detect", "looks", "scene")))
        rows.append((img, sorted(hit), sorted(miss), sorted(extra), lab["note"]))
    n = len(rows)
    if not n: print("no results"); return
    crit_total = sum(per_group[x]["tp"] + per_group[x]["fn"] for x in per_group if x in CRITICAL)
    crit_found = sum(per_group[x]["tp"] for x in per_group if x in CRITICAL)
    print(f"photos {n} | recall {tp}/{tp+fn} = {tp/max(1,tp+fn):.0%} | critical recall {crit_found}/{crit_total} = {crit_found/max(1,crit_total):.0%}"
          f" | false items {fp} ({fp/n:.2f} per photo) | protected {prot_ok}/{prot_n} | median time {sorted(times)[n//2]/1000:.1f}s")
    print("per hazard (found/labelled):", ", ".join(f"{k} {v['tp']}/{v['tp']+v['fn']}" for k, v in sorted(per_group.items())))
    print("false items by hazard:", dict(fp_groups.most_common()))
    if verbose:
        for img, hit, miss, extra, note in rows:
            print(f"  {img} hit={hit} miss={miss} extra={extra}  [{note}]")

if __name__ == "__main__":
    main(sys.argv[1], "--verbose" in sys.argv)
