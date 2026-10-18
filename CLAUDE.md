# CLAUDE.md — graph-encoder-fast

This branch makes XMage's simulation path fast enough for MageZero's MCTS, without changing how the rules play.
MageZero is Will Wroble's AlphaZero-style system for Magic on this XMage fork. The search, encoder and data
generator here are his design. Read this file fully before touching code.

## The target: match or beat gorge

[gorge](https://github.com/adams-shaun/gorge) is a Go MTG engine. In draft-zero's engine comparison
(docs/015-rules-engine-comparison.md in github.com/danieljbrooks/draft-zero), measured against this XMage/MageZero:

- one search step: gorge 46 µs (clone + one submit) vs MageZero 1.2–2.4 ms (copy, run to next decision,
  features, score) — about 27× faster
- state copy: about 11× faster
- random play: 16–50× faster per turn

The goal is to close that gap in XMage: match gorge's per-step cost, then beat it.

## Both tapping modes

Report every stat for both autotap and manual tap.

## The goal

Cut the cost of one MCTS simulation step. Most of today's cost is copying and allocation, not rules logic:

- `GameImpl` copy deep-copies `gameCards` (every card object with its abilities), every LKI map, `targetedMap`,
  `permanentsEntering`, `enterWithCounters`, then the whole `GameState`.
- `GameState` copy deep-copies battlefield, stack, exile and command (every `Permanent` with abilities),
  `cardState`, `mageObjectAttribute`, `values`, all continuous effects, triggers, delayed triggers and watchers.
- `RandomUtil.deepCopy` copies the RNG through a full Java serialization round trip on every state copy.
- `getPlayable` / `getManaAvailable` copy the game again for legality checks.
- Every `resetRootGame` re-runs `applyEffects`, whether or not anything changed.

The plan, in order: baseline measurement, RNG copy, copy-on-write for `gameCards`, copy-on-write for the per-card
maps, then permanents, effects and watchers. One ticket at a time; the current ticket is given in the session prompt.

## Where things live

- `Mage/src/main/java/mage/game/` — `GameImpl`, `GameState` (the copy constructors are the main target).
- `Mage/src/main/java/mage/players/` — `PlayerImpl`, `Library`.
- `Mage/src/main/java/mage/util/` — `CardUtil.deepCopyObject`, `RandomUtil`.
- `Mage.Server.Plugins/Mage.Player.AIMCTS/` — `MCTSNode`, `ComputerPlayerMCTS`, `MCTSPlayer` (scripted replay player).
- `Mage.Server.Plugins/Mage.Player.AI.RL/` — `ComputerPlayerMCTS2`, `MCTSNode2` (async search), `RemoteModelEvaluator`.
- `Mage.MageZero/` — `ParallelDataGenerator`, `Config`, `LabeledStateWriter`, decks, `config/`.
- `Mage.Tests/src/test/java/org/mage/test/AI/RL/MCTSDeckValidationTest.java` — the gate.

## The gate: MCTSDeckValidationTest

Exact game-by-game determinism is not achievable for MCTS vs MCTS (timing and hardware dependent). Do not chase
identical games. The gate, run in both autotap and manual tap:

1. Every parameterized case in `MCTSDeckValidationTest` completes all its games with no exception.
2. `ERROR` and `FATAL` log lines don't increase over the baseline. Some occur in the baseline too and are
   unavoidable; what matters is new kinds or higher counts. A copy-on-write bug usually shows up here (leaked
   mutations between simulation copies), not as a crash. If counts rise beyond run-to-run noise, the change is
   wrong even if the test passes.
3. Win rates match the baseline at the same search budget (a fixed number of simulations, not a time limit, so a
   faster engine can't buy extra search). A drop beyond noise means the change altered behaviour.

## The scoreboard

All measurement is offline MCTS (heuristic value, no network). Never start the model server or include inference time.

Speed is measured, never asserted. For every change, before and after, on the same machine, in both tapping modes:

- Simulations per second: the `Total: simulated N evaluations in T seconds - Average: X` lines from
  `ComputerPlayerMCTS2`.
- Per step: time in `Game.copy()` / `GameState.copy()`, `getPlayable`, `applyEffects`, and the encoder.
- Allocated bytes per simulation step and GC time per 1,000 steps, from JFR.

Every number goes in `docs/perf/LOG.md` with the command that produced it, the machine (cores, RAM, JVM flags),
and the commit. A claim without a measurement is labelled a hypothesis.

## Rules

- Never push to `graph-encoder` or `master`. Work on the branch the session started on; Will merges via PR.
- Do not change game behaviour. A rules difference is a bug, even if it is faster.
- Keep the protection from upstream commit `e8e2f232`: playable calculations run in a simulation, outside the real
  game. Making them cheaper must not let a check mutate the real game.
- Do not add config fields. Heap and JVM settings belong to the launcher scripts, not `Config`.
- In the PR description, state the reason for each design decision, what was measured, and what was not tested.
- If a change needs a decision the ticket doesn't settle, stop and ask instead of choosing.


## Environment

- JDK 21, Maven. XMage needs a large heap; ZGC is preferred.
- Cloud sessions have 4 vCPUs and 16 GB. If the build or the gate does not fit, say so with the numbers
  rather than shrinking the test.