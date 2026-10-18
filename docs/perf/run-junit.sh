#!/usr/bin/env bash
# XMage JUnit suite as upstream CI runs it (.travis.yml), minus the MageZero gate (see run-gate.sh).
#   docs/perf/run-junit.sh <run name>
set -euo pipefail
RUN=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/docs/perf/runs}
mkdir -p "$OUT_DIR"
cd "$ROOT"
MAVEN_OPTS=-Xmx2g mvn test -B -Dxmage.dataCollectors.printGameLogs=false \
  -Dlog4j.configuration=file:$ROOT/.travis/log4j.properties \
  -Dmaven.test.failure.ignore=true -Dtest='!MCTSDeckValidationTest' -Dsurefire.failIfNoSpecifiedTests=false \
  2>&1 | tee "$OUT_DIR/$RUN.mvn.log"
