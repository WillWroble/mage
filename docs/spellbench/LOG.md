# Spellbench log

## Lockstep games (MageZero in XMage, Archon in mtg-kernel)

Setup: MageZero ver15 (`ComputerPlayerPolicyOnlyLockstep`, greedy, opponent's hand hidden) pilots Pauper-Faeries in
XMage; the Archon a48 pilots Pauper-Burn in mtg-kernel 92ec2393 (patched: `Mage.Spellbench/kernel/`), seated as p1
through `ComputerPlayerSpellBench`. One local game per process (`play.sh`: `SpellbenchMain run1/config.yml` with
`-Dspellbench.lockstep=settings.json`). Restriction: XMage players get priority only in the kernel's windows
(precombat main, declare attackers, declare blockers, postcombat main).

### 2026-11-10, commit f522c9d5 (+ temporary logging around the Archon's casts, no behaviour change)

First full game: `21a679b5`, XMage turn 11, no divergence. The kernel ended naturally (`p0_win`, 96 steps, 93 with a
real choice) and XMage's winner is p0: MageZero beat the Archon in both engines.

Batch of 4: 1 full game, 3 divergences:
- 2 × XMage refuses the Archon's Fireblast by its alternative cost (open: XMage never asks for the sacrifice);
- 1 × XMage passed the kernel's priority for the Archon while MageZero's spell was on the stack (open).

### 2026-11-10, commit aa8957a5

Fixes: Fireblast (XMage core: a computer player's forced multi-target auto-choice stopped after one target, so
"sacrifice two Mountains" failed; `TargetImpl.chooseTarget`), and MageZero follows the kernel's whole priority surface
(mtg-kernel `surface.rs`: also one action per round in declare attackers/blockers, and no priority right after its own
stack-using action).

Batch of 6: 4 full games (all MageZero wins in both engines, 51 to 179 kernel decisions), 2 divergences (open: a
Guttersnipe/end-step mismatch, a draw-step mismatch). Earlier batch on 8c4866bb: 3 of 6 full; the Archon cast Fireblast
three times successfully. Full games so far: 9, two of them Archon wins (game 53) or MageZero wins (the rest), winners
always equal across engines.

Rules disagreements seen in earlier runs (counted as divergences, not worked around):
- Of One Mind: XMage casts it reduced to {U} with a Human and a non-Human; the kernel does not see Humbling Elder as a
  Human (its card data has `Subtype::HumanAllCaps`; Of One Mind checks `Subtype::Human`), so no reduction.
- Ninjutsu: the kernel resolves it without the stack, so it cannot be responded to (CR 702.49 uses the stack).

Spec gaps in the kernel's protocol engine (Jack's wrapper), found while matching:
- a scry's top-order picks reach the native chooser unreversed, so the top order comes out reversed (patched locally
  in `kernel/spellbench-27c9009.patch`);
- `cast_spell` always has `method: null`, also when no `choose_cast_method` follows (spec 7.2);
- the stack is listed top first (spec 6.4: index 0 is the bottom); read bottom first here, the Archon's input
  unchanged.
