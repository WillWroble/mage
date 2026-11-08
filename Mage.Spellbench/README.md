# Mage.Spellbench

MageZero's policy-only player as a [Spellbench](https://github.com/jackmaiorino/spellbench) protocol v2 agent, so it
can play on any v2 engine (Spellbench's XMage engine, and mtg-kernel for the `pauper-kernel` boards).

The agent sees only its seat's messages. For each decision, Spellbench's XMage agent kit rebuilds an XMage game from
the observation (hidden cards sampled from the decklists, seeded from `agent_seed`), and `KitPolicy` answers it:
the decision is encoded as `MCTSPlayer` encodes it in training, scored once by the MageZero model server, and the
highest-scoring offered choice is played (greedy, no search). The opponent's hand is never encoded, only its size.

## Contents

| Path | What |
|---|---|
| `spellbench/kit/xmage/KitPolicy.java` | the decider (new): MageZero's policy on a kit world |
| `spellbench/kit/core`, `spellbench/kit/xmage` | Spellbench's agent kit (front, sampler, world builder, mapping) |
| `mage/player/spellbench` | the parts of Spellbench's XMage engine overlay the kit uses |
| `mage/player/cabt` | CABT (NMaass/mtg-rl-tools), vendored unchanged |
| `mage/player/ai/Kit*.java` | the kit's autopay planner and budgets |

Taken from Spellbench `27c9009` (`engines/xmage/kit`, `overlay`, `vendor/cabt`), MIT; CABT is MIT
(`LICENSE-spellbench.txt`, `LICENSE-cabt.txt`). Changes from upstream:

- `KitMad` (XMage's MAD bot) is replaced by `KitPolicy`, and the MCTS entry, the slice harnesses and the engine's
  server and test mains are left out. `Entries` gains the `mz` entry (`magezero-policy`); `--model ID` binds the
  checkpoint's identity into the bot version.
- This XMage predates upstream's `DoubleFacedCard`: modal double-faced cards use `ModalDoubleFacedCard`, and
  transforming cards (a front card with a second face) enter transformed when the observation names the back face.
- `RandomUtil` gained the kit's opt-in `Source` (X-P1 and X-P3's `newId()`); with no source installed it behaves as
  before. The call-site patches (X-P2 to X-P4) are not applied.

## Run

```bash
mvn -pl Mage.Spellbench -am package -DskipTests
python src/magezero/server.py --model <model dir> --port 50052        # in WillWroble/MageZero
Mage.Spellbench/scripts/agent.sh --db <card db dir> --model MZ-Pauper-Faeries-v15
```

The card database template is a `db` directory built once by `mage.cards.repository.CardScanner.scan()`. Results are
in `docs/spellbench/LOG.md`.
