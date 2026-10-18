# Perf log

Every number here comes from a command listed with it. Anything not measured is labelled a hypothesis.

## 2026-10-18 — Ticket 1: baseline (autotap)

Manual tap was dropped for this ticket at Will's request; only autotap is measured.

### Setup

| | |
|---|---|
| Engine/search code | `cfbfb733` (branch base; this ticket changes only test, config and `docs/perf/`) |
| Harness commit | `94c50a73` for the gate runs; JFR summary from the next commits on `graph-encoder-fast` (summary script only) |
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

Two separate runs, with JFR on, of the first gate case (Standard16-UB vs Standard-MonoB, 16 games). Each run was
stopped after that case. Nothing else was running on the machine during either recording window.

```
OUT_DIR=<dir> docs/perf/run-gate.sh autotap <run name> jfr
#   adds -XX:FlightRecorderOptions:stackdepth=2048
#        -XX:StartFlightRecording=settings=profile,filename=<run>.jfr,jdk.ThreadAllocationStatistics#period=1s
# after the case's "Player A win rate" line: jcmd <fork pid> JFR.dump filename=<run>.jfr, then stop the fork
python3 docs/perf/summarize.py <dir>/<run name>.log --cases UB --jfr <dir>/<run name>.jfr --case 0
```

The window runs from the case's `STARTING DATA GENERATION` line to its `Player A win rate` line. Steps are the
simulations counted in the log for that window.

| | JFR run 1 | JFR run 2 |
|---|---|---|
| UB win rate | 3/16 | 3/16 |
| window | 1,997 s | 2,605 s |
| simulation steps | 156,369 | 199,730 |
| sims/s (search) | 45.2 | 45.6 |
| search time per step | 22.1 ms | 21.9 ms |
| execution samples in window / inside `applyMCTS` | 307,888 / 98.5% | 293,924 / 98.7% |

With JFR on, sims/s is 45.4, against 53.2 for UB without JFR. JFR (profile settings, 2048-frame stacks) costs
about 15%. The JFR runs are for attribution only; the gate runs above are the speed numbers.

**Where search time goes.** Each bucket is the share of execution samples inside `ComputerPlayerMCTS2.applyMCTS`
whose stack contains that frame (inclusive), times the measured search time per step. These are sampling estimates.
Buckets nest: `getPlayable` contains `getManaAvailable`, and both contain game copies. So the shares overlap and
don't sum to 100%.

| inclusive bucket (frame) | share, run 1 | share, run 2 | est. ms/step, run 1 | run 2 |
|---|---|---|---|---|
| `getPlayable` (`PlayerImpl.getPlayable`) | 47.5% | 42.9% | 10.5 | 9.4 |
| Game copy (`GameImpl.<init>`) | 43.6% | 39.1% | 9.6 | 8.6 |
| `getManaAvailable` (`PlayerImpl.getManaAvailable`) | 23.7% | 21.6% | 5.2 | 4.7 |
| GameState copy (`GameState.<init>`) | 23.1% | 21.7% | 5.1 | 4.8 |
| `applyEffects` (`GameImpl.applyEffects`) | 17.9% | 20.7% | 4.0 | 4.6 |
| encoder (`mage.player.ai.encoder.StateEncoder.*`) | 11.7% | 13.6% | 2.6 | 3.0 |
| `RandomUtil.deepCopy` | 0.4% | 0.4% | 0.09 | 0.09 |

Searches on moves where `allMana` is set aren't logged, so their simulations are missing from the step count while
their samples are included. That makes the ms/step column a slight overestimate. The share columns are unaffected.

**Allocation and GC.**

