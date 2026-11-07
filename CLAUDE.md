# CLAUDE.md — spell-bench

This branch makes XMage usable with [Spellbench](https://github.com/jackmaiorino/spellbench), an open benchmark that
rates Magic bots by Elo, so that MageZero can eventually be rated against other bots on a public scale.
MageZero is Will Wroble's AlphaZero-style system for Magic on this XMage fork: the search (`ComputerPlayerMCTS2`),
the state encoder and the data generator here are his design, and the networks live in `WillWroble/MageZero`.
Read this file and the Spellbench v2 spec before touching code.

## Why

Spellbench's `pauper-kernel` board (jackmaiorino.github.io/spellbench/b/pauper-kernel) uses the same 8 Pauper decks
MageZero trains on (Affinity, Burn, CawGates, Elves, Faeries, Rally, Spy, Wildfire). Its entries today are RL policy
nets without search ("Archon", 1226–1388 Elo) and scripted baselines (heuristic 1102, random 1000 anchor, first 867).
MageZero has never been compared with any of them: there is no common engine. For reference, inside XMage the
ver15 Faeries model wins ≈49% against XMage's offline heuristic MCTS (300 sims) with its policy alone and ≈67% with
value search, and XMage's MAD bot is ≈10 points weaker than that offline MCTS.

## How to work with Will

- Read the code, configs and the MageZero repo before asking. If the answer is in `config.yml`, an encoder class or a
  results file, find it yourself. Only ask about decisions the code can't settle, all at once, in plain text (not
  question widgets), short and numbered, with your recommendation. Then act on the answer.
- Never write messages or posts on Will's behalf (to Jack, Discord, GitHub). If something needs saying to someone,
  tell Will what needs saying and why.
- Settled decisions for this branch (don't re-ask):
  1. The MageZero bot must be **engine-neutral**: a Spellbench agent that rebuilds its input from the v2 board view,
     so it can play on `pauper-kernel` against Archon, not only inside XMage.
  2. First bot: **policy only, greedy** (`ComputerPlayerPolicyOnly` with `noise.enabled: false`, top-scored legal
     move, no search). Break ties deterministically (candidate order), never by `HashMap` order.
  3. Spellbench hides the opponent's hand: run with `hiddenInfo.see_opponent_hand: false` (the encoder then encodes
     only the hand size, `StateEncoder.perfectInfo`). The models were trained with it visible; report the loss.
  4. Serve the model from the MageZero repo, branch `graph-encoder-test`:
     `python src/magezero/server.py --model experiment_models/Pauper-Faeries/ver15 --port 50052`.

## MageZero: what exists (from `WillWroble/MageZero`, `results/`)

**Pipeline.** XMage (this fork) plays games with `ComputerPlayerMCTS2` (async MCTS, virtual loss, `max_pending`
evaluations in flight per tree). Each state is encoded by `StateEncoder` into a graph (typed internal nodes: ROOT,
PLAYER, ZONE, STACK_OBJECT, PERMANENT, CARD, ABILITY; hashed leaf features; labelled child→parent edges) and sent
by `RemoteModelEvaluator` (msgpack, raw int32 arrays plus per-node subtree hashes) to the Python server, which
batches requests and caches local-layer outputs by subtree hash. Games are written to h5 shards
(`data/{deck}/ver{N}/...`, one file per seat and matchup) with per-state MCTS visit counts (priority, target,
choose-use) and value labels (TD-blended, discount 0.92, toward ±1 at the end). Python trains on them.

**Network.** `NetGraph` (9.1M params): d256, 4 heads, FF 512, 2 bottom-up local passes (one attention layer per node
type, ABILITY → … → ROOT), then a 2-layer global transformer over [CLS, internal nodes]. Heads: per-node priority and
target logits, CLS choose-use and value (tanh, MSE). Policy loss is over the **legal candidates only**. A GNN beats
an MLP of equal size and the MLP in play at every generation (`results/gnn-vs-mlp-summary.md`).

**Search settings behind every result.** Value-only MCTS (priors off), 300 simulations, `c_puct` 1.0,
`max_pending` 4, the opponent's hand visible, XMage `f2ce8eca`. Opponent = XMage's offline heuristic MCTS
(`offline_mode: true`, `GameStateEvaluator3`), also 300 sims. 200 games per matchup (±3.5 points).

**Data versions (Pauper-Faeries).** `ver4` is the current data line (v0.3 encoder: passed-combat flags, only playable
activated abilities). Generations: gen0 = heuristic search vs heuristic; gen1 = games of model v4; gen2 = ver7/ver10;
gen3 = ver13; each generation plays the offline search, the earlier models and itself (self-play). Decks: Faeries is
always the model's deck; opponents are the 8 Pauper decks (`decks/Pauper-*.txt`: Affinity, Burn, CawGates, Elves,
Faeries, Rally, Spy, Wildfire) and 5 Standard mono decks. `ver1`–`ver3`, `ver5` and UWTempo `ver30/31` are older.

