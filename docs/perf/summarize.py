#!/usr/bin/env python3
"""Summarize an MCTSDeckValidationTest run log (written via log4j-perf.properties), optionally with a JFR recording.

  summarize.py run.log --cases UB 5C UR GW
  summarize.py run.log --cases UB --jfr run.jfr --case 0
"""
import argparse
import json
import re
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timezone

LINE = re.compile(r'^(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\s+(\S+ \S+)\s(.*?)\s*=>\[[^\]]*\]\s*(\S*)')
MOVE = re.compile(r'Player: (\S+) simulated (\d+) evaluations in ([\d.E-]+) seconds')
WR = re.compile(r'Player A win rate: [\d.]+% \((\d+)/(\d+)\)')
STOPS = {'required visits reached': 'budget', 'timed out, ending search': 'timeout',
         'too many nodes in tree': 'nodes', 'tree is too deep': 'depth', 'force time out': 'force'}
# inclusive buckets for JFR execution samples: label -> frame prefix
HOT = {
    'Game copy (GameImpl.<init>)': 'mage.game.GameImpl.<init>(',
    'GameState copy (GameState.<init>)': 'mage.game.GameState.<init>(',
    'RandomUtil.deepCopy': 'mage.util.RandomUtil.deepCopy(',
    'getPlayable': 'mage.players.PlayerImpl.getPlayable(',
    'getManaAvailable': 'mage.players.PlayerImpl.getManaAvailable(',
    'applyEffects': 'mage.game.GameImpl.applyEffects(',
    'encoder (StateEncoder)': 'mage.player.ai.encoder.StateEncoder.',
}
SAMPLE_PERIOD_MS = 10  # jdk.ExecutionSample period in the 'profile' settings


def norm(msg):
    msg = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', msg)
    msg = re.sub(r'\d+(\.\d+)?', '#', msg)
    return msg[:160]


def parse_log(path):
    cases = []
    for line in open(path, errors='replace'):
        m = LINE.match(line)
        if not m:
            continue
        level, ts, msg, where = m.groups()
        if 'STARTING DATA GENERATION' in msg:
            cases.append(dict(start=ts, end=None, sims=0, secs=0.0, moves=0, stops=Counter(), errors=Counter(),
                              wins=None, games=None))
        if not cases:
            continue
        c = cases[-1]
        mv = MOVE.search(msg)
        if mv:
            c['sims'] += int(mv.group(2))
            c['secs'] += float(mv.group(3))
            c['moves'] += 1
        for k, v in STOPS.items():
            if k in msg:
                c['stops'][v] += 1
        wr = WR.search(msg)
        if wr:
            c['wins'], c['games'] = int(wr.group(1)), int(wr.group(2))
            c['end'] = ts
        if level in ('ERROR', 'FATAL'):
            c['errors'][f'{level} {where}: {norm(msg)}'] += 1
    return cases


def print_log(cases, names):
    print('| case | games | win rate | sims | search s | sims/s | stops |')
    print('|---|---|---|---|---|---|---|')
    tot_sims = tot_secs = 0
    for name, c in zip(names, cases):
        wr = f"{c['wins']}/{c['games']} ({100.0 * c['wins'] / c['games']:.1f}%)" if c['games'] else 'n/a'
        sps = c['sims'] / c['secs'] if c['secs'] else 0
        stops = ', '.join(f'{k} {v}' for k, v in sorted(c['stops'].items()))
        print(f"| {name} | {c['games']} | {wr} | {c['sims']} | {c['secs']:.0f} | {sps:.1f} | {stops} |")
        tot_sims += c['sims']
        tot_secs += c['secs']
    print(f"| **all** | | | {tot_sims} | {tot_secs:.0f} | {tot_sims / tot_secs if tot_secs else 0:.1f} | |")
    print()
    print('| ERROR/FATAL (normalized) | ' + ' | '.join(names) + ' | total |')
    print('|---|' + '---|' * (len(names) + 1))
    all_err = Counter()
    for c in cases:
        all_err.update(c['errors'])
    for msg, n in all_err.most_common():
        per = ' | '.join(str(c['errors'][msg]) for c in cases)
        print(f"| `{msg.replace('|', '/')}` | {per} | {n} |")


def log_time(ts):
    return datetime.strptime(ts, '%Y-%m-%d %H:%M:%S,%f').replace(tzinfo=timezone.utc)