| metric | JFR run 1 | JFR run 2 |
|---|---|---|
| allocated (Δ `jdk.ThreadAllocationStatistics`, all threads) | 4,985 GiB = **32.6 MiB/step** | 5,973 GiB = **30.6 MiB/step** |
| ZGC minor cycles (count, time in cycle) | 2,098, 2,430 s | 1,978, 2,201 s |
| ZGC major cycles (count, time in cycle) | 178, 1,608 s | 184, 1,448 s |
| GC pause time | 126 ms = 0.81 ms / 1,000 steps | 127 ms = 0.64 ms / 1,000 steps |
| JVM CPU (`jdk.CPULoad`) | 4,722 s = 30.2 s / 1,000 steps | 4,418 s = 22.1 s / 1,000 steps |
| GC + other non-Java JVM threads CPU (JVM CPU − Σ `jdk.ThreadCPULoad`) | 1,723 s = **11.0 s / 1,000 steps** (36%) | 1,582 s = **7.9 s / 1,000 steps** (36%) |

ZGC runs concurrently, so cycle times overlap each other and the run. Time in cycle above 100% of the window means
a minor cycle was running at almost all times. Pauses are negligible. GC cost shows up as CPU instead: about a
third of all JVM CPU goes to non-Java threads, mostly ZGC workers. That figure is an estimate, since JIT compiler
threads are Java threads and so already fall in the Java-thread sum. Allocation is about 2.5 GiB/s.

### Takeaways (measured above; anything about fixes is a hypothesis)

- Copying the game is the largest cost. `GameImpl.<init>` is in about 40% of search samples and `GameState.<init>`
  in about 22%. `RandomUtil.deepCopy` is only 0.4%, so the planned RNG-copy ticket will gain little on its own.
- `getPlayable` is in 43–48% of search samples. About half of that is `getManaAvailable`, and much of it overlaps
  with game copies (the legality checks copy the game, per CLAUDE.md). Cheaper copies should shrink it. How much
  is a hypothesis until measured.
- About 31 MiB allocated per step, and about a third of JVM CPU spent outside Java threads. Allocation is a target
  in its own right.
- The gate's win-rate check is noisy at 16 games per case (see Noise above).
- Not measured: manual tap (dropped for this ticket), the full `MCTSDeckValidationTest` matrix, minimax and
  commander cases.

## 2026-10-18 — Engine correctness baseline: Mage.Tests

The second correctness baseline is XMage's own rules suite. Only Mage.Tests counts. Client/server modules and
Mage.Verify are out of scope.

```
OUT_DIR=<dir> docs/perf/run-junit.sh <run name>
#   mvn test -B -pl Mage.Tests -am, with upstream CI's flags (.travis.yml), -Dmaven.test.failure.ignore=true,
#   excluding MCTSDeckValidationTest (the MageZero gate, see above)
python3 docs/perf/summarize.py <dir>/<run name>.mvn.log --junit --failed-out <dir>/<run name>.failed.txt
```

Same machine as above. The test fork uses surefire's default heap, as in CI. One run takes about 4–7 minutes.

