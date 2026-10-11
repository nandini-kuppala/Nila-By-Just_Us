"""Fetch candidate room photos for the babyproofing eval set from Wikimedia Commons.

Only freely licensed files (CC0 / CC BY / CC BY-SA / public domain) are kept, and
each one's author, licence and page are written to candidates.json so the final
set can carry proper credits (eval/images/CREDITS.md).
"""
import json, os, re, sys, time, urllib.parse, urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "eval", "candidates")
API = "https://commons.wikimedia.org/w/api.php"
UA = "NilaBabyproofLab/0.1 (research; nandini-kuppala)"
QUERIES = [
    "living room interior India", "living room interior", "drawing room India house",
    "bedroom interior India", "bedroom interior", "kitchen interior India", "kitchen interior",
    "staircase interior house", "home staircase", "balcony apartment India", "bathroom interior India",
    "electrical socket wall India", "power strip extension cord floor", "home office desk cables",
    "nursery room crib", "dining room interior", "baby gate stairs", "outlet cover child safety",
    "television stand living room", "bookshelf room interior", "study room interior",
]
OK = re.compile(r"^(cc0|cc[- ]by(-sa)?[- ]?[0-9.]*|public domain|pd.*)$", re.I)

def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)

def main(per_query=8):
    os.makedirs(OUT, exist_ok=True)
    meta_path = os.path.join(OUT, "candidates.json")
    meta = json.load(open(meta_path)) if os.path.exists(meta_path) else {}
    for q in QUERIES:
        data = get({"action": "query", "format": "json", "generator": "search",
                    "gsrsearch": f"{q} filetype:bitmap", "gsrnamespace": 6, "gsrlimit": 25,
                    "prop": "imageinfo", "iiprop": "url|extmetadata|size", "iiurlwidth": 1600})
        pages = sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
        kept = 0
        for p in pages:
            ii = (p.get("imageinfo") or [{}])[0]
            em = ii.get("extmetadata", {})
            lic = em.get("LicenseShortName", {}).get("value", "")
            if not OK.match(lic.strip()) or ii.get("width", 0) < 900:
                continue
            title = p["title"]
            if title in meta:
                continue
            ext = os.path.splitext(ii["thumburl"])[1].lower() or ".jpg"
            name = f"c{len(meta)+1:03d}{ext}"
            req = urllib.request.Request(ii["thumburl"], headers={"User-Agent": UA})
            try:
                with urllib.request.urlopen(req, timeout=60) as r, open(os.path.join(OUT, name), "wb") as f:
                    f.write(r.read())
            except Exception as e:
                print("skip", title, e); continue
            artist = re.sub("<[^>]+>", "", em.get("Artist", {}).get("value", "")).strip()
            meta[title] = {"file": name, "query": q, "license": lic, "author": artist,
                           "page": ii.get("descriptionurl"), "size": [ii.get("width"), ii.get("height")]}
            kept += 1
            time.sleep(0.3)
            if kept >= per_query:
                break
        print(f"{q!r}: +{kept}")
        json.dump(meta, open(meta_path, "w"), indent=1)
    print("total", len(meta))

if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 8)