**Models** (`experiment_models/Pauper-Faeries/verN/model.pt.gz`):
- v4 (`ver4`): gen1 model, trained on gen0.
- ver7: gen2, gen0 + gen1. ver10: gen2 without gen0 (weaker).
- ver13: gen3, warm start from v4, gen0–gen2, lr 3e-5, 3 epochs. First model to beat v4.
- ver14: gen4 candidate, warm start from ver13, gen0–gen3 (4.37M states), lr 3e-5, 3 epochs. Overfit by data order.
- **ver15: current best.** Same recipe as ver14 (seed 0, player names kept, legal-only loss, TD 0.92), different data
  order. Will expects to replace it.

**ver15's strength (Faeries vs the offline search, 200 games):** with search: mirror 72.0%, Burn 68.5%, MonoB 36.0%,
MonoW 53.5%, Rally 31.0%, MonoG 32.0% (48.8% mean, +2.3 over ver13). Policy only, greedy: Pauper pool mean 48.8%
(Affinity 66.5, Burn 45.5, CawGates 67.0, Elves 55.3, mirror 39.0, Rally 13.5, Spy 55.0), about level with the
heuristic search and ≈19 points below search; Mono decks mean ≈24% (`results/onpolicy.md`). 1000 sims instead of
300: MonoB 44.6%. Priority priors don't help yet.

**Training regime and lessons.** Warm start from the previous model at lr 3e-5, legal-only policy loss, MSE value,
all generations in the buffer (dropping old generations hurt play), every 5th game held out per block, validation
on every block every 10K steps (`train.py --val-every`). Small held-out value-loss differences (≈.004) moved win
rate by ≈6 points. Training value and policy jointly beats either alone.

**In progress (Will's direction).** Gen5: one model for the whole Pauper format, starting from ver15's weights. Its
first data: ver15 piloting each of the 8 Pauper decks against the offline search of each (8×8×200 games, running on
RunPod), then self-play on all 36 matchups. So expect multi-deck models; don't assume Faeries-only.

## The spec (read first)

- `spec/SPELLBENCH_PROTOCOL_V2.md` in the Spellbench repo: stdio NDJSON, one decision at a time, the engine poses
  authoritative legal candidates and a viewer-only board view, the host validates everything.
  - §2–4 transport and strict JSON (no floats anywhere, ints only, unknown fields rejected by engines).
  - §5 object ids (opaque, per viewer, fresh on zone change, HMAC from `game_secret`).
  - §6 observation (opponent hand `null`, library/hand knowledge only through `known`).
  - §7–8 candidate kinds and decision groups (`pass` is candidate 0 when legal; at most 4096 candidates;
    no dead ends, or declare `rewind`).
  - §9 the environment role: `hello`, `reset`, `step`, `validate_deck`, the step-validation order and error codes.
  - §11 host, seeds and determinism (all randomness from `game_secret`, ≥64-bit streams per seat and purpose).
  - §13 fairness (nothing hidden may leak through `known`, text, labels or extensions; decision shape and
    candidate count must not depend on hidden facts).
  - §16 conformance: golden transcripts in `goldens/protocol_v2/*.transcript.jsonl` and
    `spellbench conformance engine ... -- path/to/engine`.
  - **Annex C: XMage via CABT.** The spec's own plan for this engine. It names an existing bridge (an XMage overlay
    with a fail-closed NDJSON legal-option server, `CabtProtocolServer`) and gives the prompt mapping, the
    determinism changes and the known gaps. Find that bridge before writing anything; reuse it if its licence
    allows, and say what you found.

## The work, in order

1. **XMage as a Spellbench engine (environment role).** A stdio server that implements §9 on top of a real XMage
   game, both seats driven through the protocol. Annex C is the blueprint:
   - Map XMage prompts to v2 kinds (`PRIORITY` → priority kinds, `CHOOSE_TARGET`/`CHOOSE` → `choose_target` or
     `select_object`, `CHOOSE_USE` → `choose_boolean`/`optional_cost`, `CHOOSE_MODE` → `choose_spell_mode`,
     attackers/blockers → `declare_attack`/`declare_block`, mulligan → `mulligan`). Mana payment by engine autopay;
     starting player `host_assigned`. Declare `rewind: true` (XMage's playability checks are heuristic and a
     failed cast rolls back).
   - Observations from XMage's per-player view, which already hides the opponent's hand.
   - Determinism: per-stream RNGs seeded from `game_secret` instead of the shared `java.util.Random`/`RandomUtil`,
     deterministic object UUIDs, stable sort keys so hash-map order never reaches candidate order, main-deck order
     restored before the seeded shuffle, one game per process.
   - Never answer a seat's decision from a copied or simulated game.
   - Done means: the conformance goldens replay, `spellbench conformance engine` passes, and the 8 Pauper decks
     pass `validate_deck` (check our `Mage.MageZero/decks/Pauper-*.txt` against the benchmark's deck pool; report
     any differences rather than editing either list).
2. **Baselines in our engine.** Run Spellbench's reference bots (`examples/minimal_bot.py`, random, first) through
   the XMage engine, and XMage-side bots (offline MCTS, MAD) wrapped as agents, so the scale can be anchored.
