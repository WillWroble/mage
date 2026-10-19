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
| + priority nodes store a state copied before the in-place playable calc (`e8263c5e`), so the calc's side effects can't be stored in a node | | **142.4** (the cost of one `GameState` copy per priority point) |
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

## 2026-10-18 — Same-decks comparison with gorge

Both engines ran on the same machine and the same core (one core of the 4-vCPU Xeon @ 2.10 GHz, `taskset`), with
the same decks: the gate's UB pair, Standard16-UB vs Standard-MonoB. gorge at `df885f9`, Go 1.25.8, its Forge card
corpus at the Makefile pin (all 60+60 cards resolve). XMage at `e8263c5e` (this branch, priority nodes store a copy taken before the playable calc), JDK 21, ParallelGC,
`-Xmx10g`. Both numbers include GC.

| | XMage (this branch) | gorge | gap |
|---|---|---|---|
| copy a mid-game state (turns 5–8) | `Game.copy()` 530–840 µs, 1.55–1.65 MB allocated (`GameState.copy()` alone 155–510 µs, 440–535 KB) | `Engine.Clone` 65–74 µs with GC (37–38 µs with the Go collector off), 210 KB, 204 KB retained | 8–12× |
| search step to the next real choice | one MCTS simulation: 7.36 ms (136 sims/s) | `Clone` + `Submit`, then auto-pass to the next real choice: about 200 µs (163–223) | ~37× |
| … without features | ~5 ms (encoder ~1.1 ms per call, ~2 calls per simulation) | no encoder | ~25× |
| random play, turns/s | not measured | 300 | |

**How it was run:**

- **gorge:** `cmd/enginebench`, with `docs/perf/gorge-enginebench-mz.patch` applied. The patch adds a pair `MZ`
  (copy the two `.dck` files from `Mage.Tests/decks` into `cmd/enginebench/testdata/decks`), lets the clone and
  step rows take their roots from `-pair`, and adds `GORGE_TO_CHOICE=1`. That mode passes priority while pass is
  the only option, the way `MCTSPlayer` auto-passes. Command:
  `GOMAXPROCS=1 taskset -c 0 bin/enginebench -row step|clone -pair MZ`. Roots are its heuristic bot's games at
  turns 5–8. The step figure is `USPerCPUWithGC` (GC included). gorge's headline `us_per_op` (127 µs) is with the
  Go collector off, as is the clone row's headline (37–38 µs). The clone figure in the table is its
  `USPerCPUWithGC` (65–74 µs).
- **XMage:** the gate's UB case with one game thread on one core:
  `taskset -c 1` + `-Dgate.threads=1 -XX:ActiveProcessorCount=1`, 15 minutes of play.
  - The step is search seconds per simulation from the gate log.
  - Copy and encoder costs came from temporary timing code, not committed: 1,000 copies at each game's first
    search on turns 5–8, `ThreadMXBean` allocation, and `System.nanoTime` around `encoder.processState`.

**Caveats:**

- The roots differ: gorge's bot games against XMage's MCTS self-play at turn 5.
- An XMage simulation also does MCTS bookkeeping (selection, backup, duplicate detection). That was under 3% of
  samples in the profiles.
- On an M1 Pro, draft-zero measured gorge at 14 µs per clone and 46 µs per step (FDN decks, GC off). This Xeon core
  runs about 2.8× slower.

**Why gorge is so much cheaper (from its code):**

- **A gorge object is a flat value.** `state.Object` is about 1 KB in one array. It points at the compiled,
  immutable card (`Card *cards.Card`), which every clone shares. `Game.CloneIntoDirty` copies the array and a few
  small slices, and can reuse a spent clone's arrays.
- **An XMage copy is a deep object-graph copy.** Every card and permanent carries its own copies of its abilities,
  with `Modes`, `Effects`, `Costs`, `Targets`, mana costs and so on.
- **gorge memoizes layers.** Its static-effects result is cached under a `continuousVersion` stamp, and clones
  carry the cache. XMage's `applyEffects` rebuilds every permanent from its template and reapplies every layer at
  every priority point (about 30% of our step).
