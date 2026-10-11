"""Replay the decision rules offline on saved looks, to tune them without the phone.

A Python copy of Rules.kt + Prompts.parseCrop/parseScene. Results JSON from a full run
carry every look (detector hit + the VLM's raw answer) and the room-wide answer, so
rule changes can be scored in seconds:

    python3 tools/replay.py eval/results/full --keep 2.0 --cross none
    python3 tools/replay.py eval/results/full --sweep

Keep it in step with Rules.kt: the Kotlin is what ships; this is the bench.
"""
import argparse, json, os, re, sys, glob, csv
sys.path.insert(0, os.path.dirname(__file__))
import score

HERE = os.path.dirname(os.path.abspath(__file__))
CAT = {h["id"]: h for h in json.load(open(os.path.join(HERE, "..", "app/src/main/assets/babyproof/hazards.json")))["hazards"]}
REACH_MATTERS = {"choking", "poison", "sharp"}
# The word list is read from Rules.kt itself, so the bench cannot drift from what ships.
_RULES = open(os.path.join(HERE, "..", "app/src/main/java/com/nila/babyproof/pipeline/Rules.kt")).read()
SCENE_WORDS = re.findall(r'"([^"]+)" to "([^"]+)"', _RULES[_RULES.index("SCENE_WORDS = listOf("):])
HARMLESS = set(re.findall(r'"([a-z]+)"', _RULES[_RULES.index("HARMLESS = setOf("):].split(")")[0]))

FIELD = re.compile(r"^\s*\**\s*(OBJECT|MATCH|HAZARD|REACHABLE|PROTECTED)\s*\**\s*[:\-]\s*(.*)$", re.I)

def yesno(s):
    if s is None: return None
    w = s.lower().strip()
    return True if w.startswith("yes") else False if w.startswith("no") else None

def parse_crop(raw):
    if not raw: return None
    f = {}
    for line in raw.splitlines():
        m = FIELD.match(line)
        if m: f.setdefault(m.group(1).upper(), m.group(2).strip().strip("*. "))
    match = yesno(f.get("MATCH"))
    if match is None and "HAZARD" not in f: return None
    return {"obj": f.get("OBJECT", ""), "match": match, "protected": yesno(f.get("PROTECTED")),
            "reachable": yesno(f.get("REACHABLE")), "hazard": f.get("HAZARD")}

def named(obj):
    o = obj.lower()
    for w, hid in SCENE_WORDS:
        if w in o: return CAT.get(hid)
    return None

def decide(look, a, P):
    h = CAT[look["id"]]
    strength = look["score"] / h["threshold"]
    if a is None:
        return ("CHECK", h) if strength >= 1.5 else None
    n = named(a["obj"]) if a["obj"] else None
    can_take = n is not None and n["id"] != h["id"] and (
        n["category"] == h["category"] or P.cross == "any" or (P.cross == "critical" and n["critical"]))
    harmless = bool(a["obj"]) and named(a["obj"]) is None and bool(set(re.findall(r"[a-z]+", a["obj"].lower())) & HARMLESS)
    if a["match"] is True and harmless:
        agreed, hh = False, h
    elif a["match"] is True:
        agreed, hh = True, (n if n is not None and n["category"] == h["category"] else h)
    elif a["match"] is False:
        agreed, hh = can_take, (n if can_take else h)
    else:
        agreed, hh = (a["hazard"] or "").lower() not in ("none", "no"), h
    if not agreed:
        named_other = bool(a["obj"]) and P.strict_named
        if hh["critical"] and strength >= P.keep and not named_other: return ("CHECK", hh)
        if hh["critical"] and strength >= P.keep_named: return ("CHECK", hh)
        return None
    if a["protected"] is True: return ("PROTECTED", hh)
    if a["reachable"] is False and hh["category"] in REACH_MATTERS: return None
    return ("FIX", hh)

def parse_scene(raw):
    if not raw or raw.strip().upper().startswith("NONE"): return []
    items = [re.sub(r"^\d+[.)]\s*", "", t.strip().lstrip("-*• ")).strip(". ") for t in re.split(r"[,\n;]", raw)]
    return [t for t in items if 3 <= len(t) <= 60 and t.lower() != "none"][:6]

def replay(res_dir, P, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for f in glob.glob(os.path.join(res_dir, "*.json")):
        r = json.load(open(f))
        findings = []
        for look in r.get("looks", []):
            d = decide(look, parse_crop(look.get("raw")), P)
            if d: findings.append({"id": d[1]["id"], "status": d[0], "score": look["score"], "box": look["box"]})
        if P.scene:
            have = {x["id"] for x in findings}
            for thing in parse_scene(r.get("scene_raw")):
                h = named(thing)
                if h and h["id"] not in have and (P.scene == "all" or h["critical"]):
                    have.add(h["id"]); findings.append({"id": h["id"], "status": "CHECK", "score": 0, "box": None})
        json.dump({"findings": findings, "ms": r.get("ms", {})}, open(os.path.join(out_dir, os.path.basename(f)), "w"))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("res"); ap.add_argument("--keep", type=float, default=2.0)
    ap.add_argument("--keep-named", type=float, default=99.0)
    ap.add_argument("--strict-named", action="store_true")
    ap.add_argument("--cross", default="none", choices=["none", "critical", "any"])
    ap.add_argument("--scene", default="all", choices=["", "critical", "all"])
    ap.add_argument("--sweep", action="store_true"); ap.add_argument("--verbose", action="store_true")
    P = ap.parse_args()
    out = "/tmp/babyproof_replay"
    if not P.sweep:
        replay(P.res, P, out); score.main(out, P.verbose); return
    for cross in ("none", "critical", "any"):
        for scene in ("", "critical", "all"):
            for keep, strict in ((2.0, False), (2.0, True), (3.0, True), (99, True)):
                P.cross, P.scene, P.keep, P.strict_named, P.keep_named = cross, scene, keep, strict, 99
                print(f"--- cross={cross} scene={scene or 'off'} keep={keep} strict_named={strict}")
                replay(P.res, P, out); score.main(out, False)

if __name__ == "__main__":
    main()