| code | Mage.Tests failing (failures + errors) of 6,451 | runs |
|---|---|---|
| fork as received (`cfbfb733` engine) | 2,026 / 2,017 | 2 |
| + opening hands only when asked (`59ad3754`) | 378 / 386 | 2 |
| + harness adapted to MageZero's choice routing (`a0849002`) | 150 / 157 | 2 |
| upstream `09a423ae6` (the fork's base), same environment | **0** | 1 |

120 tests are skipped in every run, upstream included.

**Fork base.** `09a423ae6` (upstream master, 2025-10-26) is the closest upstream commit. Mage.Sets plus the card
tests differ from HEAD by 6 lines. Git history in the fork starts at a squashed import (`8f22235b`).

**What the two fixes addressed.**

- `GameImpl.drawHand` was a static flag defaulting to `true`, so every test-mode game dealt opening hands. It is
  now a per-game `GameOptions.drawHandInTestMode`, which MageZero sets (`59ad3754`). This was about 1,650 failures.
- The fork's `Target.choose` sends non-targeting selections (costs, "choose a …") to `chooseTarget`. Strict tests
  script those with `setChoice`, which only `TestPlayer.choose` reads. The fix is in the harness only: in strict
  mode, `TestPlayer.chooseTarget` answers a not-target selection from `setChoice` when no `addTarget` is queued.
  `CardTestPlayerAPIImpl.reset()` also got back upstream's default stop (turn 2, UNTAP), whose absence crashed
  tests without `setStopAt`. The engine wiring is unchanged (`a0849002`). This was about 230 failures.

**The remaining ~155.** These are a long tail, not attributed one by one. They include about 40 "Missing TARGET
def" and about 20 "addTarget … not used". These are probably targets the fork now asks for at a different point
(MCTS no longer enumerates targets up front). The rest are state assertions and "can't activate". Fork rule
changes are a likely source, for example `AbilityImpl.activate` now pays non-mana costs before mana costs, and
`getPlayable(game, hidden)` no longer hides duplicate abilities. That is a hypothesis, not measured.

**Noise.** 4–10 tests differ between runs of the same code, mostly AI tests and the Asmodeus/Doubling Season
families. The gate for later changes is: no test fails that passed in both baseline runs, after checking it
against that noisy set.

## 2026-10-18 — Where the copy cost comes from (from the baseline JFR, no new runs)

Recording `autotap-jfr-2` (UB case, 296,807 execution samples). Callee and caller are taken from the outermost
`GameImpl.<init>` frame of each sample.

| | share of all samples |
|---|---|
| `GameImpl.<init>` (inclusive) | 39.5% |
| … line 211 `gameCards = CardUtil.deepCopyObject(game.gameCards)` | **24.3%** |
| … line 223 `state = game.state.copy()` | 15.1% |
| … everything else in the copy (LKI maps, meld cards, options) | < 0.2% |

| who makes the copies (caller of `Game.copy`) | share of copy samples |
|---|---|
| `GameImpl.createSimulationForPlayableCalc` (`getPlayable` / `getManaAvailable`) | **99.5%** |
| `GameImpl.createSimulationForAI` | 0.3% |
| `GameImpl.setLastPriority` | 0.2% |

| inside `GameState.<init>` (direct callee) | share of `GameState.<init>` samples |
|---|---|
| `Battlefield.copy` | 36.1% |
| `TriggeredAbilities.copy` | 27.8% |
| `ContinuousEffects.copy` | 11.9% |
| `Watchers.copy` | 9.4% |
| `CardUtil.deepCopyObject` (cardState, values, …) | 4.8% |
| `Players.copy` | 2.5% |
| `RandomUtil.deepCopy` | 2.0% |

So the MCTS tree's own copies are negligible. The cost is the throwaway simulations that the playable and mana
checks create, and within those it is mostly copying every card object.

## 2026-10-18 — Speed batch 1: GC and the first copy reductions (autotap)

### Method

Iteration uses a 5-minute speed probe: the UB case, stopped after 5 minutes of play. It reports sims/s and the
live heap per `GameState` from a class histogram at the end.

```
OUT_DIR=<dir> [GATE_GC="-XX:+UseParallelGC"] docs/perf/probe.sh <run name>
```

The probe's own noise is ±1 sims/s under ZGC (baseline 50.0 / 48.3 / 50.9) and ±5 under ParallelGC (HEAD 131.2 /
126.4). The correctness sweeps (Mage.Tests and the full 4-case gate) run only once a batch shows a large gain.
Profiling is JFR (`profile` settings) on the probe: execution samples for CPU, `jdk.ObjectAllocationSample` for
allocation sites, and `jdk.OldObjectSample` for what survives.

### What limits throughput

At baseline the game threads ran at only 0.55–0.70 of a core each, with no lock contention. The live heap after GC
was a median 5.6 GiB, a p90 of 8.5 GiB, and touched the 10 GiB limit. Every MCTS node keeps a full `GameState`
(about 0.9 MB each). Generational ZGC ran a cycle nearly all the time, took about a third of JVM CPU on 4 vCPUs,
and stalled allocating threads (788 s of stalls in a 2,605 s window).

