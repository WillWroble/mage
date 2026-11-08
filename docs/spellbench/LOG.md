# Spellbench log

Runs and checks for `Mage.Spellbench` (MageZero's policy-only player as a Spellbench v2 agent). Newest last.
Every result names its command and commits. Paths under `$S` are a scratch directory.

## 2026-11-08 — Deck lists vs the pauper-kernel pool

Our `Mage.MageZero/decks/Pauper-*.txt` against the pauper-kernel decks as Spellbench's kit records them
(`engines/xmage/kit/register/pauper-kernel-decks.json`, from mtg-kernel `a4e1474b`, `data/runtime_decks_v1.json`),
main deck only, card name and count:

| Deck | Ours | pauper-kernel | Result |
|---|---:|---:|---|
| Affinity | 60 | 60 | identical |
| Burn | 60 | 60 | identical |
| CawGates | 60 | 60 | identical |
| Elves | 60 | 60 | identical |
| Faeries | 60 | 60 | identical |
| Rally | 60 | 60 | identical |
| Spy | 60 | 60 | identical |
| Wildfire | 60 | 60 | identical |

Not run: the engine's `validate_deck`. Spellbench's XMage engine offers only `fdn-limited-bo1` and
`standard-2022-25-bo1` (no Pauper format), and the pauper-kernel engine (mtg-kernel) is private.

## 2026-11-08 — Smoke test: 2 games, ver15 vs `heuristic`

- Agent: `Mage.Spellbench` (`--entry mz`, version `0.3.0+1f9e47ebcd47`), this fork at `714e4b33` plus the
  uncommitted module; model `MZ-Pauper-Faeries-v15` (sha256 `96e55040…eba56`) served on CPU by
  `WillWroble/MageZero` `graph-encoder-test` `f91e766` (`src/magezero/server.py`).
- Engine and host: Spellbench `27c9009`, its XMage engine (`engines/xmage/scripts/build.sh`, XMage `fd40ad5c` plus
  X-P1 to X-P4) and live validator, through `engines/xmage/kit/tests/play.py`. The format label is
  `standard-2022-25-bo1` with our Pauper decks passed inline (`--dck`), since the engine has no Pauper format.

```bash
python tests/play.py --games 2 --format standard-2022-25-bo1 --dck Pauper-Faeries.txt --dck Pauper-Burn.txt \
  --kit-name magezero-policy --kit-version 0.3.0+1f9e47ebcd47 \
  --kit-cmd '["bash", "Mage.Spellbench/scripts/agent.sh", "--db", "$S/fork-db/db", "--model", "MZ-Pauper-Faeries-v15", ...]' \
  --opponent heuristic -- bash engines/xmage/scripts/engine.sh --build $S/engine/build --db $S/engine-db/db
```

| Game | p0 | p1 | Deck | Result | Decisions | Wall |
|---|---|---|---|---|---:|---:|
| 0 | magezero-policy | heuristic | Faeries mirror | p0 win (life) | 428 | 21 s |
| 1 | heuristic | magezero-policy | Faeries mirror | p1 win (life) | 389 | 20 s |

0 violations, 0 forfeits, 0 host halts. The agent's 451 answered decisions were all tagged `bot` (no kit fallback):
297 single-candidate, 72 priority, 47 combat, 18 dialogs, the rest pregame and continuations. The model server
answered every policy query.

## 2026-11-08 — Anchors: ver15 vs Spellbench's builtins, 8 Pauper decks

Same setup as the smoke test (Spellbench `27c9009` XMage engine and live validator, Pauper decks inline under the
`standard-2022-25-bo1` label, ver15 on CPU), agent at `cbfae60f`. 32 games per opponent: each deck 2 seat-swapped
pairs, both bots piloting the same deck (`play.py` schedule), 2 shards.

```bash
python tests/play.py --games 32 --shards 2 --format standard-2022-25-bo1 --dck Pauper-<deck>.txt ... (8 decks) \
  --kit-name magezero-policy --kit-version 0.3.0+1f9e47ebcd47 --kit-cmd '[... agent.sh ...]' --opponent <bot> -- <engine>
```

| Deck | vs heuristic | vs uniform | vs first |
|---|---:|---:|---:|
| Affinity | 4/4 | 4/4 | 4/4 |
| Burn | 4/4 | 4/4 | 4/4 |
| CawGates | 3/4 | 2/4 | 4/4 |
| Elves | 4/4 | 3/4 | 4/4 |
| Faeries | 4/4 | 4/4 | 4/4 |
| Rally | 3/4 | 4/4 | 4/4 |
| Spy | 3/4 | 3/4 | 4/4 |
| Wildfire | 1/4 | 3/4 | 4/4 |
| **Total** | **26/32 (81%)** | **27/32 (84%)** | **32/32 (100%)** |

All 96 games natural, 0 violations, 0 forfeits, 0 host halts. Kit fallbacks (`wrapper`): 85 of 7,862 answered
decisions vs heuristic, 90 of 10,876 vs uniform, 39 of 6,565 vs first, all in dialog kinds the kit does not
synthesize yet (`choose_cost_target` plan values, `choose_replacement`, `order_pick`, `distribute`,
`optional_cost`, `choose_cast_method`); none in priority. These samples are small (±15 points at 32 games) and
this is XMage's engine, not mtg-kernel's.

An earlier heuristic batch at `e219f496` (23/32) is discarded: 119 priority decisions failed because `choose(Choice)`
and `makeChoiceAmount` went through MageZero's search root, which a kit world doesn't set up (fixed in `cbfae60f`).

## 2026-11-08 — What hiding the opponent's hand costs ver15 (policy only, in XMage)

ver15 policy only (greedy) as Pauper-Faeries vs the offline heuristic search (300 sims), 200 games, with
`player_a.hiddenInfo.see_opponent_hand: false` (the encoder sees only the opponent's hand size). Configs are
MageZero's `kaggle/configs/onpolicy-<deck>.yml` with only that flag, the thread count (8) and the shard paths
changed. XMage built from this branch at `714e4b33` (before 05766b34's auto pass), the same player code as the
visible-hand baselines; model served on CPU.

```bash
java -jar lib/mage-magezero-1.4.58.jar hidden-<deck>.yml
```

| Opponent | Hand hidden | Hand visible (Kaggle, `results/onpolicy.md`) | Hand visible (Will, Nov 7) |
|---|---:|---:|---:|
| Burn | 56.0% (112/200, 0 failed) | 52.0% | 45.5% |
| mirror | 39.5% (79/200, 0 failed) | 44.0% | 39.0% |

At 200 games (±3.5 points) hiding the hand costs nothing measurable: Burn is above both visible runs, the mirror
between them. The visible runs themselves differ by 5–6 points.
