#!/usr/bin/env python3
"""Summarize an MCTSDeckValidationTest run log (written via log4j-perf.properties), optionally with a JFR recording.

  summarize.py run.log --cases UB 5C UR GW
  summarize.py run.log --cases UB --jfr run.jfr --case 0
  summarize.py junit.mvn.log --junit [--failed-out failed.txt]
"""
import argparse
import json
import os
import re
import subprocess
from collections import Counter, defaultdict
from datetime import datetime, timezone

LINE = re.compile(r'^(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\s+(\S+ \S+)\s(.*?)\s*=>\[([^\]]*)\]\s*(\S*)')
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
SEARCH, SEARCH_FRAME = 'search', 'mage.player.ai.ComputerPlayerMCTS2.applyMCTS('


def norm(msg):
    msg = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', msg)
    msg = re.sub(r'\d+(\.\d+)?', '#', msg)
    return msg[:160]


def parse_log(path):
    cases = []
    stopped = {}  # thread -> stop reason of its search in progress
    for line in open(path, errors='replace'):
        m = LINE.match(line)
        if not m:
            continue
        level, ts, msg, thread, where = m.groups()
        if 'STARTING DATA GENERATION' in msg:
            cases.append(dict(start=ts, end=None, sims=0, secs=0.0, moves=0, capped_secs=0.0, capped_sims=0,
                              stops=Counter(), errors=Counter(),
                              wins=None, games=None))
        if not cases:
            continue
        c = cases[-1]
        mv = MOVE.search(msg)
        if mv:
            c['sims'] += int(mv.group(2))
            c['secs'] += float(mv.group(3))
            c['moves'] += 1
            if stopped.pop(thread, None) == 'force':
                c['capped_sims'] += int(mv.group(2))
                c['capped_secs'] += float(mv.group(3))
        for k, v in STOPS.items():
            if k in msg:
                c['stops'][v] += 1
                stopped[thread] = v
        wr = WR.search(msg)
        if wr:
            c['wins'], c['games'] = int(wr.group(1)), int(wr.group(2))
            c['end'] = ts
        if level in ('ERROR', 'FATAL'):
            c['errors'][f'{level} {where}: {norm(msg)}'] += 1
    return cases


def rate(sims, secs):
    return sims / secs if secs else 0


def print_log(cases, names):
    print('| case | games | win rate | sims | search s | sims/s | sims/s excl. 300 s cap | stops |')
    print('|---|---|---|---|---|---|---|---|')
    tot_sims = tot_secs = cap_sims = cap_secs = 0
    for name, c in zip(names, cases):
        wr = f"{c['wins']}/{c['games']} ({100.0 * c['wins'] / c['games']:.1f}%)" if c['games'] else 'n/a'
        stops = ', '.join(f'{k} {v}' for k, v in sorted(c['stops'].items()))
        print(f"| {name} | {c['games']} | {wr} | {c['sims']} | {c['secs']:.0f} | {rate(c['sims'], c['secs']):.1f} | "
              f"{rate(c['sims'] - c['capped_sims'], c['secs'] - c['capped_secs']):.1f} | {stops} |")
        tot_sims += c['sims']
        tot_secs += c['secs']
        cap_sims += c['capped_sims']
        cap_secs += c['capped_secs']
    print(f"| **all** | | | {tot_sims} | {tot_secs:.0f} | {rate(tot_sims, tot_secs):.1f} | "
          f"{rate(tot_sims - cap_sims, tot_secs - cap_secs):.1f} | |")
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


def integrate_load(events, key, fields):
    """CPU-seconds from periodic load events (fractions of the whole machine), grouped by key(event)."""
    cores = os.cpu_count()
    by_key = defaultdict(list)
    for e in events:
        by_key[key(e)].append(e)
    total = 0.0
    for evs in by_key.values():
        evs.sort(key=ev_time)
        for prev, cur in zip(evs, evs[1:]):
            dt = (ev_time(cur) - ev_time(prev)).total_seconds()
            total += sum(cur['values'][f] for f in fields) * cores * dt
    return total


