#!/usr/bin/env python3
"""Score extracted visit notes against testdata/visits.json's expected notes.

Lenient on wording, strict on the things that hurt when wrong:
  * medicines: found by name; as-needed must match; a course length, when
    expected, must match exactly
  * dates of visits/vaccines/tests: exact
  * invented medicines (not in expected): counted, the number that matters most
  * watch_for / dos / donts: recall by keyword overlap
"""
import json, re, sys, glob, os, datetime

def words(s): return set(re.findall(r"[a-z0-9]+", s.lower()))

def match(expected, got, key):
    e = words(expected.get(key, "") if isinstance(expected, dict) else expected)
    best, bi = 0.0, -1
    for i, g in enumerate(got):
        gw = words(g.get(key, "") if isinstance(g, dict) else g)
        if not e or not gw: continue
        sc = len(e & gw) / len(e)
        if sc > best: best, bi = sc, i
    return best, bi

def text_items(lst):
    return [x["text"] if isinstance(x, dict) else x for x in lst]

def date_of(exp, visit):
    if exp.get("date"): return exp["date"]
    if exp.get("in_days") is not None: return str(visit + datetime.timedelta(days=exp["in_days"]))
    if exp.get("weekday"):
        wd = ["monday","tuesday","wednesday","thursday","friday","saturday","sunday"].index(exp["weekday"].lower())
        d = visit + datetime.timedelta(days=1)
        while d.weekday() != wd: d += datetime.timedelta(days=1)
        return str(d)
    return None

tot = dict(med=0, med_ok=0, invented=0, dates=0, dates_ok=0, notes=0, notes_ok=0)
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.json"))):
    r = json.load(open(f))
    if "result" not in r: continue
    exp = r["expected"]; notes = r["result"]["notes"]
    visit = datetime.date(2026, 10, 10)
    lines = []
    meds = notes["medicines"]; used = set()
    for m in exp["medicines"]:
        tot["med"] += 1
        sc, i = match(m, meds, "name")
        ok = sc >= 0.5
        why = []
        if ok:
            used.add(i); g = meds[i]
            if m.get("as_needed") is not None and bool(g["as_needed"]) != bool(m["as_needed"]): ok = False; why.append("as_needed")
            if m.get("duration_days") and g.get("duration_days") != m["duration_days"]: ok = False; why.append(f"days {g.get('duration_days')}!={m['duration_days']}")
        tot["med_ok"] += ok
        lines.append(f"   med  {'OK ' if ok else 'BAD'} {m['name']:24s} {('-> ' + meds[i]['name'] + ' ' + str(meds[i].get('reminders'))) if i >= 0 else 'missing'} {' '.join(why)}")
    inv = [g["name"] for j, g in enumerate(meds) if j not in used]
    tot["invented"] += len(inv)
    if inv: lines.append(f"   med  EXTRA {inv}")
    got_dates = [a.get("date") for a in notes["appointments"]] + [v.get("date") for v in notes["vaccines"]] + [t.get("date") for t in notes["tests"]]
    for a in exp["appointments"] + [v for v in exp["vaccines"] if not v.get("given_today")]:
        d = date_of(a, visit)
        if not d: continue
        tot["dates"] += 1; ok = d in got_dates; tot["dates_ok"] += ok
        lines.append(f"   date {'OK ' if ok else 'BAD'} {a.get('what', a.get('name')):24s} want {d} got {sorted(set(x for x in got_dates if x))}")
    for k_exp, k_got in (("watch_for", "watch_for"), ("dos", "dos"), ("donts", "donts")):
        got = text_items(notes[k_got])
        for e in exp[k_exp]:
            tot["notes"] += 1
            sc, _ = match(e, got, "text")
            tot["notes_ok"] += sc >= 0.34
    st = {s["name"]: s["ms"] for s in r["result"]["stages"]}
    print(f"{r['case']:22s} meds {sum(1 for l in lines if 'med  OK' in l)}/{len(exp['medicines'])}  dropped={len(notes.get('dropped', []))}  "
          f"asr={st.get('whisper-run','-')}ms llm={st.get('llm-run','-')}ms")
    if len(sys.argv) > 2: print("\n".join(lines)); [print("   drop", d[:110]) for d in notes.get("dropped", [])]
print(f"\nTOTAL medicines right {tot['med_ok']}/{tot['med']}, invented {tot['invented']}, "
      f"dates {tot['dates_ok']}/{tot['dates']}, notes recall {tot['notes_ok']}/{tot['notes']}")
