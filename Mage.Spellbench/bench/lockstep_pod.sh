# Lockstep games (MageZero in XMage, an Archon in mtg-kernel) on a RunPod CPU pod started by bench/launch.py: run
# from the pod's MageZero checkout (for runpod/java.sh and the model server), with this repo cloned at $MAGE_COMMIT in
# /tmp/mage. Builds are cached on the volume (/workspace/spellbench). Each (deck, Archon seat, first player) cell is
# one JVM of MageZero's generator playing $GAMES games. Results go to $OUT: lockstep-games.jsonl, summary.txt, logs.tar.gz, the server log.
#
# Env: MAGE_COMMIT, OUT (launch.py), GAMES (per cell, default 4), THREADS (games at once per cell, default GAMES),
# DECKS (default all eight), MIRROR (1: MageZero pilots the Archon's deck; else Faeries), SHARDS (cells at once,
# default vCPUs / THREADS).
set -ex
M=/tmp/mage
C=/workspace/spellbench
SB=/tmp/spellbench
A=$C/archons
DECKS=${DECKS:-Affinity Burn CawGates Elves Faeries Rally Spy Wildfire}
GAMES=${GAMES:-4}
THREADS=${THREADS:-$GAMES}
SHARDS=${SHARDS:-$(( $(nproc) / THREADS ))}
mkdir -p $C
apt-get install -y -qq curl git build-essential pkg-config >/dev/null
source runpod/java.sh
lscpu > $OUT/cpu.txt

# Python with CPU torch (the model server) and Spellbench at the Archons' commit with the lockstep patch
curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null && export PATH=$HOME/.local/bin:$PATH
uv venv -q --python 3.12 /tmp/venv && . /tmp/venv/bin/activate
uv pip install -q torch --index-url https://download.pytorch.org/whl/cpu
uv pip install -q numpy h5py flask waitress msgpack pyyaml
git clone -q https://github.com/jackmaiorino/spellbench $SB && git -C $SB checkout -q 27c9009
git -C $SB apply $M/Mage.Spellbench/kernel/spellbench-27c9009.patch
uv pip install -q -e $SB

# mtg-kernel at the Archons' rated commit with the lockstep patch, built once per patch (16 codegen units: one needs
# more than 16 GB)
K=$C/mtg-kernel-lockstep-$(sha256sum $M/Mage.Spellbench/kernel/mtg-kernel-92ec2393.patch | cut -c1-12)
if [ ! -x $K/target/release/agent_bridge_v1 ]; then
  curl -sSf https://sh.rustup.rs | sh -s -- -y -q --profile minimal --default-toolchain none
  git clone -q https://github.com/jackmaiorino/mtg-kernel /tmp/mtg-kernel
  (cd /tmp/mtg-kernel && git checkout -q 92ec2393db34bea805a85991163458b9ff890f57 \
    && git apply $M/Mage.Spellbench/kernel/mtg-kernel-92ec2393.patch && . $HOME/.cargo/env \
    && CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16 cargo build -q --release --locked -p mtg-kernel \
       --bin agent_bridge_v1 --bin spellbench_scorer_v1 --bin spellbench_kernel_catalog \
    && rm -rf $K.tmp && mkdir -p $K.tmp/target/release && target/release/spellbench_kernel_catalog > $K.tmp/pauper-v2-catalog.json \
    && cp target/release/{agent_bridge_v1,spellbench_scorer_v1} $K.tmp/target/release/ && mv $K.tmp $K)
fi
echo "af8e1b914d76ed6d30318403c6d434fc4d3a651a68d7e37c79c9d09c68cc457e  $K/pauper-v2-catalog.json" | sha256sum -c

# Archon 3 (g115; MIT, huggingface.co/mtg-kernel) checked against its sums, with its scorer config for this machine
r=archon-3-pauper
if [ ! -d $A/$r ]; then
  for f in $(curl -sS https://huggingface.co/api/models/mtg-kernel/$r | python -c \
      "import sys,json;print(' '.join(s['rfilename'] for s in json.load(sys.stdin)['siblings']))"); do
    mkdir -p $(dirname $A/$r/$f) && curl -sSL -o $A/$r/$f https://huggingface.co/mtg-kernel/$r/resolve/main/$f
  done
fi
(cd $A/$r && sha256sum -c SHA256SUMS)
python - $A/$r <<'EOF'
import hashlib, json, pathlib, sys
w = pathlib.Path(sys.argv[1]) / "weights"; out = w.parent / "local"; out.mkdir(exist_ok=True)
imp = json.loads((w / "play-import-b.json").read_text())
imp["initialization"]["path"] = (w / "init-generated-b/initialization.json").as_posix()
imp["parameters"]["path"] = (w / "init-generated-b/parameters.f32le").as_posix()
(out / "play-import-b.json").write_text(json.dumps(imp, separators=(",", ":")))
cfg = json.loads((w / "g115.scorer.json").read_text())
cfg["source"]["play_import"] = {"path": (out / "play-import-b.json").as_posix(),
                                "sha256": hashlib.sha256((out / "play-import-b.json").read_bytes()).hexdigest()}
cfg["source"]["checkpoint"]["path"] = next(p for p in w.glob("*.json") if len(p.stem) == 64).as_posix()
(out / "g115.scorer.json").write_text(json.dumps(cfg, indent=2))
EOF