def print_jfr(path, case):
    t0, t1 = log_time(case['start']), log_time(case['end'])
    steps = case['sims']
    search_ms_per_step = case['secs'] * 1000.0 / steps
    window = (t1 - t0).total_seconds()
    in_window = lambda e: t0 <= ev_time(e) <= t1

    # inclusive buckets over execution samples; per-step cost is taken relative to samples inside the search
    samples = searching = 0
    hits, search_hits = Counter(), Counter()
    proc = subprocess.Popen(['jfr', 'print', '--events', 'jdk.ExecutionSample', '--stack-depth', '4096', path],
                            stdout=subprocess.PIPE, text=True)
    inside, seen = False, set()
    for line in proc.stdout:
        s = line.strip()
        if s.startswith('startTime ='):
            hms, day = re.match(r'startTime = (\S+) \((\S+)\)', s).groups()
            t = datetime.fromisoformat(f'{day}T{hms}').replace(tzinfo=timezone.utc)
            inside, seen = t0 <= t <= t1, set()
        elif inside and s == '}':
            samples += 1
            hits.update(seen - {SEARCH})
            if SEARCH in seen:
                searching += 1
                search_hits.update(seen - {SEARCH})
        elif inside:
            if s.startswith(SEARCH_FRAME):
                seen.add(SEARCH)
            for label, prefix in HOT.items():
                if s.startswith(prefix):
                    seen.add(label)
    proc.wait()

    print(f'Window {case["start"]} .. {case["end"]} ({window:.0f} s), {steps} simulation steps, '
          f'{search_ms_per_step:.1f} ms search time per step')
    print(f'{samples} execution samples in the window, {searching} ({100.0 * searching / samples:.1f}%) inside '
          f'ComputerPlayerMCTS2.applyMCTS')
    print()
    print('| inclusive bucket | share of all samples | share of search samples | est. ms/step |')
    print('|---|---|---|---|')
    for label in HOT:
        share = search_hits[label] / searching
        print(f'| {label} | {100.0 * hits[label] / samples:.1f}% | {100.0 * share:.1f}% | '
              f'{share * search_ms_per_step:.2f} |')
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

    gcs = [e for e in jfr_json(path, 'jdk.GarbageCollection') if in_window(e)]
    cycles = Counter(e['values']['name'] for e in gcs)
    cycle_s = defaultdict(float)
    for e in gcs:
        cycle_s[e['values']['name']] += duration_s(e['values']['duration'])
    pause_s = sum(duration_s(e['values']['sumOfPauses']) for e in gcs)

    jvm_cpu = integrate_load([e for e in jfr_json(path, 'jdk.CPULoad') if in_window(e)],
                             lambda e: 0, ['jvmUser', 'jvmSystem'])
    java_cpu = integrate_load([e for e in jfr_json(path, 'jdk.ThreadCPULoad') if in_window(e)],
                              lambda e: e['values']['eventThread']['javaThreadId'], ['user', 'system'])
    other_cpu = jvm_cpu - java_cpu

    print('| metric | value |')
    print('|---|---|')
    print(f'| allocated (ThreadAllocationStatistics) | {alloc / 2**30:.0f} GiB = {alloc / steps / 2**20:.1f} MiB/step |')
    for name, n in sorted(cycles.items()):
        print(f'| {name} cycles | {n}, {cycle_s[name]:.0f} s total ({100.0 * cycle_s[name] / window:.0f}% of the window) |')
    print(f'| GC pause time | {pause_s * 1000:.0f} ms = {pause_s * 1e6 / steps:.2f} ms per 1,000 steps |')
    print(f'| JVM CPU (CPULoad) | {jvm_cpu:.0f} s = {jvm_cpu * 1e6 / steps:.0f} ms per 1,000 steps |')
    print(f'| Java threads CPU (ThreadCPULoad) | {java_cpu:.0f} s |')
    print(f'| GC + other non-Java JVM threads CPU (difference) | {other_cpu:.0f} s = '
          f'{other_cpu * 1e6 / steps:.0f} ms per 1,000 steps ({100.0 * other_cpu / jvm_cpu:.0f}% of JVM CPU) |')


MODULE = re.compile(r'^\[INFO\] Building (.+?) [\d.]+\s+\[\d+/\d+\]')
TOTALS = re.compile(r'^\[(?:INFO|WARNING|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$')
FAILED = re.compile(r'^\[ERROR\]   (\S+?)(?::\d+)?(?:->\S+)? (.*)$')


def print_junit(path, failed_out):
    module, totals, causes, failed = None, [], Counter(), []
    in_results = False
    for line in open(path, errors='replace'):
        line = line.rstrip('\n')
        m = MODULE.match(line)
        if m:
            module = m.group(1)
        m = TOTALS.match(line)
        if m:
            totals.append((module, *map(int, m.groups())))
        if line in ('[ERROR] Failures: ', '[ERROR] Errors: '):
            in_results = True  # entries until the next plain Maven line; messages may span lines
            continue
        if line.startswith(('[INFO]', '[WARNING]', '[ERROR] Tests run')):
            in_results = False
        m = FAILED.match(line) if in_results else None
        if m:
            failed.append(f'{module}: {m.group(1)}')
            causes[norm(m.group(2)).strip()[:110]] += 1
    print('| module | tests | failures | errors | skipped |')
    print('|---|---|---|---|---|')
    for row in totals:
        print('| ' + ' | '.join(map(str, row)) + ' |')
    print()
    print('| failing tests by cause (normalized, top 25) | count |')
    print('|---|---|')
    for cause, n in causes.most_common(25):
        print(f"| `{cause.replace('|', '/')}` | {n} |")
    print(f'| all causes | {sum(causes.values())} |')
    if failed_out:
        open(failed_out, 'w').write('\n'.join(sorted(failed)) + '\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--cases', nargs='+', help='case names, in run order')
    ap.add_argument('--junit', action='store_true', help='log is a run-junit.sh Maven log')
    ap.add_argument('--failed-out', help='with --junit: write the sorted failing test ids here')
    ap.add_argument('--jfr')
    ap.add_argument('--case', type=int, default=0, help='index of the case the JFR window covers')
    a = ap.parse_args()
    if a.junit:
        print_junit(a.log, a.failed_out)
        return
    cases = parse_log(a.log)
    if a.jfr:
        print_jfr(a.jfr, cases[a.case])
    else:
        print_log(cases, a.cases)


if __name__ == '__main__':
    main()
