"""fetch_commons.py OUTDIR "File:Title.jpg"... : fetch licence/author and a 1024 px thumbnail for each file."""
import json, os, re, sys, time, urllib.parse, urllib.request
UA = {'User-Agent': 'LittleSignsHackathon/1.0 (research; contact via github Vishal-AS-0407)'}
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
def get(url, raw=False):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                b = r.read()
                return b if raw else json.loads(b.decode('utf-8'))
        except urllib.error.HTTPError as e:
            if e.code == 429: time.sleep(5 * (attempt + 1)); continue
            raise
    raise RuntimeError('rate limited')
meta = []
for t in sys.argv[2:]:
    time.sleep(2)
    q = 'https://commons.wikimedia.org/w/api.php?action=query&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=1024&format=json&titles=' + urllib.parse.quote(t)
    try:
        page = next(iter(get(q)['query']['pages'].values()))
        ii = page['imageinfo'][0]; em = ii.get('extmetadata', {})
        lic = em.get('LicenseShortName', {}).get('value', '?')
        artist = re.sub('<[^>]+>', '', em.get('Artist', {}).get('value', '?')).strip()
        fn = re.sub(r'[^a-z0-9]+', '_', t[5:].rsplit('.', 1)[0].lower())[:40].strip('_') + '.jpg'
        time.sleep(1)
        open(os.path.join(out, fn), 'wb').write(get(ii['thumburl'], raw=True))
        meta.append({'title': t, 'file': fn, 'license': lic, 'artist': artist, 'page': ii['descriptionurl']})
        print('OK', fn, '|', lic, '|', artist)
    except Exception as e:
        print('FAIL', t, e)
json.dump(meta, open(os.path.join(out, 'meta.json'), 'w', encoding='utf-8'), indent=1, ensure_ascii=False)