3. **MageZero as an agent.** Decided (see "How to work with Will"): engine-neutral, greedy policy only, opponent's
   hand hidden. The agent gets only the v2 board view, so it rebuilds the encoder's input from it. Search (which
   copies an XMage game) comes later and needs its own plan. ver15 pilots Faeries well; gen5 will cover all 8 decks.

## Rules

- Work on `spell-bench`. Never push to `graph-encoder` or `master`; Will merges via PR.
- Do not change how the rules play or how MageZero's data generator and search behave on their existing paths.
  Put the Spellbench server in its own module or package; shared changes (RNG streams, UUIDs) must be opt-in and
  leave `ParallelDataGenerator` and `ComputerPlayerMCTS2` unchanged by default.
- Fail closed: an unsupported prompt halts with a pinned error, never a guessed answer.
- Measure and record: conformance results, per-deck `validate_deck` results, games played and halts, in
  `docs/spellbench/LOG.md` with the command and commit. A claim without a run is a hypothesis.
- If a design decision isn't settled here or in the spec, stop and ask Will instead of choosing.

## Where things live

- `Mage/src/main/java/mage/game/` — `GameImpl`, `GameState`; `Mage/src/main/java/mage/util/RandomUtil.java`.
- `Mage/src/main/java/mage/players/` — `PlayerImpl` (the prompt methods a protocol-driven player overrides).
- `Mage.Server.Plugins/Mage.Player.AI.RL/` — `ComputerPlayerMCTS2`, `MCTSNode2`, `ComputerPlayerPolicyOnly`,
  `RemoteModelEvaluator` (MageZero's players; a protocol-driven player can follow how they hook decisions).
- `Mage.MageZero/` — `ParallelDataGenerator`, `Config`, decks (`decks/Pauper-*.txt`), `config/`.

## Running games on RunPod

Paid GPU work goes through RunPod with the tooling in `WillWroble/MageZero` (`runpod/`, branch
`graph-encoder-test`); use it rather than writing new launchers, so results stay comparable. `RUNPOD_API_KEY` is in
the session environment. Will pays for every pod: run only what was asked, state the expected cost before launching,
and report the actual cost afterwards.

- **Tooling.** `runpod/launch.py` creates a pod that clones MageZero at the pushed commit (push first) and runs a
  script: `--script runpod/generate.sh --env MAGE_COMMIT=<sha> MODEL=<dir> CONFIG="<yml> <yml> ..."` plays each
  config in turn (a model server on port 50052, optional `OPP_MODEL` on 50053). `runpod/build-xmage.sh` builds this
  repo at `MAGE_COMMIT` into `/workspace/xmage/<sha>` once per commit (≈15 min the first time, then cached).
  `launch.py --list`, `--reap` (deletes pods that wrote `DONE`), `runpod/watchdog.py` (checks every 10 min).
- **Volume.** Network volume `0ksqf5sep2` in EU-RO-1 (300 GB), mounted at `/workspace` in every pod. Pods must be
  in EU-RO-1. A files pod (`mz-files`, CPU, $0.06/h) serves `/workspace/runs` over HTTP on port 8000 for reading logs
  (`https://<files pod id>-8000.proxy.runpod.net/<run>/`). If it stops answering, recreate it with
  `launch.py --files`; running pods are unaffected. The volume froze once when it filled up: check free space before
  big runs and clean up old checkpoints and duplicate shards.
- **Which pod.** Games per $ on ver13 vs offline MonoB (`MageZero/results/runpod-pods.md`): RTX 4090 ($0.89/h)
  is cheapest, 178 games/$, with `THREADS=16 HEAP=32g TIMEOUT_MS=120000`; RTX PRO 6000 ($2.49/h) is fastest, 64
  threads, and is used for training. Pass the settings that match the pod: a PRO 6000 launched with 4090 settings
  wastes most of its cores. 4090s are sometimes unavailable; retry rather than silently switching type.
- **Comparability.** Every MageZero result so far uses XMage `f2ce8eca`, value-only search, 300 sims, `c_puct` 1.0,
  `max_pending` 4, the opponent's hand visible. Record the XMage commit, GPU type and `max_pending` next to every win
  rate. `max_pending` (evaluations in flight per tree) changes play when inference is slow: on Will's RTX 4070,
  ver15 vs offline MonoB went from ≈33% at 4 to ≈36% at 2; on the pods' GPUs the cap rarely binds (five pod types
  agreed within noise). 200 games give about ±3.5 points; use the same deck files, seats and budgets when comparing.
- **Practicalities that bit before.** XMage builds its card database on startup: give each pod its own local copy,
  never a shared or copied `db/` (decks load with cards missing). Write shards to the container disk and copy them to
  the volume when a run ends (`generate.sh` does this), so a stalled volume can't leave a half-written shard. Load
  models from the volume, not through Git LFS: LFS bandwidth is billed and was once blocked by its budget. Sessions
  stop background watchers after two hours; restart the watchdog when told it stopped.

## Environment

- JDK 21, Maven. XMage needs a large heap; ZGC is preferred.
- Cloud sessions have 4 vCPUs and 16 GB. If the build or a test does not fit, say so with the numbers.
- Spellbench itself is Python; install it from its repo for the conformance tools.
