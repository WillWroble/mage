"""Plays a tournament config through Spellbench's host and live validator, like engines/xmage/kit/tests/play.py
(preflight, schedule, play_game), without `spellbench run`'s rated-run isolation gate. Local and unrated.

    SPELLBENCH=<spellbench checkout> python drive.py CONFIG.json OUT.jsonl [--shards N] [--record DIR]

--record DIR writes every decision each seat receives, with its answer, to DIR/<game index>-<seat>-<bot>.jsonl.
"""
import json
import os
import secrets
import sys
import time
from concurrent.futures import ProcessPoolExecutor

sys.path.insert(0, os.path.join(os.environ.get("SPELLBENCH", "../spellbench"), "engines/xmage/kit/tests"))
from play import _row  # noqa: E402
from spellbench.arena.config import TournamentConfig  # noqa: E402
from spellbench.arena.drivers import make_driver  # noqa: E402
from spellbench.arena.schedule import EnginePin, game_setup, preflight, schedule  # noqa: E402
from spellbench.host.engine_process import EngineProcess  # noqa: E402
from spellbench.host.game import play_game  # noqa: E402
from spellbench.run_secret import RunSecret  # noqa: E402


class Recorder:
    """A seat driver that writes each decision it is asked and its answer (one JSON line each)."""

    def __init__(self, driver, path):
        self._driver, self._file = driver, open(path, "w")

    def start(self, game_start, *, timeout_s):
        self._driver.start(game_start, timeout_s=timeout_s)

    def choose(self, choose, *, timeout_s):
        choice = self._driver.choose(choose, timeout_s=timeout_s)
        self._file.write(json.dumps({"choose": choose, "candidate_id": choice.candidate_id}) + "\n")
        return choice

    def game_over(self, game_over, *, timeout_s):
        self._file.write(json.dumps({"game_over": game_over}) + "\n")
        self._driver.game_over(game_over, timeout_s=timeout_s)

    def close(self):
        self._file.close()
        self._driver.close()


def worker(args):
    config_path, secret_hex, indexes, record = args
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
            if record:
                seats = {seat: Recorder(driver, os.path.join(record, f"{index}-{seat}-{spec.name}.jsonl"))
                         for (seat, spec), driver in zip(context.seat_specs, seats.values())}
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
    record = sys.argv[sys.argv.index("--record") + 1] if "--record" in sys.argv else None
    if record:
        os.makedirs(record, exist_ok=True)
    config = TournamentConfig.from_json(json.load(open(config_path)))
    secret_hex = secrets.token_hex(32)
    print(json.dumps({"run_secret": secret_hex}), file=sys.stderr, flush=True)  # to replay any game of the run
    indexes = [c.game_index for c in schedule(config, RunSecret.from_hex(secret_hex))]
    chunks = [indexes[i::shards] for i in range(shards)]
    # unlike multiprocessing.Pool, the executor fails (BrokenProcessPool) instead of waiting forever when a shard dies
    with ProcessPoolExecutor(shards) as pool:
        results = list(pool.map(worker, [(config_path, secret_hex, chunk, record) for chunk in chunks]))
    rows = sorted((r for rs in results for r in rs), key=lambda r: r["game_index"])
    with open(out, "w") as f:
        for r in rows:
            f.write(json.dumps(r, sort_keys=True) + "\n")
    print(json.dumps({"games": len(rows), "run_secret": secret_hex}))


if __name__ == "__main__":
    main()
