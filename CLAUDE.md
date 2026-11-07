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
3. **MageZero as an agent.** Not to be started without Will. Open questions to bring to him, with options:
   - The agent only gets the neutral board view, but MageZero's search copies an XMage game and its encoder reads
     XMage state. Rebuild a game from the observation and decklists (determinized, PIMC-style), or rate it only
     on the XMage engine through an extension (which rated runs restrict, §14)?
   - MageZero's models are deck-specific (Faeries only today) and were trained with the opponent's hand visible;
     Spellbench hides it. The PIMC games so far played much worse than perfect-information ones.

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

## Environment

- JDK 21, Maven. XMage needs a large heap; ZGC is preferred.
- Cloud sessions have 4 vCPUs and 16 GB. If the build or a test does not fit, say so with the numbers.
- Spellbench itself is Python; install it from its repo for the conformance tools.
