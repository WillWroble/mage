#!/usr/bin/env bash
# One MageZero policy agent (Spellbench protocol v2, agent role) on stdin/stdout: the kit's front and, as its child,
# the runner (one JVM with this XMage). Adapted from Spellbench's engines/xmage/kit/scripts/agent.sh.
#
#   Mage.Spellbench/scripts/agent.sh --db DIR [--model-url URL] [--work DIR] [--xmx SIZE] [front options...]
#
# --db         a card database template (a directory named db, built once by CardScanner); each agent copies it
# --model-url  the MageZero model server (default http://127.0.0.1:50052), started separately:
#              python src/magezero/server.py --model <model dir> --port 50052   (WillWroble/MageZero)
# Front options (passed through): --entry mz (default) --model ID (bound into the bot version) --log FILE --log-dir DIR
#
# Build first: mvn -pl Mage.Spellbench -am package -DskipTests (target/mage-spellbench.jar and target/lib).
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
DB=""; WORK="${TMPDIR:-/tmp}"; XMX=2g; MODEL_URL="http://127.0.0.1:50052"
FRONT=(--entry mz)
while [ $# -gt 0 ]; do
  case "$1" in
    --db) DB="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --xmx) XMX="$2"; shift 2 ;;
    --model-url) MODEL_URL="$2"; shift 2 ;;
    *) FRONT+=("$1" "$2"); shift 2 ;;
  esac
done
[ -n "$DB" ] || { echo "need --db" >&2; exit 2; }
if [ -n "${KIT_LOG_DIR:-}" ]; then FRONT+=(--log-dir "$KIT_LOG_DIR"); fi

JAR="$HERE/target/mage-spellbench.jar"
mkdir -p "$WORK"
DIR=$(mktemp -d "$WORK/mz-agent-XXXXXX")
# the front's own cleanup can leave the directory behind (each holds a 250 MB database copy), so remove it here too
trap 'rm -rf "$DIR"' EXIT INT TERM
cp -R "$DB" "$DIR/db"
java -Xmx128m -cp "$JAR" spellbench.kit.core.Front \
  --work "$DIR" --work-root "$WORK" --cleanup-work 1 "${FRONT[@]}" \
  -- java -Xmx"$XMX" -XX:+UseSerialGC -Dspellbench.model.url="$MODEL_URL" -cp "$JAR:$HERE/target/lib/*" \
  spellbench.kit.xmage.Runner
