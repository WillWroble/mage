#!/usr/bin/env bash
# Fast gate: MCTSDeckValidationTest#fastGate in one tapping mode. Log (and optional JFR) go to OUT_DIR.
#   docs/perf/run-gate.sh autotap|manual <run name> [jfr]
# GATE_DECKS=UB (comma-separated) runs only those cases; default all four
set -euo pipefail
MODE=$1 RUN=$2 JFR=${3:-}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/docs/perf/runs}
YML=$ROOT/Mage.Tests/config/config.yml
mkdir -p "$OUT_DIR" "$ROOT/Mage.Tests/data/playerA" "$ROOT/Mage.Tests/data/playerB"  # HDF5 output dirs

TAP=false; [ "$MODE" = manual ] && TAP=true
cp "$YML" "$OUT_DIR/config.yml.bak"
trap 'mv "$OUT_DIR/config.yml.bak" "$YML"' EXIT
sed -i "s/manual_tapping: .*/manual_tapping: $TAP/" "$YML"

# surefire runs with -ea; jhdf5's own asserts reject LabeledStateWriter's offset arrays (block > size), so skip them
JVM="-Dfile.encoding=UTF-8 -Xmx10g -XX:+UseZGC -XX:+ZGenerational -da:ch.systemsx.cisd..."
JVM="$JVM -Dlog4j.configuration=file:$ROOT/docs/perf/log4j-perf.properties -Dperf.log=$OUT_DIR/$RUN.log"
JVM="$JVM -Dgate.decks=${GATE_DECKS:-}"
if [ -n "$JFR" ]; then
  JVM="$JVM -XX:FlightRecorderOptions:stackdepth=2048"
  JVM="$JVM -XX:StartFlightRecording=settings=profile,filename=$OUT_DIR/$RUN.jfr,jdk.ThreadAllocationStatistics#period=1s"
fi

# Mage.MageZero skips install, so Mage.Tests runs inside the reactor
cd "$ROOT"
mvn -B test -pl Mage.Tests -am -Dtest='MCTSDeckValidationTest#fastGate' -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="$JVM" 2>&1 | tee "$OUT_DIR/$RUN.mvn.log"
