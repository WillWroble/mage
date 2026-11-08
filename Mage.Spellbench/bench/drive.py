"""Plays a tournament config through Spellbench's host and live validator, like engines/xmage/kit/tests/play.py
(preflight, schedule, play_game), without `spellbench run`'s rated-run isolation gate. Local and unrated.

    SPELLBENCH=<spellbench checkout> python drive.py CONFIG.json OUT.jsonl [--shards N]
"""
import json
import multiprocessing
import os
import secrets
import sys
import time

sys.path.insert(0, os.path.join(os.environ.get("SPELLBENCH", "../spellbench"), "engines/xmage/kit/tests"))
from play import _row  # noqa: E402
from spellbench.arena.config import TournamentConfig  # noqa: E402
from spellbench.arena.drivers import make_driver  # noqa: E402
from spellbench.arena.schedule import EnginePin, game_setup, preflight, schedule  # noqa: E402
from spellbench.host.engine_process import EngineProcess  # noqa: E402
from spellbench.host.game import play_game  # noqa: E402
from spellbench.run_secret import RunSecret  # noqa: E402


def worker(args):
    config_path, secret_hex, indexes = args
    config = TournamentConfig.from_json(json.load(open(config_path)))
    secret = RunSecret.from_hex(secret_hex)
    pin = EnginePin()
    setup = preflight(config, secret, pin=pin)
    contexts = {c.game_index: c for c in schedule(config, secret)}
    engine = EngineProcess(list(config.engine_command), timeout_s=config.time_control.startup_ms / 1000)
    pin.check(engine.hello().engine)
    rows = []
    try:
        for index in indexes:
            context = contexts[index]
            seats = {seat: make_driver(spec, config.time_control) for seat, spec in context.seat_specs}
            start = time.monotonic()
            game = game_setup(config, setup, context, secret)
            try:
                result = play_game(game, engine=engine, seats=seats)
            finally:
                for driver in seats.values():
                    driver.close()
            row = _row(context, result, time.monotonic() - start)
            rows.append(row)
            print(json.dumps(row, sort_keys=True), flush=True, file=sys.stderr)
            if row["host_halt"]:
                engine.close()
                engine = EngineProcess(list(config.engine_command), timeout_s=config.time_control.startup_ms / 1000)
                pin.check(engine.hello().engine)
    finally:
        engine.close()
    return rows


def main():
    config_path, out = sys.argv[1], sys.argv[2]
    shards = int(sys.argv[sys.argv.index("--shards") + 1]) if "--shards" in sys.argv else 1
    config = TournamentConfig.from_json(json.load(open(config_path)))
    secret_hex = secrets.token_hex(32)
    indexes = [c.game_index for c in schedule(config, RunSecret.from_hex(secret_hex))]
    chunks = [indexes[i::shards] for i in range(shards)]
    with multiprocessing.Pool(shards) as pool:
        results = pool.map(worker, [(config_path, secret_hex, chunk) for chunk in chunks])
    rows = sorted((r for rs in results for r in rs), key=lambda r: r["game_index"])
    with open(out, "w") as f:
        for r in rows:
            f.write(json.dumps(r, sort_keys=True) + "\n")
    print(json.dumps({"games": len(rows), "run_secret": secret_hex}))


if __name__ == "__main__":
    main()
