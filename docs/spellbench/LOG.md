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
three times successfully. Full games so far: 10 (41, 45, 53, 62-64, 67-69, 71): one Archon win (53), nine MageZero
wins; the winner was the same in both engines every time.

### 2026-11-10, against g115 (Archon 3), commits ed292649 and b030aa3d

Same setup with the Archon g115 (`archon-3-pauper`, model state 8139016c...) piloting Burn.
- First batch of 6 (ed292649's predecessor aa8957a5): 3 full games (g115 2, MageZero 1), 3 divergences: two Ninjas
  of the Deep Hours swapped when matched across engines (the kernel keeps combat flags past combat), XMage asking a
  trigger order while the kernel first asked the discard order, Of One Mind (kernel bug).
- Batch of 6 on b030aa3d: 4 full games (MageZero 3, g115 1), 2 divergences, both the Of One Mind kernel bug (once as
  the missing cast, once as the kernel charging full cost and leaving MageZero no mana for its next spell).

### 2026-11-10, benchmark schedule: g115 across the 8 Pauper decks, both seats

MageZero (ver15) pilots Faeries; g115 pilots each of the 8 `pauper-kernel` decks (our deck files match the board's
lists card for card; CawGates' double-faced card is named by its front face in XMage and the native kernel). Each
deck is played in both kernel seats and both turn orders (`goes_first`), two games at a time, from a build snapshot
that is refreshed between games as fixes land, so early games ran on older builds. Results:
`python Mage.Spellbench/bench/lockstep_results.py <bench root>`.

First cycle (32 games, builds 89c41783 to bae332d2), plus debugging reruns: 38 games, 4 full (MageZero 3, g115 1),
34 divergences. Fixed from them: the Archon's mana payment (Affinity's bridges), library order after a shuffle
(Elves' Generous Ent), CawGates' card names, a stack ability's source after it left (Cryogen Relic), MageZero's
priority round after a resolution, the Archon's mana colors (Spy's Lotus Petal).

### 2026-11-10, one build: 35f36f5b, two schedule cycles (64 games)

    archon  deck      games  full MageZero Archon diverged
    g115    Affinity      8     3        3      0        5
    g115    Burn          8     4        1      3        4
    g115    CawGates      8     1        1      0        7
    g115    Elves         8     0        0      0        8
    g115    Faeries       8     2        2      0        6
    g115    Rally         8     5        2      3        3
    g115    Spy           8     4        4      0        4
    g115    Wildfire      8     3        3      0        5
    g115    all          64    22       16      6       42

Completion 34%. Stops: Of One Mind (kernel bug) 9, XMage past the kernel's priority for the Archon 8, XMage past the
kernel's decision for MageZero 3, kernel halts 3, others 19. Win rates on finished games are biased by which games
finish (see the analysis in the session); not a strength estimate yet.

Speed: each game spent ~32 of its ~36 s rebuilding XMage's card database (the per-game copy was built by an older
jar). A database built once by the current jar and copied per game loads without rebuilding and with every deck card
(checked); a full game now takes ~20-25 s including the JVM.

### 2026-11-10/11, continuous fixing (builds up to 97b0f8fb)