- **Every gorge mutation is an event** (`events.Apply`), so "nothing changed" is knowable. XMage writes state
  directly, which is why our "skip quiet `applyEffects`" experiment found changes no event announced.
- **gorge has no feature encoder in the step.** Ours costs about 1.1 ms per call: the whole graph is rebuilt from
  ability text at every decision.

## 2026-10-19 — Copy-on-write for GameState (autotap)

Machine and flags as above: 4 vCPU Xeon @ 2.10 GHz, 15 GiB, JDK 21, ParallelGC, `-Xmx10g`.

### Changes

| commit | change | why |
|---|---|---|
| `90a17e4c` | `GameState.lazyCopy`: shares the 21 big components with the source and copies each one on first access. `MCTSNode.validateState` restores from it. | A node snapshot never changes after it is stored, so a restore can share it. On its own this moved nothing (136.9 sims/s): counters showed every restore touches almost every component (`applyEffects` resets players, permanents, triggers, effects). |
| `cb60606d` | `TriggeredAbilities` copy-on-write per ability: a map owns the abilities it added or copied. A copy shares the source's non-owned abilities. An ability is copied only when a trigger check is about to change it (`checkEventType` passed). A lazy restore shares all of a node's abilities (`copyShared`). | Triggers were 38% of `GameState` copy time. A trigger check is the only place an ability in this map changes (`getTriggers` has three engine callers; `checkEventType` bodies scanned: none mutate). |
| `cb60606d` | `CopyableRandom`: `java.util.Random`'s exact LCG and gaussian cache, with a direct copy. `RandomUtil.deepCopy` uses it instead of a serialization round trip. | The RNG round trip was 5.7% of copy time. The sequence is identical by construction. |
| `cb60606d` | `Watcher.copy` caches the constructor and field lookups per class. | It did reflection lookups on every copy. Copy semantics unchanged. |
| `6bfaeae7` | `GameState.snapshotCopy`: priority snapshots (`MCTSPlayer.priority`) copy permanents without their abilities (`Permanent.copyWithoutAbilities`). | A snapshot is only restored through `lazyCopy` then `applyEffects`, which resets every permanent. Reset rebuilds the abilities from the permanent's card or token, so they are dead weight in a snapshot. Abilities were 79% of permanent copy time. |

### Speed

5-minute UB probe (4 threads): `docs/perf/probe.sh <run>` with `GATE_GC="-XX:+UseParallelGC"`.

| build | sims/s |
|---|---|
| `e8263c5e` (before) | 142.4 |
| `90a17e4c` lazy restore | 136.9 |
| `cb60606d` + triggers, RNG, watchers | 167.5 |
| `6bfaeae7` + snapshot copies | 180.8 |

Single core (`taskset -c 1`, `-Dgate.threads=1 -XX:ActiveProcessorCount=1`, UB case, 12 minutes; same setup as
the gorge comparison): 136 sims/s at `e8263c5e`, 220.0 / 235.8 at `6bfaeae7` (two runs). That is 4.2–4.5 ms per
simulation, against gorge's ~200 µs step.

Profile share (JFR execution samples, 3-minute probe) of state copying: about 27% at `90a17e4c` (15.3% priority
snapshot, 10.9% restore), 19% at `cb60606d`. Others at `cb60606d`: `applyEffects` 33%, encoder 22%,
`getPlayable` 10%.

### Clone cost per call

`docs/perf/copybench.patch` (temporary, `git apply` it for the run): at each game's first priority search on turns
5–8, 1,000 calls of each after 300 warm-up rounds, `System.nanoTime` and `ThreadMXBean` allocation. Single core,
same command as above, at `6bfaeae7`. 14 samples, all turn 5 with 4–9 permanents. Median (range):

| call | time | allocated |
|---|---|---|
| `GameState.copy()` | 132 µs (113–705) | 424 KB |
| `snapshotCopy()` (priority snapshot) | 128 µs (113–614) | 397 KB |
| `lazyCopy()` + first access of every component (restore) | 58 µs (51–144) | 190 KB |
| gorge `Engine.Clone`, turns 5–8, GC on (from the comparison above) | 65–74 µs | 210 KB |

