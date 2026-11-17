# YT: summary of scripts/audit.sh: lint ids in YT files, unused YT resources, classes R8 dropped
# (many are inlining false positives: grep before deleting), preference keys nobody names, greps.
# Usage: python3 -I scripts/audit_summary.py <checkout> <out-dir>
import sys, re, os, collections, glob
import xml.etree.ElementTree as ET
W, S = sys.argv[1], sys.argv[2]
def P(*a): print(' '.join(str(x) for x in a))
def is_yt(p):
    p = p.replace('\\', '/')
    if '/com/kyant/' in p: return False
    return ('/ui/yt/' in p or re.search(r'/Yt[A-Z0-9][^/]*\.(kt|java)$', p) is not None
            or re.search(r'/(yt_|ic_yt)[^/]*\.[a-z]+$', p) is not None)
rank = {'Fatal': 0, 'Error': 1, 'Warning': 2, 'Information': 3}
xs = sorted(glob.glob(W + '/app/build/reports/lint-results*.xml'))
if xs:
    root = ET.parse(xs[0]).getroot()
    allc = collections.Counter(); ytc = collections.Counter(); meta = {}; locs = collections.defaultdict(list)
    for i in root.iter('issue'):
        iid = i.get('id'); meta[iid] = (i.get('severity') or '?', i.get('category') or '?'); allc[iid] += 1
        l = i.find('location'); f = (l.get('file') if l is not None else '') or ''
        if is_yt(f):
            ytc[iid] += 1
            locs[iid].append('%s:%s  %s' % (f.split('/app/src/')[-1], (l.get('line') or ''), (i.get('message') or '').replace('\n', ' ')[:220]))
    order = sorted(ytc.items(), key=lambda kv: (rank.get(meta[kv[0]][0], 9), -kv[1]))
    P('LINT: %d issues total, %d in YT files (%d ids). By id in YT files:' % (sum(allc.values()), sum(ytc.values()), len(ytc)))
    for iid, n in order[:50]:
        P('  %-5s %-40s %4d  %s' % (meta[iid][0][:5], iid, n, meta[iid][1]))
    up = [(iid, n - ytc.get(iid, 0)) for iid, n in allc.items() if meta[iid][0] in ('Fatal', 'Error') and n - ytc.get(iid, 0) > 0]
    P('  errors in non-YT files:', ', '.join('%s:%d' % x for x in sorted(up, key=lambda x: -x[1])[:12]))
    with open(S + '/lint_yt.txt', 'w') as f:
        for iid, n in order:
            f.write('## %s (%s, %s) x%d\n' % (iid, meta[iid][0], meta[iid][1], n)); f.write('\n'.join(locs[iid]) + '\n\n')
    with open(S + '/unused_yt_resources.txt', 'w') as f:
        f.write('\n'.join(locs.get('UnusedResources', [])) + '\n')
else:
    P('LINT: no xml report')
u = S + '/usage.txt'
def ytn(n):
    s = n.split('.')[-1]
    return '.ui.yt.' in n or s.startswith('Yt')
if os.path.exists(u):
    dead = []; mem = collections.defaultdict(list); cur = None
    for line in open(u, errors='replace'):
        line = line.rstrip('\n')
        if not line.strip(): continue
        if not line.startswith((' ', '\t')):
            cur = line.rstrip(':')
            if not line.endswith(':') and cur.startswith('com.github.libretube'): dead.append(cur)
        elif cur and cur.startswith('com.github.libretube'): mem[cur].append(line.strip())
    d = sorted(c for c in dead if ytn(c) and '$' not in c and 'ComposableSingletons' not in c)
    open(S + '/dead_yt_classes.txt', 'w').write('\n'.join(d) + '\n')
    P('R8: fully removed app classes %d; YT top-level %d:' % (len(dead), len(d)))
    P('   ' + ', '.join(c.split('.')[-1] for c in d[:70]))
    noise = re.compile(r'component\d+|copy(\$default)?\(|\$default|hashCode|equals|toString|access\$|<init>|<clinit>|serializer|\$stable|INSTANCE|Companion|write\$Self|\$\$')
    real = {c: [m for m in ms if not noise.search(m)] for c, ms in mem.items() if ytn(c) and '$' not in c}
    top = sorted(((len(v), c) for c, v in real.items() if v), reverse=True)
    P('R8: YT classes with removed members (noise filtered): %d classes, %d members; top:' % (len(top), sum(n for n, _ in top)))
    P('   ' + ', '.join('%s:%d' % (c.split('.')[-1], n) for n, c in top[:25]))
    with open(S + '/dead_yt_members.txt', 'w') as f:
        for n, c in top:
            f.write('## %s (%d)\n' % (c, n)); f.write('\n'.join('  ' + m for m in real[c]) + '\n')
else:
    P('R8: no usage.txt')
keys = []
for x in sorted(glob.glob(W + '/app/src/main/res/xml/*.xml')):
    try: r = ET.parse(x).getroot()
    except Exception: continue
    for e in r.iter():
        for a, v in e.attrib.items():
            if a.split('}')[-1] == 'key' and v.startswith('yt_'):
                keys.append((v, e.tag.split('.')[-1], os.path.basename(x)))
buf = []; ytfiles = []
for d, _, fs in os.walk(W + '/app/src/main/java'):
    for fn in fs:
        if fn.endswith(('.kt', '.java')):
            p = os.path.join(d, fn); buf.append(open(p, errors='replace').read())
            if fn.endswith('.kt') and is_yt(p): ytfiles.append(p)
src = '\n'.join(buf)
deadk = [(k, t, x) for k, t, x in keys if ('"%s"' % k) not in src]
P('PREFS: %d yt_ keys in res/xml; %d never named in code:' % (len(keys), len(deadk)))
for k, t, x in deadk[:40]: P('   %s | %s | %s' % (k, t, x))
def scan(pattern, label, limit):
    rx = re.compile(pattern); hits = []
    for p in sorted(ytfiles):
        for n, line in enumerate(open(p, errors='replace'), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*')): continue
            if rx.search(line): hits.append('%s:%d %s' % (os.path.basename(p), n, s[:110]))
    P('%s: %d' % (label, len(hits)))
    for h in hits[:limit]: P('   ' + h)
    open(S + '/grep_%s.txt' % re.sub(r'\W+', '_', label), 'w').write('\n'.join(hits) + '\n')
scan(r'\brunBlocking\b', 'runBlocking', 10)
scan(r'GlobalScope\.', 'GlobalScope', 6)
scan(r'Thread\.sleep\(', 'Thread.sleep', 6)
scan(r'"[^"\n]*[äöüßÄÖÜ][^"\n]*"', 'german-literals', 14)