Engine setup changes: the kernel's bridge carries two more local fixes (Of One Mind sees a Human spelled HUMAN; a
library arrangement keeps cards whose position a player knows), and the engine now runs without `--known-cards`
(the wrapper's hand-knowledge tracker failed closed with "known hand facts exceed the hand count"; neither the Archon
bot nor the comparison reads `known`, but the board's engine runs with it).

Fixed since the one-build table: two-option effect choices by XMage's named options (Winding Way), the Archon's
effect options kept when the kernel runs ahead (Ninja's draw), embalmed token names, a stale blocking flag
(CR 506.4), MageZero's unasked yes/no, mana made while paying not recorded as an action, dies-trigger sources,
tapping a sacrificed land first (Reckoner's Bargain), a selection the kernel ended, digs with forced destinations,
Brainstorm's put-back order, combat declarations XMage asks nothing for, library orders the kernel cannot take yet.

On builds since 2a29b4de: 36 games, 17 full (47%); Burn, Faeries and Rally finish nearly every game.

Known gaps (kernel models differ): Avenging Hunter's initiative (XMage's Undercity room trigger and command zone),
the kernel's autopay using Heap Gate's filtered mana where XMage's payer does not.

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

### 2026-11-11, commit fd157773

Fixed from the schedule on 674141cb:
- the Archon's mana: XMage's AI payer could pay a generic mana with Heap Gate's filter ({1},{T}: any color), spend a
  second Gate on it and come up short, then pay with lands the kernel left untapped (Prismatic Strands refused; Journey
  to Nowhere paid with Citadel Gate). While paying for the Archon, a mana ability that costs mana is used only when the
  kernel tapped more permanents than the mana left to pay (it filtered too);
- combat damage division when several attackers are blocked: the group is the one whose blockers XMage's dialog names
  (Nyxborn Hydra and Elvish Mystic, each blocked by one creature);
- the initiative: XMage puts Undercity in the command zone and sources its rooms' triggers there, the kernel keeps
  only the designation and sources the triggers by the object that gave it; compared without the dungeon card and
  without the initiative holder's trigger sources;
- `exiled_by` for Mesmeric Fiend (its leaving returns the card);
- the Archon's X (Nyxborn Hydra): XMage's AI capped it by the mana in its pool, which is still empty when X is
  announced.

### 2026-11-11, one build: 0c6c10b2, two schedule cycles (64 games)

Fixed since fd157773 (commits aacfe940 to 0c6c10b2, and the kernel patch b046d93b):
- a search XMage resolves without asking (one Forest for Land Grant): at the Archon's next priority at the same point,
  with less on XMage's stack, the kernel's pending dialog is the Archon's to answer;
- the Archon's choice of a card in MageZero's revealed hand (Mesmeric Fiend), hand cards by name (Brainstorm's two
  Lorien Revealed), a search's same-named picks (three Squadron Hawks were one), a selection the kernel ends;
- blocking a hexproof attacker (XMage's block dialog is a target), an activated ability's index over all of its
  object's abilities (Twisted Landscape's cycling from the hand), a forced X (Nyxborn Hydra with no mana for more);
- every mana payment of the Archon's from the permanents the kernel tapped (Nihil Spellbomb's {B});
- a dig's optional pick, which XMage asks first as a yes/no (Lead the Stampede);
- MageZero's orders: Balustrade Spy's mill is not an arrangement; the initiative's taking on combat damage, which
  the kernel puts in MageZero's trigger order and XMage gives the initiative holder, goes last;
- mtg-kernel (local patch): protection from monocolored only on the battlefield (Guardian of the Guildpact on the
  stack could not be countered).

    archon  deck      games  full MageZero Archon draws diverged
    g115    Affinity      7     7        5      2     0        0
    g115    Burn          8     6        3      3     0        2
    g115    CawGates      7     1        1      0     0        6
    g115    Elves         8     3        2      1     0        5
    g115    Faeries       8     6        4      2     0        2
    g115    Rally         8     8        3      5     0        0
    g115    Spy           8     8        8      0     0        0
    g115    Wildfire      7     4        4      0     0        3
    g115    all          61    43       30     13     0       18

Completion 70% (34% on 35f36f5b). Three more games did not start (XMage's HDF5 native library failed to load, with
three JVMs starting at once; no game state). A first run of this schedule is discarded: the ver15 model server had
stopped with a worker restart, so MageZero played without its model (every game stopped at its first discard);
`bench.sh` now refuses to start without the server. Win rates on finished games are biased by which games finish.

Open: CawGates (Archon's casts refused: Sacred Cat; Heap Gate/Gates payment; priority skipped), Wildfire (Fanatical
Offering refused), blockers XMage did not declare (Nyxborn Hydra, Humbling Elder), MageZero's Of One Mind.
