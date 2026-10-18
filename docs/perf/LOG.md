# Perf log

Every number here comes from a command listed with it. Anything not measured is labelled a hypothesis.

## 2026-10-18 — Ticket 1: baseline (autotap)

Manual tap was dropped for this ticket at Will's request; only autotap is measured.

### Setup

| | |
|---|---|
| Engine/search code | `cfbfb733` (branch base; this ticket changes only test, config and `docs/perf/`) |
| Harness commit | `94c50a73` |
| Machine | cloud container, 4 vCPU Intel Xeon @ 2.10 GHz (1 thread/core), 15 GiB RAM, Linux 6.18 |
| JVM | OpenJDK 21.0.12, test fork: `-Xmx10g -XX:+UseZGC -XX:+ZGenerational -da:ch.systemsx.cisd...` |
| Build | Maven 3.9.11: `MAVEN_OPTS=-Xmx4g mvn -q -B install -DskipTests -pl Mage.Tests -am` |
| Gate | `MCTSDeckValidationTest#fastGate`: Standard16-{UB,5C,UR,GW} vs Standard-MonoB, MCTS vs MCTS, offline (heuristic value, no model server), 16 games per case, 4 game threads |
| Search budget | `Mage.Tests/config/config.yml`: `search_budget: 300`, `timeout_ms: 600000` (both players); mulligans on |

Command (one run):

```
OUT_DIR=<dir> docs/perf/run-gate.sh autotap <run name>
python3 docs/perf/summarize.py <dir>/<run name>.log --cases UB 5C UR GW
```

`run-gate.sh` runs the test inside the Maven reactor (`mvn -B test -pl Mage.Tests -am -Dtest='MCTSDeckValidationTest#fastGate'`)
with `docs/perf/log4j-perf.properties`, which writes the whole log to one non-rolling file.

Notes on the setup:

- `-da:ch.systemsx.cisd...`: surefire enables assertions, and jhdf5's own `assert blockSize <= size` rejects
  `LabeledStateWriter.createOffsets` (size 1, chunk 2048), failing every case before a game starts. Only jhdf5's
  asserts are off; XMage's stay on.
- sims/s is ΣN / ΣT over the per-search `Player: X simulated N evaluations in T seconds` lines. The
  `Total: simulated … Average:` lines are running sums of exactly these, so the ratio is the same.
- Budget binds: every search ended on `required visits reached` except those that hit the 300 s hard cap in
  `ComputerPlayerMCTS2.applyMCTS` ("force time out … couldn't find legal move", column "force"). None ended on
  the time limit. Budget counts root visits, and the tree is reused between moves, so a search runs fewer than
  300 new simulations when the root already has visits.

### Gate results

| case | run 1 | run 2 | win rate run 1 | win rate run 2 | both runs |
|---|---|---|---|---|---|
| UB | pass | pass | 2/16 (12.5%) | 4/16 (25.0%) | 6/32 (18.8%) |
| 5C | pass | pass | 4/16 (25.0%) | 8/16 (50.0%) | 12/32 (37.5%) |
| UR | pass | pass | 2/16 (12.5%) | 5/16 (31.2%) | 7/32 (21.9%) |
| GW | **fail** (15/16 games) | pass | 3/15 (20.0%) | 5/16 (31.2%) | 8/31 (25.8%) |
| all | 3/4 | 4/4 | 11/63 (17.5%) | 22/64 (34.4%) | 33/127 (26.0%) |

Win rate is Player A (the test deck) against Standard-MonoB.

**Noise.** Identical code gave per-case differences of 2–4 games out of 16, and 17.5% vs 34.4% overall. At
p ≈ 0.25 and 16 games, one case's standard error is about ±11 points; over 64 games it is about ±5.4. Two runs are
only a rough noise bound. A change can only be called a win-rate regression if it falls clearly outside this
spread. Smaller effects need more games.

**GW failure (run 1), pre-existing.** On turn 20, two searches in a row hit the 300 s cap. The next priority
call threw `NullPointerException: Cannot invoke "mage.abilities.Ability.toString()" because "ability" is null`
at `ComputerPlayerMCTS.priority(ComputerPlayerMCTS.java:135)`, called from
`ComputerPlayerMCTS2.priority(ComputerPlayerMCTS2.java:134)`. The game was dropped and the case asserted 15 ≠ 16.
In run 2, GW hit the cap 3 times without crashing. The stuck search reproduces in GW. The crash after it does not
happen every time.

### Speed (search)

| case | sims/s run 1 | sims/s run 2 | excl. 300 s-cap searches, run 1 | excl., run 2 | cap hits (1 / 2) |
|---|---|---|---|---|---|
| UB | 53.0 | 53.4 | 53.0 | 53.4 | 0 / 0 |
| 5C | 52.9 | 55.6 | 52.9 | 55.6 | 0 / 0 |
| UR | 65.0 | 61.1 | 65.0 | 61.1 | 0 / 0 |
| GW | 46.9 | 41.9 | 46.4 | 47.0 | 2 / 3 |
| all | 52.6 | 50.8 | 52.7 | 53.2 | |

Sims/s is per search. In offline mode `MCTSNode2.evaluate` runs synchronously, so each search uses one thread.
With 4 games on 4 vCPUs, that is about 1/52.6 s ≈ 19 ms per simulation step on one core. One step here is select +
`validateState` + heuristic evaluate + `expand`, which is not the same unit as the 1.2–2.4 ms "search step" quoted
in CLAUDE.md, so the two are not directly comparable. Wall time: run 1 1 h 54 min, run 2 1 h 53 min.

### ERROR / FATAL lines

Grouped by message, with numbers and UUIDs normalized (`summarize.py`).

| message | run 1 | run 2 |
|---|---|---|
| `FATAL GameImpl.playPriority: Game error: <uuid> - !!!NOT GAME THREAD!!! …` | 1 (GW) | 0 |
| `FATAL GameImpl.playPriority: Game end on critical error: mage.MageException: Error in unit tests` | 1 (GW) | 0 |
| `ERROR ParallelDataGenerator.runSingleGame: Caught an internal AI/Game exception … Error in unit tests` | 1 (GW) | 0 |
| `ERROR ParallelDataGenerator.runSimulations: A game simulation failed and its result will be ignored …` | 1 (GW) | 0 |
| `ERROR StateEncoder.addFeature: missing parent reference for` | 0 | 3 (UB 1, GW 2) |

The 4 lines in run 1 all come from the single GW crash above.

### JFR (UB case)

Pending.