Before this work `GameState.copy()` was 155–510 µs. The outliers are GC pauses inside the timed loop.

### Correctness

- Full gate at `6bfaeae7` (`GATE_GC="-XX:+UseParallelGC" docs/perf/run-gate.sh autotap gate-cow1`): 4/4 pass,
  36 min wall time (56 for batch 2).

  | case | win rate | sims/s |
  |---|---|---|
  | UB | 1/16 | 180.6 |
  | 5C | 2/16 | 171.0 |
  | UR | 8/16 | 196.8 |
  | GW | 3/16 | 171.3 |
  | all | 14/64 (21.9%); baseline 11/63 and 22/64, batch 2 19/64 | **178.6** (batch 2: 139.2) |

  ERROR/FATAL: 3 × `StateEncoder.addFeature: missing parent reference`, the baseline's kind and count. No new
  kinds. No search hit the 300 s cap.
- Mage.Tests at `6bfaeae7` (`docs/perf/run-junit.sh`): 157 failures + 3 errors. Every failing test also failed in
  at least one earlier baseline run (harness runs h1 / h2, batch 2). `DoublingSeasonTest...PirFirst` is outside
  the union of h1 / h2 / batch 2 but failed in 5 earlier runs. No new failures.

### Not tested

- Manual tap.
- Win rate is one 64-game run; 16-game cases are too small to see a per-deck shift.
- The commander and minimax cases.

## 2026-10-19 — applyEffects, first pass (autotap)

### Where applyEffects time goes

Temporary per-call-site counters in `GameImpl.applyEffects` (not committed), 3-minute UB probe at `814f5992`,
4 threads, 56,316 simulations:

| call site | calls per simulation | µs per call |
|---|---|---|
| `MCTSNode.resetRootGame` | 2.6 | 481 (it also pays for the lazy restore copy) |
| `GameImpl.playPriority`, before each priority | 7.5 | 158 |
| `GameImpl.playPriority`, top of the loop | 4.1 | 162 |
| `processAction` after a mana ability resolves | 2.2 | 157 |
| `AbilityImpl.activate` | 1.8 | 168 |
| others (`playPriority` after resolve, untap, draw, moveCards, end of turn) | 2.3 | ~150 |

About 19 calls and 4 ms of thread time per simulation. Inside one call (JFR at `814f5992`):

- rebuilding permanents from their cards (`PermanentCard.copyFromCard`, mostly copying the card's abilities): 11.5% of
  all samples;
- `ContinuousEffects.apply`: 17.7%, of which `getLayeredEffects` (called again after every applied effect) 11%,
  `removeInactiveEffects` 3%.

A per-call benchmark on one core (`docs/perf/applybench.patch`, 1,000 calls on a simulation copy at each game's
first priority search on turns 5–8) gives 37–55 µs per call at turn 5, with 4–10 permanents. That is about 3× less
than inside the search, where the objects are cold in cache.

### Changes

| commit | change | measured |
|---|---|---|
| `814f5992` | `GameEvent` ids from `ThreadLocalRandom` instead of `UUID.randomUUID`, whose shared `SecureRandom` is slow and contended across game threads (`randomUUID` was 3.8% of samples). `GameState.getCardState` uses `computeIfAbsent` instead of allocating a `CardState` per call. `PermanentImpl.hasAbility` checks the base and card-state lists directly instead of building the combined list. All give the same results. | probe 180.8 → 205.5 |
| `68268a06` | Mana cost copies share their `options` set. It is set when the cost is built and never changed in place; `setPhyrexian` now replaces it. | JFR: `ManaOptions` copies 4.3% → 0.2% of samples; probe inside noise |
| `13b10b66` | MCTS restores skip `playPriority`'s opening `applyEffects`. `resetRootGame` has just applied effects, and only a `PhaseChangedEvent` fires in between. A mark on the state, cleared by any other `applyEffects`, limits this to that path; real games never set it. A call-sequence change, agreed with Will: a restored node now applies effects twice before its decision, where the original game applied them once. | probe 198.1 / 212.8 |


Probe noise: the same build gave 181.3 and 205.5 on different runs, and the probe plays different random games each run. One
probe can't resolve changes under about 10%; JFR sample shares can.

### Correctness at `13b10b66`

- Full gate (`GATE_GC="-XX:+UseParallelGC" docs/perf/run-gate.sh autotap gate-ae1`): 4/4 pass, 35 min.

  | case | win rate | sims/s |
  |---|---|---|
  | UB | 2/16 | 165.4 |
  | 5C | 4/16 | 190.8 |
  | UR | 3/16 | 220.8 |
  | GW | 1/16 | 167.2 |
  | all | **10/64** (15.6%) | 180.2 (178.6 at `6bfaeae7`) |

  ERROR/FATAL: 5 × `StateEncoder.addFeature: missing parent reference` (GW), the baseline's only kind (baseline 3,
  batch 2 4). No new kinds.
