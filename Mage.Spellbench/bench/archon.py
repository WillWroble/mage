"""Writes the config for MageZero's policy agent vs the Archon panel (a48, c12, g115) on mtg-kernel, as on
Spellbench's pauper-kernel-v2 board (engine, extensions, time control), local and unrated. Also writes each Archon's
local scorer config (the model card's step 2: its path files point at this machine).

    python archon.py --spellbench DIR --kernel DIR --archons DIR --db DIR --work DIR --out DIR [--pairs N] [--decks D ...] > config.json

--archons holds archon-1-pauper, archon-2-pauper and archon-3-pauper from huggingface.co/mtg-kernel.
"""
import argparse
import hashlib
import json
import pathlib
import sys

ARCHONS = [("a48", "archon-1-pauper", "a", "774e936da26468358257774fe41ae94578265b331d2edb49d017c0835b08699a"),
           ("c12", "archon-2-pauper", "b", "5c14c025e3cc87fb2c3eea28e1dbd770d7d257344174296d6259020f720da9de"),
           ("g115", "archon-3-pauper", "b", "8139016ca561961714f25e22a9d6f7fc888548fc332e45b6bce402dfc43159f2")]
KERNEL_COMMIT = "92ec2393db34bea805a85991163458b9ff890f57"
AGENT_VERSION = "0.3.0+1f9e47ebcd47"  # the mz entry with --model MZ-Pauper-Faeries-v15


def scorer_config(repo: pathlib.Path, name: str, side: str) -> pathlib.Path:
    w, out = repo / "weights", repo / "local"
    out.mkdir(exist_ok=True)
    imp = json.loads((w / f"play-import-{side}.json").read_text())
    imp["initialization"]["path"] = (w / f"init-generated-{side}/initialization.json").as_posix()
    imp["parameters"]["path"] = (w / f"init-generated-{side}/parameters.f32le").as_posix()
    (out / f"play-import-{side}.json").write_text(json.dumps(imp, separators=(",", ":")))
    cfg = json.loads((w / f"{name}.scorer.json").read_text())
    cfg["source"]["play_import"] = {"path": (out / f"play-import-{side}.json").as_posix(),
                                    "sha256": hashlib.sha256((out / f"play-import-{side}.json").read_bytes()).hexdigest()}
    cfg["source"]["checkpoint"]["path"] = next(p for p in w.glob("*.json") if len(p.stem) == 64).as_posix()
    path = out / f"{name}.scorer.json"
    path.write_text(json.dumps(cfg, indent=2))
    return path


def main():
    p = argparse.ArgumentParser()
    for a in ["spellbench", "kernel", "archons", "db", "work", "out"]:
        p.add_argument(f"--{a}", required=True, type=lambda s: pathlib.Path(s).resolve())
    p.add_argument("--pairs", type=int, default=16, help="seat-swapped pairs per Archon (8 per rotation of the decks)")
    p.add_argument("--decks", nargs="*", help="only these pool decks (default: the board's eight)")
    p.add_argument("--agent", default=str(pathlib.Path(__file__).resolve().parent.parent / "scripts/agent.sh"))
    a = p.parse_args()
    py, sb, k = sys.executable, a.spellbench, a.kernel
    bots = [{"name": "magezero-policy", "version": AGENT_VERSION, "type": "subprocess",
             "command": ["bash", a.agent, "--db", str(a.db), "--work", str(a.work), "--model", "MZ-Pauper-Faeries-v15",
                         "--log-dir", str(a.out / "logs")]}]
    for name, repo, side, state in ARCHONS:
        bots.append({"name": name, "version": "pauper-v2.0.0", "type": "subprocess",
                     "command": [py, f"{sb}/integrations/mtg_kernel/kernel_flat_bot.py", "--scorer",
                                 f"{k}/target/release/spellbench_scorer_v1", "--config",
                                 str(scorer_config(a.archons / repo, name, side)), "--name", name,
                                 "--version", "pauper-v2.0.0", "--expect-model-state", state]})
    bench = json.loads((sb / "benchmarks/pauper-kernel-v2/benchmark.json").read_text())
    config = {"schema": "spellbench-tournament-config/v2", "tournament_dir": str(a.out / "t"), "format": bench["format"],
              "deck_pool": [{"catalog_id": d} for d in (a.decks or bench["deck_pool"])],
              "engine": {"command": [py, f"{sb}/integrations/mtg_kernel/kernel_engine_v2.py",
                                     "--bridge", f"{k}/target/release/agent_bridge_v1",
                                     "--catalog", f"{k}/pauper-v2-catalog.json",
                                     "--source-revision", KERNEL_COMMIT, "--known-cards"]},
              "extensions": bench["extensions"], "native_id_audits": bench["native_id_audits"],
              "bots": bots, "matchups": [["magezero-policy", name] for name, *_ in ARCHONS],
              "pairs_per_matchup": a.pairs, "stats_seed": bench["stats_seed"], "time_control": bench["time_control"]}
    (a.out / "logs").mkdir(parents=True, exist_ok=True)
    json.dump(config, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