# ver15
MODEL=/tmp/model && mkdir -p $MODEL
curl -sSL -o $MODEL/model.pt.gz https://huggingface.co/inkling616/MZ-Pauper-Faeries-v15/resolve/main/model.pt.gz
sha256sum $MODEL/model.pt.gz | tee $OUT/model.sha256

# the lockstep at $MAGE_COMMIT, once per commit
G=$C/lockstep/$MAGE_COMMIT
if [ ! -f $G/mage-spellbench.jar ]; then  # copied whole, then renamed: never half-written
  (cd $M && MAVEN_OPTS=-Xmx16g mvn -B -q -T 1C package -DskipTests -Dmaven.repo.local=/workspace/m2 -pl Mage.Spellbench -am)
  rm -rf $G.tmp && mkdir -p $G.tmp && cp -r $M/Mage.Spellbench/target/mage-spellbench.jar $M/Mage.Spellbench/target/lib $G.tmp/ \
    && rm -rf $G && mv $G.tmp $G
fi

python -u src/magezero/server.py --model $MODEL --port 50052 2>&1 | grep -v "^\[REQ\|^\[BATCH" > $OUT/server.log &
until curl -sf localhost:50052/healthz; do sleep 2; done

# the matchups' fixed kernel setups, the engine and Archon commands, MageZero's config
R=/tmp/run && mkdir -p $R/setups
for d in $DECKS; do
  for pair in "$d Faeries" "Faeries $d" "$d $d"; do
    set -- $pair
    python $M/Mage.Spellbench/bench/lockstep_setup.py $SB $K/pauper-v2-catalog.json $1 $2 > $R/setups/$1-$2.json
  done
done
python - $SB $K $A/$r > $R/settings.json <<'EOF'
import json, sys
sb, k, archon = sys.argv[1:4]
print(json.dumps({
    "engine": ["/tmp/venv/bin/python", f"{sb}/integrations/mtg_kernel/kernel_engine_v2.py", "--bridge",
               f"{k}/target/release/agent_bridge_v1", "--catalog", f"{k}/pauper-v2-catalog.json",
               "--source-revision", "92ec2393db34bea805a85991163458b9ff890f57"],
    "archon": ["/tmp/venv/bin/python", f"{sb}/integrations/mtg_kernel/kernel_flat_bot.py", "--scorer",
               f"{k}/target/release/spellbench_scorer_v1", "--config", f"{archon}/local/g115.scorer.json",
               "--name", "g115", "--version", "pauper-v2.0.0", "--expect-model-state",
               "8139016ca561961714f25e22a9d6f7fc888548fc332e45b6bce402dfc43159f2"]}))
EOF
cat > $R/config.yml <<EOF
goes_first: FIRST
player_a:
  deckPath: $M/Mage.MageZero/decks/Pauper-MZDECK.txt
  type: on_policy
  priors: {priority: true, target: true, binary: true, opponent: false, prior_temperature: 1.5}
  noise: {enabled: false}
  gameplay: {mulligans_enabled: false, manual_tapping: false}
  hiddenInfo: {see_opponent_hand: false}
player_b:
  deckPath: $M/Mage.MageZero/decks/Pauper-DECK.txt
  type: spellbench
  gameplay: {mulligans_enabled: false, manual_tapping: false}
training: {games: GAMES, threads: THREADS, max_turns: 100, max_minutes: 60}
server: {host: localhost, port: 50052}
logging: {save_final_wr: false}
EOF

# one cell: a JVM playing its games, $THREADS at once, as MageZero's generator does (its own card database, model
# data and JVM temp files)
cell() {
  local deck=$1 seat=$2 first=$3 mz=Faeries setup
  [ "${MIRROR:-}" = 1 ] && mz=$deck
  if [ $seat = p1 ]; then setup=$R/setups/$mz-$deck.json; else setup=$R/setups/$deck-$mz.json; fi
  local d=$R/games/g115/$deck/$seat-$first/run
  mkdir -p $d/data/playerA $d/data/playerB $d/tmp && cd $d
  python -c "import json;s=json.load(open('$R/settings.json'));s.update(logs='$d/logs',seat='$seat',setup='$setup');json.dump(s,open('settings.json','w'))"
  sed -e "s#FIRST#$first#; s#MZDECK#$mz#; s#Pauper-DECK#Pauper-$deck#; s#GAMES#$GAMES#; s#THREADS#$THREADS#" \
    $R/config.yml > config.yml
  java -Xmx8g -Djava.io.tmpdir=$d/tmp -Dspellbench.lockstep=settings.json -cp "$G/mage-spellbench.jar:$G/lib/*" \
    org.mage.spellbench.SpellbenchMain config.yml > java.log 2>&1 || echo "$deck $seat $first exit $?" >> $R/failed.txt
  rm -rf db data tmp
}
export -f cell; export R G MIRROR GAMES THREADS

(while sleep 60; do cat $R/games/g115/*/*/run/logs/lockstep-games.jsonl > $OUT/games-so-far.jsonl 2>/dev/null || true; done) &
for d in $DECKS; do for s in "p1 player_a" "p0 player_b" "p1 player_b" "p0 player_a"; do echo "$d $s"; done; done \
  | xargs -P $SHARDS -L 1 bash -c 'cell "$@"' _
cat $R/games/g115/*/*/run/logs/lockstep-games.jsonl > $OUT/lockstep-games.jsonl
python $M/Mage.Spellbench/bench/lockstep_results.py $R/games > $OUT/summary.txt
cp $R/failed.txt $OUT/ 2>/dev/null || true
tar czf $OUT/logs.tar.gz -C $R games --exclude='*.h2.*'