- Win rate across runs: baseline 11/63 and 22/64, batch 2 19/64, `6bfaeae7` 14/64, `13b10b66` 10/64. The last two are
  below the batch-2 and second baseline runs but inside the baseline's own spread. With 64 games per run, this can't
  tell a behaviour change from noise (binomial sd ≈ 3.5 games). **Needs more games before merging.**
- Mage.Tests: 157 failures + 3 errors, all in earlier baseline runs. No new failures.

### Not done

- Manual tap.
- The bigger applyEffects wins need dirty tracking (rebuild only what changed, like gorge's memoized layers);
  not started.

## 2026-10-19 — Encoder, first pass; manual tap from here on

Manual tap is now the mode for step-to-step comparisons with gorge: `PROBE_MODE=manual docs/perf/probe.sh <run>`.

### Single-core manual-tap step (at `1ddf7dd6`)

`taskset -c 1`, `-Dgate.threads=1 -XX:ActiveProcessorCount=1`, ParallelGC, UB case, 12 minutes, with
`docs/perf/stepbench.patch` applied (encoder timers):

- 140,464 simulations in 695 s of search, **4.95 ms per simulation** (gorge: ~200 µs per step).
- Encoder: 218,412 calls, 1.55 per simulation, **812 µs per call**, 1.26 ms per simulation (25%).
- State hash (`FeatureGraph.getStateHash`, computed once per graph): 128 µs per call.

Simulation counts here come from the cumulative `Total:` lines of every game in the window, including the game
still running. `summarize.py` counts only finished games: 17,524 simulations, 189 sims/s, for the same run.

### Dedup cost (`getMatchingState`)

JFR, 3-minute UB probes, share of all samples:

| | autotap | manual tap |
|---|---|---|
| `getMatchingState` / `getMatchingStateInScope` | 6.2% | 7.4–9.5% |
| … of which the first computation of the state hash (16-round graph hash, done lazily here) | 5.9% | |
| … the tree walk itself | 0.2% | |
| exact-match state string (`GameState.getValue` in `validateState`) | 3.4–3.9% | 5.0–5.5% |

### Changes

| commit | change | measured (JFR, 3-minute manual-tap UB probe) |
|---|---|---|
| `1ddf7dd6` | `cleanString` returns the input when it has no `[` or `<`; `addFeature` hashes a name once; `getStateHash` runs its rounds over flat arrays | `cleanString` 3.4% → 1.0%, `getStateHash` 7.8% → 6.6% of samples |
| `aca0095c` | the encoder looks a permanent's exile-zone id up instead of `getExileZoneId`, which created and stored an id in the game state for every permanent; `AbilitiesImpl.getValue` calls `toString` once per ability | `AbilitiesImpl.getValue` 2.5% → 1.5%, `getExileZoneId` 0.6% → 0 |
| dropped | `hash64` over the string's chars instead of `getBytes` | slower: 2.5% → 4.5% (the JDK's `getBytes` and `ByteBuffer.getLong` are intrinsics) |