So under ZGC, cutting short-lived allocation did nothing. The `gameCards` change and skipping the nested mana copy
removed about 20% of samples and allocation each, yet sims/s did not move. Only cutting what nodes retain
(`PermanentCard` templates) helped. With ParallelGC the GC stops being the ceiling, and allocation and CPU
reductions show up.

### Results (5-minute UB probe, sims/s)

| change | ZGC | ParallelGC |
|---|---|---|
| baseline engine (`cfbfb733` + hand fix) | 50.9 (48.3–50.9) | 84.7 |
| + `gameCards` copied on first access in playable calc (`66ae79c1`) | 49.5 | |
| + `PermanentCard` copies share one template snapshot (`440799d6`) | 60.9 / 63.2 | 131.2 / 126.4 |
| + playable calc in place on MCTS working games (`3ed14901`) | | 175.6 |
| + encoder hash resolves edges once, precompiled regexes (`79d97ebb`) | | (included above) |
| tried and dropped: reuse getPlayable's simulation for its nested getManaAvailable | 55.3 / 59.4 | 121.9 |
| tried and dropped: playable calc shares triggers until first use | | 125.3 |
| G1 instead of ZGC (at `440799d6`) | | G1: 97.5 |

### Full gate on batch 2 (`79d97ebb`, ParallelGC, `-Xmx10g`)

```
OUT_DIR=<dir> GATE_GC="-XX:+UseParallelGC" docs/perf/run-gate.sh autotap batch2-gate
```

| case | pass | win rate | sims/s | baseline sims/s (run 1 / run 2) |
|---|---|---|---|---|
| UB | pass | 4/16 (25.0%) | 184.1 | 53.0 / 53.4 |
| 5C | pass | 6/16 (37.5%) | 155.8 | 52.9 / 55.6 |
| UR | pass | 5/16 (31.2%) | 200.7 | 65.0 / 61.1 |
| GW | pass | 4/16 (25.0%) | 106.4 | 46.9 / 41.9 |
| all | 4/4 | 19/64 (29.7%), baseline 11/63 and 22/64 | **139.2** | 52.6 / 50.8 |

- Wall time is 56 min, against 1 h 54 for the baseline.
- No search hit the 300 s cap (the baseline had 2 and 3 in GW).
- ERROR/FATAL: 4 × `StateEncoder.addFeature: missing parent reference` (GW), the same kind as the baseline's 3.
  No new kinds.
- Mage.Tests on this code: 157 failing, the same as the harness baseline (150 / 157). No new failures.

Under ParallelGC the full UB case had 21 full GCs, the longest 2.4 s, with 141 s of pauses in all. Pauses don't
matter for offline self-play throughput.

### Not changed, needs a decision

The release launchers (`Mage.MageZero/release/mz-xmage.sh` / `.bat`) run `-Xmx24g -XX:+UseZGC`, which is
non-generational ZGC on JDK 21. On this 4-vCPU machine ParallelGC is about 1.7× faster on the same code. Whether
that holds on Will's machine (more cores, 24 GiB heap) is untested. It is a hypothesis to measure there before
changing the launchers.

### Tried after batch 1 and dropped (measured, not committed)

- **No `applyEffects` in `MCTSNode.resetRootGame`.** The resumed game's `playPriority` applies effects first anyway,
  so this should be neutral. Probe: 178.5 against 175.6, inside the noise. Not worth even a small behaviour risk.
- **Skip `applyEffects` when no event was handled since the previous run.** An event counter in `GameState`
  showed 31% of calls are quiet (564k of 1.8M in 5 minutes). But a fingerprint check found 11 of 28,222 quiet
  calls still changed state. A transform (Cecil, Dark Knight → Redeemed Paladin) appeared only after the next
  apply. Effects that grant abilities also create new ability objects, with new ids, on every apply. Not safe
  without dirty tracking beyond events.
