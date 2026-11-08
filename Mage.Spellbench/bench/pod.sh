# MageZero's policy agent vs the Archon panel (a48, c12, g115) on mtg-kernel, on a RunPod CPU pod. Run from the
# pod's MageZero checkout (graph-encoder-test) by its runpod/spellbench.sh, with this repo cloned at $MAGE_COMMIT in
# /tmp/mage. Builds are cached on the volume (/workspace/spellbench). Results go to $OUT: games.jsonl, the config,
# the agent's decision logs (logs.tar.gz) and the model server's log.
#
# Env: MAGE_COMMIT, OUT (launch.py), PAIRS (seat-swapped pairs per Archon, default 32), SHARDS (games at once,
# default vCPUs / 4), SPELLBENCH_COMMIT (default 27c9009), MODEL_REPO (Hugging Face, default ver15).
set -ex
M=/tmp/mage
C=/workspace/spellbench
SB=/tmp/spellbench
K=$C/mtg-kernel-92ec2393
A=$C/archons
PAIRS=${PAIRS:-32}
SHARDS=${SHARDS:-$(( $(nproc) / 4 ))}
mkdir -p $C
apt-get install -y -qq curl git build-essential pkg-config >/dev/null
source runpod/java.sh
lscpu > $OUT/cpu.txt; free -g > $OUT/mem.txt

# Python 3.12 with CPU torch (the model server) and Spellbench (host, live validator, the Archons' bot)
curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null && export PATH=$HOME/.local/bin:$PATH
uv venv -q --python 3.12 /tmp/venv && . /tmp/venv/bin/activate
uv pip install -q torch --index-url https://download.pytorch.org/whl/cpu
uv pip install -q numpy h5py flask waitress msgpack pyyaml
git clone -q https://github.com/jackmaiorino/spellbench $SB && git -C $SB checkout -q ${SPELLBENCH_COMMIT:-27c9009}
uv pip install -q -e $SB

# mtg-kernel at the Archons' rated commit, once per volume (16 codegen units: one needs more than 16 GB)
if [ ! -x $K/target/release/agent_bridge_v1 ]; then
  curl -sSf https://sh.rustup.rs | sh -s -- -y -q --profile minimal --default-toolchain none
  rm -rf $K && git clone -q https://github.com/jackmaiorino/mtg-kernel $K
  (cd $K && git checkout -q 92ec2393db34bea805a85991163458b9ff890f57 && . $HOME/.cargo/env \
    && CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16 cargo build -q --release --locked -p mtg-kernel \
       --bin agent_bridge_v1 --bin spellbench_scorer_v1 --bin spellbench_kernel_catalog \
    && target/release/spellbench_kernel_catalog > pauper-v2-catalog.json)
fi
echo "af8e1b914d76ed6d30318403c6d434fc4d3a651a68d7e37c79c9d09c68cc457e  $K/pauper-v2-catalog.json" | sha256sum -c

# the Archons (MIT, huggingface.co/mtg-kernel) and the model, checked against their sums
for r in archon-1-pauper archon-2-pauper archon-3-pauper; do
  if [ ! -d $A/$r ]; then
    for f in $(curl -sS https://huggingface.co/api/models/mtg-kernel/$r | python -c \
        "import sys,json;print(' '.join(s['rfilename'] for s in json.load(sys.stdin)['siblings']))"); do
      mkdir -p $(dirname $A/$r/$f) && curl -sSL -o $A/$r/$f https://huggingface.co/mtg-kernel/$r/resolve/main/$f
    done
  fi
  (cd $A/$r && sha256sum -c SHA256SUMS)
done
MODEL=/tmp/model && mkdir -p $MODEL
curl -sSL -o $MODEL/model.pt.gz https://huggingface.co/${MODEL_REPO:-inkling616/MZ-Pauper-Faeries-v15}/resolve/main/model.pt.gz
sha256sum $MODEL/model.pt.gz | tee $OUT/model.sha256

# the agent at $MAGE_COMMIT, once per commit, and this pod's own card database template
G=$C/agent/$MAGE_COMMIT
if [ ! -f $G/target/mage-spellbench.jar ]; then
  (cd $M && MAVEN_OPTS=-Xmx16g mvn -B -q -T 1C package -DskipTests -Dmaven.repo.local=/workspace/m2 -pl Mage.Spellbench -am)
  mkdir -p $G && cp -r $M/Mage.Spellbench/target $G/
fi
rm -rf $M/Mage.Spellbench/target && cp -r $G/target $M/Mage.Spellbench/
mkdir -p /tmp/cards && (cd /tmp/cards && java -Xmx4g -cp "$M/Mage.Spellbench/target/mage-spellbench.jar:$M/Mage.Spellbench/target/lib/*" \
  spellbench.kit.xmage.CardDb)

python -u src/magezero/server.py --model $MODEL --port 50052 2>&1 | grep -v "^\[REQ\|^\[BATCH" > $OUT/server.log &
until curl -sf localhost:50052/healthz; do sleep 2; done

# the games, written on the container disk and copied to the volume at the end
R=/tmp/run && mkdir -p $R/work
python $M/Mage.Spellbench/bench/archon.py --spellbench $SB --kernel $K --archons $A --db /tmp/cards/db --work $R/work \
  --out $R --pairs $PAIRS > $R/config.json
cp $R/config.json $OUT/
(while sleep 60; do grep -h '^{"bot_p0"' $R/run.log > $OUT/games-so-far.jsonl 2>/dev/null || true; done) &
SPELLBENCH=$SB python $M/Mage.Spellbench/bench/drive.py $R/config.json $R/games.jsonl --shards $SHARDS > $R/run.log 2>&1
cp $R/games.jsonl $R/run.log $OUT/
tar czf $OUT/logs.tar.gz -C $R logs
