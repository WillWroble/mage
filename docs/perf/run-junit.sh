#!/usr/bin/env bash
# XMage engine tests (Mage.Tests and the modules it builds on) with upstream CI's flags (.travis.yml),
# minus the MageZero gate (see run-gate.sh).
#   docs/perf/run-junit.sh <run name>
set -euo pipefail
RUN=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/docs/perf/runs}
mkdir -p "$OUT_DIR"
cd "$ROOT"
MAVEN_OPTS=-Xmx2g mvn test -B -pl Mage.Tests -am -Dxmage.dataCollectors.printGameLogs=false \
  -Dlog4j.configuration=file:$ROOT/.travis/log4j.properties \
  -Dmaven.test.failure.ignore=true -Dtest='!MCTSDeckValidationTest' -Dsurefire.failIfNoSpecifiedTests=false \
  2>&1 | tee "$OUT_DIR/$RUN.mvn.log"