Equality checks: 2M random strings (including non-ASCII) gave identical `cleanString` and `hash64` results; a
4-minute probe compared 220,000 state hashes against the previous algorithm (0 mismatches); a 4-minute UB/GW
probe compared both exile-zone lookups on every permanent (0 mismatches, 8,960 real zones found).

### Still in the encoder (man-enc2, share of all samples)

- `SpellAbility`/`ActivatedAbility.canActivate` on a copy of every activated ability ("CanActivate"): ~5% (copy plus check).
- Ability text (`toString` / `getRule`) for every ability node: ~4%.
- `Modes.getAvailableModes` (target checks) for every ability: ~2%.
- Graph bookkeeping (`HashMap` lookups and puts in `addFeature`): ~3–4%.
- `FeatureMap.addFeature`: 3.5%, because `Mage.Tests/config/config.yml` has `log_feature_hash: true` (research
  logging; `Mage.MageZero/config/config.yml` has it off). Not changed.

## 2026-10-19 — Overnight: manual-tap step (encoder excluded)

All measurements in manual tap. Commits, in order:

| commit | change | evidence |
|---|---|---|
| `ae3c4df1` | manual-tap playable calc (`ComputerPlayer.getPlayableAbilities`) runs in place on MCTS working games instead of copying the whole game; incremental tree size/depth limits; graph nodes keep child references for the state hash | JFR: playable calc 14.4% → 7.6% of samples, its game copy 6.0% → 0.1%; 300,000 incremental tree counts and 300,000 state hashes matched the old code |
| `29bcc5f55` | MCTS resumes skip the pre-priority `applyEffects` when nothing changed since the restore | JFR: other `applyEffects` 17.7% → 13.0% |
| `b72a13e1` | snapshots also leave out temporary effects and gained triggers (rebuilt by the restore's `applyEffects`) | 300,000 restores compared full vs reduced snapshot after `applyEffects`: identical except one regenerated random ability id (12,169 cases) |
| `683bf0e4` | tree limits from upper bounds (no walk after prunes); unused rule-text map in the playable calc removed | |
| `5bfb9ce6` | dedup compares a cheap label sum before the state hash | 29.4M comparisons, same results as the hash alone; `getStateHash` 4.8% → 2.4% |
| `41e8d91a` | MCTS resumes also skip the activation's `applyEffects` right after the restore | JFR: other `applyEffects` 14.6% → 10.0% |


The two `applyEffects` skips extend the call-sequence change Will approved for `13b10b66` (a restored node now
applies effects once before its decision, as the original game did).

### Single-core step, encoder excluded

`taskset -c 1`, `-Dgate.threads=1 -XX:ActiveProcessorCount=1`, ParallelGC, UB case, 12 minutes,
`docs/perf/stepbench.patch` applied. One validation (restore, run to the next decision, encode) is the unit
comparable to gorge's step (clone, submit, run to the next choice): pruned duplicates and failed scripts are
validations that do not count as simulations.

| build | µs per validation without encoder | encoder µs per call | ratio |
|---|---|---|---|
| `aca0095c` (start of the night), run 1 | 2026 | 823 | 2.46 |
| `aca0095c`, run 2 | 2333 | 951 | 2.45 |
| `41e8d91a`, run 1 | 2100 | 1063 | 1.98 |
| `41e8d91a`, run 2 | 1924 | 1035 | 1.86 |

Each run plays different games, and the cost per step moves with the board: the encoder, whose code did not
change, ranges from 812 to 1063 µs per call. Normalized by the encoder cost, the non-encoder part dropped by
about 22%. In absolute terms it is about **1.9–2.1 ms per step, about 10× gorge's ~200 µs**. The 1 ms target
was not reached.

### Manual-tap gate at `41e8d91a`

`GATE_GC="-XX:+UseParallelGC" docs/perf/run-gate.sh manual gate-man1` (GW ran separately as `gate-man1-gw`
after the first run hit the 2-hour task limit):

| case | win rate | sims/s |
|---|---|---|
| UB | 3/16 | 238.9 |
| 5C | 1/16 | 231.2 |
| UR | 6/16 | 234.3 |
| GW | 4/16 | 211.3 |
| all | 14/64 | |

4/4 pass. There is no manual-tap baseline, so win rates have nothing to compare against. ERROR lines: the
baseline's `missing parent reference` kind, plus 2 events (UR, one game) of a kind never seen in autotap runs:
`MageObjectReference`: a stack ability's source is not found, not even in LKI. Hypothesis, not verified:
`MCTSNode.resetRootGame` clears LKI on every restore, so a node restored with such an ability on the stack
can't find its source; manual tap stops at more mid-stack points.

### What is left, without the encoder (JFR at `41e8d91a`, share of the non-encoder step)

restore 24% (its `applyEffects` plus copying components out of the snapshot), other `applyEffects` 17%, playable
calc 14%, priority snapshot copy 10%, exact-match state string for dedup 7.5%, state hash 5%. Under 1 ms
needs structural changes, each a design decision: drop the restore's `applyEffects` (snapshots would have to
keep everything again), match dedup on the hash alone instead of the state string, and dirty tracking in
`applyEffects`.

