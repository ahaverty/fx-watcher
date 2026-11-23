import hashlib, json, sys, urllib.request, os, time
env = {}
for l in open(os.path.expanduser('~/.nightscout/nightscout.env'), encoding='utf-8'):
    l = l.strip()
    if l and not l.startswith('#') and '=' in l:
        k, v = l.split('=', 1); env[k] = v
h = hashlib.sha1(env['API_SECRET'].encode()).hexdigest()
base = sys.argv[1]
out = sys.argv[2]
def get(path):
    r = urllib.request.Request(base + path, headers={'api-secret': h})
    return json.load(urllib.request.urlopen(r, timeout=60))
print(get('/api/v1/status.json').get('version'))
# page backwards in time: entries older than `before`
rows = {}
before = int(time.time()*1000)
while True:
    page = get(f'/api/v1/entries/sgv.json?count=5000&find[date][$lt]={before}')
    if not page: break
    for e in page:
        rows[e['date']] = (e['sgv'], e.get('device', ''))
    nb = min(e['date'] for e in page)
    if nb >= before: break
    before = nb
    print(len(rows), time.strftime('%Y-%m-%d', time.gmtime(nb/1000)), file=sys.stderr)
    if len(rows) > 400000: break
with open(out, 'w') as f:
    for d in sorted(rows):
        f.write(f'{d},{rows[d][0]},{rows[d][1]}\n')
print('saved', len(rows))
