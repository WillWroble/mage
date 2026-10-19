#!/usr/bin/env bash
# Speed probe: the UB gate case, stopped after PROBE_MINUTES (default 5) of play.
# PROBE_MODE=manual runs it with manual tapping (default autotap).
# Prints sims/s over the window and live bytes per GameState from a class histogram at the end.
#   docs/perf/probe.sh <run name>
# Baseline (cfbfb733 engine): 50.0 / 48.3 sims/s over the first 5 minutes.
set -uo pipefail
RUN=$1 MINUTES=${PROBE_MINUTES:-5}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export OUT_DIR=${OUT_DIR:-$ROOT/docs/perf/runs}
LOG=$OUT_DIR/$RUN.log
rm -f "$LOG"
GATE_DECKS=${GATE_DECKS:-UB} "$ROOT/docs/perf/run-gate.sh" "${PROBE_MODE:-autotap}" "$RUN" > /dev/null 2>&1 &
GATE=$!

until grep -q "STARTING DATA GENERATION" "$LOG" 2>/dev/null; do
  kill -0 $GATE 2>/dev/null || { echo "gate exited before play started, see $OUT_DIR/$RUN.mvn.log"; exit 1; }
  sleep 2
done
sleep $((MINUTES * 60))

FORK=$(pgrep -f "^/usr/lib/jvm.*surefirebooter")
jcmd "$FORK" GC.class_histogram > "$OUT_DIR/$RUN.histo.txt" 2>/dev/null
kill "$FORK"
wait $GATE

python3 "$ROOT/docs/perf/summarize.py" "$LOG" --cases UB | sed -n 3p
awk '/ mage\.game\.GameState$/ {n=$2} /^Total/ {b=$3} END {if (n) printf "live heap %.2f GiB, %d GameStates, %.0f KiB per GameState\n", b/2^30, n, b/n/1024}' "$OUT_DIR/$RUN.histo.txt"
