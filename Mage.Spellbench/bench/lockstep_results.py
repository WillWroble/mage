"""Results of lockstep benchmark games, from each game's record (lockstep-games.jsonl) under a results root laid out
<root>/<archon>/<deck>/<archon seat>-<first>/<game>/logs/: per Archon and opponent deck, the games, how many played to
a natural end in both engines, MageZero's wins, the Archon's wins and draws there, and the divergences (games stopped
where the engines disagreed, which have no result).

    python lockstep_results.py <root>
"""
import collections
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
rows = collections.defaultdict(collections.Counter)
for path in sorted(root.glob("*/*/*/*/logs/lockstep-games.jsonl")):
    archon, deck = path.parts[-6], path.parts[-5]
    record = json.loads(path.read_text().splitlines()[0])
    row = rows[(archon, deck)]
    row["games"] += 1
    if record["divergence"] is not None:
        row["divergences"] += 1
        continue
    row["full"] += 1
    winner = record["xmage_winner"]
    row["draws" if winner is None else "archon" if winner == record["archon_seat"] else "magezero"] += 1

print(f"{'archon':7} {'deck':9} {'games':>5} {'full':>5} {'MageZero':>8} {'Archon':>6} {'draws':>5} {'diverged':>8} "
      f"{'MZ win %':>8}")
total = collections.defaultdict(collections.Counter)
for (archon, deck), row in sorted(rows.items()):
    total[archon].update(row)
    decided = row["magezero"] + row["archon"]
    rate = f"{100 * row['magezero'] / decided:.0f}" if decided else "-"
    print(f"{archon:7} {deck:9} {row['games']:5} {row['full']:5} {row['magezero']:8} {row['archon']:6} "
          f"{row['draws']:5} {row['divergences']:8} {rate:>8}")
for archon, row in sorted(total.items()):
    decided = row["magezero"] + row["archon"]
    rate = f"{100 * row['magezero'] / decided:.0f}" if decided else "-"
    print(f"{archon:7} {'all':9} {row['games']:5} {row['full']:5} {row['magezero']:8} {row['archon']:6} "
          f"{row['draws']:5} {row['divergences']:8} {rate:>8}")
