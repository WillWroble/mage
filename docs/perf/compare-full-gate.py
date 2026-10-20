# compare.py <head prefix dir/prefix> <base dir/prefix>: per case win rate, decided games, crashes, timeouts, ERROR/FATAL kinds
import sys, re, collections, os
CASES = "Standard16-UB Standard-MonoB Standard-MonoG Standard-MonoR Standard-MonoU Standard-MonoW Standard16-5C Standard16-BW Standard16-GB Standard16-GW Standard16-RB Standard16-RG Standard16-RW Standard16-UG Standard16-UR Standard16-UW Commander-MonoG".split()
def norm(line):
    line = re.sub(r'^\S+\s+\S+ \S+ ', '', line)
    line = re.sub(r'=>.*', '', line)
    line = re.sub(r'[0-9a-f]{8}-[0-9a-f-]{27}', '<id>', line)
    line = re.sub(r'\d+', 'N', line)
    return line.strip()[:110]
def stats(path):
    if not os.path.exists(path): return None
    txt = open(path, errors='replace').read()
    m = re.findall(r'win rate: [\d.]+% \((\d+)/(\d+)\)', txt)
    errs = collections.Counter(norm(l) for l in txt.splitlines() if l.startswith(('ERROR', 'FATAL')))
    return dict(w=m[-1] if m else None, crash=txt.count('Game end on critical error'), to=txt.count('Game timed out'), errs=errs)
H, B = sys.argv[1], sys.argv[2]
tw = tg = bw = bg = 0; allh = collections.Counter(); allb = collections.Counter()
print("| case | HEAD wins | base wins | HEAD crashes/timeouts | base crashes/timeouts |\n|---|---|---|---|---|")
for c in CASES:
    h, b = stats(f"{H}-{c}.log"), stats(f"{B}-{c}.log")
    f = lambda s: f"{s['w'][0]}/{s['w'][1]}" if s and s['w'] else "n/a"
    g = lambda s: f"{s['crash']}/{s['to']}" if s else "n/a"
    print(f"| {c} | {f(h)} | {f(b)} | {g(h)} | {g(b)} |")
    if h and h['w']: tw += int(h['w'][0]); tg += int(h['w'][1]); allh += h['errs']
    if b and b['w']: bw += int(b['w'][0]); bg += int(b['w'][1]); allb += b['errs']
print(f"| all | {tw}/{tg} | {bw}/{bg} | | |\n")
print("| ERROR/FATAL kind | HEAD | base |\n|---|---|---|")
for k in sorted(set(allh) | set(allb), key=lambda k: -(allh[k] + allb[k])):
    print(f"| {k} | {allh[k]} | {allb[k]} |")
