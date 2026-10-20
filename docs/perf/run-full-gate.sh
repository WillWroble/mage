#!/usr/bin/env bash
# Full gate (MCTSDeckValidationTest DECK_POOL + the commander case), one case per run so each stays short and a
# crash loses only its own case. Resumable: cases whose log already has a result are skipped.
#   docs/perf/run-full-gate.sh autotap|manual <run prefix>
# Logs go to OUT_DIR/<prefix>-<deck>.log; other variables as for run-gate.sh
set -uo pipefail
MODE=$1 PREFIX=$2
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/docs/perf/runs}
CASES="Standard16-UB Standard-MonoB Standard-MonoG Standard-MonoR Standard-MonoU Standard-MonoW Standard16-5C
Standard16-BW Standard16-GB Standard16-GW Standard16-RB Standard16-RG Standard16-RW Standard16-UG Standard16-UR
Standard16-UW Commander-MonoG"
for deck in $CASES; do
  run=$PREFIX-$deck
  grep -q "win rate" "$OUT_DIR/$run.log" 2>/dev/null && continue
  test=fullGate
  [[ $deck == Commander* ]] && test=fullGateCommander
  GATE_TEST=$test GATE_DECKS=$deck "$ROOT/docs/perf/run-gate.sh" "$MODE" "$run" > /dev/null 2>&1
done
