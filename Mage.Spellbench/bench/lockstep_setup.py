"""The fixed part of a lockstep matchup's kernel game, built with Spellbench's own library so the Java host never
re-derives a digest: the reset's format, seats and rules (without the starting seat, which each game sets), each
seat's own deck as game_start shows it, and the time control, limits and resources game_start carries.

    python lockstep_setup.py <spellbench checkout> <pauper-v2-catalog.json> <p0 deck> <p1 deck>  > setup.json
"""
import json
import sys

sys.path.insert(0, sys.argv[1] + "/python")
from spellbench.agent_messages import OwnDeck  # noqa: E402
from spellbench.digests import card_name_domain, deck_id  # noqa: E402
from spellbench.messages import CardNameDomain, Limits, Resources, Rules, TimeControl, WireDeck  # noqa: E402

catalog = {d["catalog_id"]: d for d in json.load(open(sys.argv[2]))["catalog"]}
decks = [catalog[name] for name in sys.argv[3:5]]
names = [row["name"] for deck in decks for row in deck["decklist"]]
rules = Rules(opponent_decklist="hidden", mulligan="none", starting_player="host_assigned", starting_seat="p0",
              card_name_domain=CardNameDomain.from_json(card_name_domain(names)),
              extensions=("x_kernel_flat_v4", "x_public_history_v1"), probe=False).to_json()
print(json.dumps({
    "format": "pauper-bo1",
    "seats": [{"seat": seat, "deck": WireDeck.from_json({"catalog_id": d["catalog_id"], "deck_id": deck_id(d["decklist"])}).to_json()}
              for seat, d in zip(("p0", "p1"), decks)],
    "rules": rules,
    "own_decks": [OwnDeck.from_json({"deck_id": deck_id(d["decklist"]), "name": d["name"], "decklist": d["decklist"]}).to_json()
                  for d in decks],
    "time_control": TimeControl(startup_ms=120000, game_start_ms=60000, bank_ms=600000, increment_ms=2000,
                                max_decision_ms=90000, engine_step_ms=120000).to_json(),
    "limits": Limits(max_decisions=100000, max_steps=200000, max_seat_decisions_per_turn=2000,
                     max_seat_decisions_per_game=49999, max_seat_steps_per_game=99999).to_json(),
    "resources": Resources(cpus=1, memory_mb=4096, gpu=False, engine_cpus=1).to_json(),
}))