**Checkpoint (2026-10-19): `92fd2a8f`.** Manual-tap step without encoder ~1.9–2.1 ms on one core; manual-tap gate 4/4 at `41e8d91a`
(the last autotap gate ran at `13b10b66`, before the overnight changes). Return here if later work regresses.

## After the checkpoint: toward 1 ms (manual tap, single core)

Same setup as "Single-core step, encoder excluded" above (4 vCPU, 15 GiB, OpenJDK 21.0.12; `taskset -c 1`,
`-Dgate.threads=1 -XX:ActiveProcessorCount=1 -XX:+UseParallelGC`, UB case, 12 minutes, `stepbench.patch`).

| build | change | µs per validation without encoder | encoder µs per call | ratio |
|---|---|---|---|---|
| `dffe91b0` | quiet `applyEffects` skip + state value hash | 1481 | 969 | 1.53 |
| `752d8ec8`, run 1 | + label-sum set, playable memo | 1839 | 981 | 1.87 |
| `752d8ec8`, run 2 | | 1459 | 1010 | 1.44 |
| `58a72245` | + compact frozen FeatureGraph | 1137 | 819 | 1.39 |

The two `752d8ec8` runs show the noise: about ±20% even after normalizing by the encoder.

Each change was checked against the old code with a temporary runtime check (reverted before committing):
- value hash: 400k validations matched the string comparisons one to one;
- label-sum set: 300k identical;
- playable memo: 900k repeats with the same result and no state-hash change;
- frozen graph: 200k graphs with identical label sum, state hash and graph arrays.

### Frozen FeatureGraph

`StateEncoder.processState` returns `featureGraph.freeze()`: each node's children map becomes two arrays and the
key → node map is dropped (rebuilt on demand only for `get`/`getGraphArrays`). Tree nodes keep their graphs, so
this is retained heap.

Class histograms, from a single-core probe at a random point in a game, so the totals are not comparable:
- before: 467k graph nodes with 1.8M `HashMap$Node`, 694k `HashMap`, 1.29M `ArrayList`;
- after: 124k graph nodes with 268k `HashMap$Node`, 248k `HashMap`, 337k `ArrayList`.

Estimated retained bytes per graph node fell from about 350 B to about 130 B. Young GC time over a 5-minute
probe (`fz-gc.jfr` vs `gc-1core.jfr`): 39.6 s in 87 pauses vs 44.7 s in 85, within noise.

### Young-generation size (launcher setting, not committed)

5-minute single-core manual probes, ParallelGC, at `752d8ec8`:

| flags | young GC | old GC |
|---|---|---|
| none (`gc-1core`) | 44.7 s / 85 | 9.5 s |
| `-Xmn6g` | 15.8 s / 44 | 7.9 s |
| `-XX:MaxTenuringThreshold=1` | 41.4 s / 88 | 8.1 s |
| both | 16.9 s / 46 | 6.5 s |

`-Xmn6g` cuts GC from ~54 s to ~24 s per ~325 s, about 9% of wall time. Whether to add it to the launcher
scripts is Will's call. The step numbers above run without it.
