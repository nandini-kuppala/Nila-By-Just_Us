"""Fetch candidate photos from curated Wikimedia Commons categories (politely).

Free licences only (CC0 / CC BY / CC BY-SA / public domain). Credits go to
eval/candidates_cat/candidates.json.
"""
import json, os, re, sys, time, urllib.parse, urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "eval", "candidates_cat")
API = "https://commons.wikimedia.org/w/api.php"
UA = "NilaBabyproofLab/0.1 (hackathon research; contact via github nandini-kuppala)"
CATS = sys.argv[2:] or [
    "Living rooms in India", "Kitchens in India", "Bathrooms in India", "Bedrooms in India",
    "Interiors of houses in India", "Electrical outlets in India", "Power strips", "Extension cords",
    "Baby gates", "Childproofing", "Staircases in houses", "Balconies in India", "LPG cylinders in India",
    "Children's rooms", "Nurseries (rooms)", "Living rooms", "Home offices", "Cable management",
]
OK = re.compile(r"^(cc0|cc[- ]by(-sa)?[- ]?[0-9.]*( [a-z]+)?|public domain|pd.*)$", re.I)

def get(params):
    params["format"] = "json"
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers={"User-Agent": UA})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(10 * (i + 1))
    return {}

def main(per_cat):
    os.makedirs(OUT, exist_ok=True)
    meta_path = os.path.join(OUT, "candidates.json")
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    titles_done = {m["title"] for m in meta.values()}
    for cat in CATS:
        data = get({"action": "query", "generator": "categorymembers", "gcmtitle": "Category:" + cat,
                    "gcmtype": "file", "gcmlimit": 60, "prop": "imageinfo",
                    "iiprop": "url|extmetadata|size|mime", "iiurlwidth": 1280})
        pages = list(data.get("query", {}).get("pages", {}).values())
        kept = 0
        for p in pages:
            ii = (p.get("imageinfo") or [{}])[0]
            if ii.get("mime") not in ("image/jpeg", "image/png") or ii.get("width", 0) < 900:
                continue
            lic = ii.get("extmetadata", {}).get("LicenseShortName", {}).get("value", "").strip()
            if not OK.match(lic) or p["title"] in titles_done:
                continue
            name = f"k{len(meta)+1:03d}.jpg"
            for attempt in range(3):
                try:
                    req = urllib.request.Request(ii["thumburl"], headers={"User-Agent": UA})
                    with urllib.request.urlopen(req, timeout=60) as r:
                        open(os.path.join(OUT, name), "wb").write(r.read())
                    break
                except Exception as e:
                    time.sleep(15 * (attempt + 1))
            else:
                print("skip", p["title"]); continue
            artist = re.sub("<[^>]+>", "", ii["extmetadata"].get("Artist", {}).get("value", "")).strip()
            meta[name] = {"title": p["title"], "category": cat, "license": lic, "author": artist,
                          "page": ii.get("descriptionurl")}
            titles_done.add(p["title"]); kept += 1
            time.sleep(1.5)
            if kept >= per_cat:
                break
        print(f"{cat!r}: {len(pages)} files, +{kept}", flush=True)
        json.dump(meta, open(meta_path, "w"), indent=1)
    print("total", len(meta))

if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 10)
