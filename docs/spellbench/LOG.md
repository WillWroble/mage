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

## 2026-11-08 — ver15 vs the Archon panel on mtg-kernel (pauper-kernel-v2 setup, unrated)

ver15 as our agent (greedy policy only, opponent's hand hidden) against Jack's Archon 1–3 (`a48`, `c12`, `g115`,
MIT, huggingface.co/mtg-kernel, loaded with their rated model states), on mtg-kernel `92ec2393` (the Archons' rated
commit; built here with 16 codegen units, catalog `af8e1b91…` as the model card) through Spellbench `27c9009`'s host
and live validator, with `pauper-kernel-v2`'s deck pool, extensions, native-id audit and time control. Every game is
a mirror (both seats one deck), seat-swapped pairs over the 8 decks, as on the board. Played with
`Mage.Spellbench/bench/drive.py` (not `spellbench run`, whose rated-run isolation gate refuses unsandboxed bots).

- Local: 16 pairs per Archon (96 games, 2 shards), agent at `ecc9e428`.
- RunPod: `bench/launch.py --name sb-archon --cpu 32 --env PAIRS=32 SHARDS=8` at `6febcdb6`, CPU pod, $1.12/h,
  1 h 10 min, about $1.31. 168 of 192 games: one of the 8 shards never reported (its 24 games are ver15 in the second
  seat of Elves and Faeries pairs) and `multiprocessing.Pool` waited on it, so the pod was deleted by hand; the
  driver now uses `ProcessPoolExecutor`, which fails instead (`a57afc25`+).
- ver15 from huggingface.co/inkling616/MZ-Pauper-Faeries-v15 on the pod: the same 215 weight tensors and vocabularies
  as `experiment_models/Pauper-Faeries/ver15` (the file only lacks the optimizer state).

| Archon (board Elo, 2026-10-28) | ver15 wins | Win rate (95%) |
|---|---:|---:|
| a48 (1179) | 51/88 | 58% ±10 |
| c12 (1211) | 50/88 | 57% ±10 |
| g115 (1381) | 34/88 | 39% ±10 |

By the deck both seats pilot:

| Deck | a48 | c12 | g115 |
|---|---:|---:|---:|
| Affinity | 12/12 | 9/12 | 4/12 |
| Burn | 8/12 | 10/12 | 8/12 |
| CawGates | 6/12 | 7/12 | 7/12 |
| Elves | 3/8 | 5/8 | 3/8 |
| Faeries | 5/8 | 6/8 | 3/8 |
| Rally | 6/12 | 7/12 | 7/12 |
| Spy | 10/12 | 2/12 | 2/12 |
| Wildfire | 1/12 | 4/12 | 0/12 |

264 games, all natural, 0 violations, 0 forfeits, 0 halts (the Archons' bot fails closed, so none of its decisions
was dropped). ver15's side, local logs (14,091 decisions): the model answered every priority (6,131), combat
(2,741) and dialog decision the kit supports; 3,667 were the kit's mana-only passes and 32 single candidates; 438
(3.1%) fell back to the kit's lowest or declining candidate: 5 where the model scored no option (library cards
missing from the encoding, fixed in `a57afc25`: 45/45 such answers by the model in 8 Elves and Spy games) and the
rest in dialog kinds the kit does not synthesize yet (`order_pick`, `choose_cost_target`, `choose_option`,
`arrange_card`, optional costs). No ver15 decision used more than half its clock.

Reading: about level with or above Archons 1 and 2 and below Archon 3. ver15 pilots Faeries in training only;
Wildfire and Spy (vs c12 and g115) are its weakest decks. Four games against a48 were discarded before this run:
the model server had died, so every decision fell back (`agent.sh` now refuses to start without it, `ecca3004`).