def jfr_json(path, event):
    out = subprocess.run(['jfr', 'print', '--json', '--events', event, path],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)['recording']['events']


def ev_time(e):
    return datetime.fromisoformat(e['values']['startTime'])


def duration_s(iso):  # "PT0.001234S"
    m = re.match(r'PT(?:(\d+)M)?([\d.]+)S', iso)
    return (int(m.group(1) or 0) * 60 + float(m.group(2))) if m else 0.0


def print_jfr(path, case):
    t0, t1 = log_time(case['start']), log_time(case['end'])
    steps = case['sims']

    samples, hits = 0, Counter()
    proc = subprocess.Popen(['jfr', 'print', '--events', 'jdk.ExecutionSample', '--stack-depth', '4096', path],
                            stdout=subprocess.PIPE, text=True)
    inside, seen = False, set()
    for line in proc.stdout:
        s = line.strip()
        if s.startswith('startTime ='):
            hms, day = re.match(r'startTime = (\S+) \((\S+)\)', s).groups()
            t = datetime.fromisoformat(f'{day}T{hms}').replace(tzinfo=timezone.utc)
            inside, seen = t0 <= t <= t1, set()
            if inside:
                samples += 1
        elif inside and s == '}':
            hits.update(seen)
        elif inside:
            for label, prefix in HOT.items():
                if s.startswith(prefix):
                    seen.add(label)
    proc.wait()

    print(f'Window {case["start"]} .. {case["end"]}, {steps} simulation steps, {samples} execution samples')
    print()
    print('| inclusive bucket | samples | share of samples | est. CPU µs/step |')
    print('|---|---|---|---|')
    for label in HOT:
        n = hits[label]
        print(f'| {label} | {n} | {100.0 * n / samples:.1f}% | {n * SAMPLE_PERIOD_MS * 1000.0 / steps:.0f} |')
    print(f'| all samples | {samples} | 100% | {samples * SAMPLE_PERIOD_MS * 1000.0 / steps:.0f} |')
    print()

    # cumulative per-thread allocation counters: bytes allocated in the window per thread
    before, last = {}, {}
    for e in sorted(jfr_json(path, 'jdk.ThreadAllocationStatistics'), key=ev_time):
        tid, t, v = e['values']['thread']['javaThreadId'], ev_time(e), e['values']['allocated']
        if t < t0:
            before[tid] = v
        elif t <= t1:
            last[tid] = v
    alloc = sum(v - before.get(tid, 0) for tid, v in last.items())
    sampled = sum(e['values']['weight'] for e in jfr_json(path, 'jdk.ObjectAllocationSample')
                  if t0 <= ev_time(e) <= t1)

    gcs = [e for e in jfr_json(path, 'jdk.GarbageCollection') if t0 <= ev_time(e) <= t1]
    gc_s = sum(duration_s(e['values']['duration']) for e in gcs)
    pause_s = sum(duration_s(e['values']['sumOfPauses']) for e in gcs)
    window = (t1 - t0).total_seconds()

    print('| metric | value |')
    print('|---|---|')
    print(f'| window wall time | {window:.0f} s |')
    print(f'| allocated (ThreadAllocationStatistics) | {alloc / 2**30:.1f} GiB = {alloc / steps / 2**20:.2f} MiB/step |')
    print(f'| allocated (ObjectAllocationSample weights) | {sampled / 2**30:.1f} GiB = '
          f'{sampled / steps / 2**20:.2f} MiB/step |')
    print(f'| GC collections | {len(gcs)} |')
    print(f'| GC time (collection duration, concurrent for ZGC) | {gc_s:.1f} s = {gc_s * 1000 / steps * 1000:.0f} ms '
          f'per 1,000 steps |')
    print(f'| GC pause time | {pause_s * 1000:.1f} ms = {pause_s * 1000 / steps * 1000:.2f} ms per 1,000 steps |')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--cases', nargs='+', required=True, help='case names, in run order')
    ap.add_argument('--jfr')
    ap.add_argument('--case', type=int, default=0, help='index of the case the JFR window covers')
    a = ap.parse_args()
    cases = parse_log(a.log)
    if a.jfr:
        print_jfr(a.jfr, cases[a.case])
    else:
        print_log(cases, a.cases)


if __name__ == '__main__':
    main()
