"""Fetch candidate room photos from Openverse (CC-licensed, mostly Flickr).

Only CC0 / PDM / CC BY / CC BY-SA, so the eval images can live in the repo with
credits. Each file's creator, licence and landing page go to candidates_ov.json.
"""
import json, os, sys, time, urllib.parse, urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "eval", "candidates_ov")
UA = "NilaBabyproofLab/0.1 (research)"
QUERIES = [
    "living room india home", "indian home interior", "hall room sofa tv india", "bedroom india home",
    "indian kitchen gas stove", "kitchen counter knives", "bathroom bucket mug india", "indian balcony railing",
    "staircase house india", "stairs home toddler", "baby gate", "electrical switch board india",
    "extension board cables", "power strip floor", "messy living room floor", "cluttered room toys",
    "toddler living room", "baby crawling living room", "glass coffee table living room", "bookshelf living room",
    "tv stand living room", "kids bedroom", "nursery room", "dining table chairs home", "medicine cabinet bottles",
    "cleaning supplies under sink", "window bed room", "plant pot living room", "home office desk cables floor",
    "ceiling fan bedroom india",
]

def main(per_query=10):
    os.makedirs(OUT, exist_ok=True)
    meta_path = os.path.join(OUT, "candidates.json")
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    seen = {m["id"] for m in meta.values()}
    for q in QUERIES:
        url = "https://api.openverse.org/v1/images/?" + urllib.parse.urlencode(
            {"q": q, "page_size": 20, "license": "by,by-sa,cc0,pdm", "category": "photograph", "mature": "false"})
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
                results = json.load(r)["results"]
        except Exception as e:
            print("query failed", q, e); time.sleep(5); continue
        kept = 0
        for r in results:
            if r["id"] in seen or (r.get("width") or 0) < 800:
                continue
            name = f"o{len(meta)+1:03d}.jpg"
            try:
                with urllib.request.urlopen(urllib.request.Request(r["url"], headers={"User-Agent": UA}), timeout=60) as resp:
                    data = resp.read()
                if len(data) < 30_000:
                    continue
                open(os.path.join(OUT, name), "wb").write(data)
            except Exception as e:
                continue
            seen.add(r["id"])
            meta[name] = {"id": r["id"], "query": q, "title": r.get("title"), "license": r["license"],
                          "license_version": r.get("license_version"), "creator": r.get("creator"),
                          "page": r.get("foreign_landing_url"), "source": r.get("source")}
            kept += 1
            time.sleep(0.5)
            if kept >= per_query:
                break
        print(f"{q!r}: +{kept}", flush=True)
        json.dump(meta, open(meta_path, "w"), indent=1)
        time.sleep(6)
    print("total", len(meta))

if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 10)
