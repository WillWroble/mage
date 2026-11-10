"""Runs bench/pod.sh (MageZero's policy agent vs the Archon panel) on a RunPod CPU pod, with the same pod settings and
naming as WillWroble/MageZero's runpod/launch.py (its --list and --reap work on these pods). The pod clones this repo
at the pushed commit (push first) and MageZero (for runpod/java.sh and the model server), writes its results to
/workspace/runs/<name> and its exit code to DONE there.

    python Mage.Spellbench/bench/launch.py --name sb-archon --cpu 32 --hours 4 --env PAIRS=32 SHARDS=8
"""
import argparse
import json
import os
import shlex
import subprocess
import urllib.error
import urllib.request

API = "https://rest.runpod.io/v1"
VOLUME = "0ksqf5sep2"  # magezero-data, EU-RO-1
DATA_CENTER = "EU-RO-1"

POD = '''export OUT=/workspace/runs/{name}; mkdir -p $OUT; rm -f $OUT/DONE
exec > $OUT/boot.log 2>&1  # the boot steps' output, so a failed clone shows why
set -x
apt-get update -qq; apt-get install -y -qq git curl >/dev/null  # the image has both: some hosts can't reach apt
git clone -q --filter=blob:none https://github.com/WillWroble/mage /tmp/mage && git -C /tmp/mage checkout -q {commit}
GIT_LFS_SKIP_SMUDGE=1 git clone -q --depth 1 -b {magezero} https://github.com/WillWroble/MageZero /tmp/code && cd /tmp/code
MAGE_COMMIT={commit} {env} timeout {hours}h bash /tmp/mage/Mage.Spellbench/bench/pod.sh > $OUT/script.log 2>&1
echo $? > $OUT/DONE
sleep infinity
'''


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--name", required=True, help="the run's directory under /workspace/runs; the pod is mz-<name>")
    p.add_argument("--cpu", type=int, default=32, help="vCPUs")
    p.add_argument("--hours", type=float, default=4, help="time limit for pod.sh")
    p.add_argument("--env", nargs="*", default=[], help="KEY=VALUE pairs for pod.sh (PAIRS, SHARDS, ...)")
    p.add_argument("--magezero", default="graph-encoder-test", help="MageZero branch")
    a = p.parse_args()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not subprocess.run(["git", "branch", "-r", "--contains", commit], capture_output=True, text=True).stdout:
        raise SystemExit(f"push {commit} first: the pod clones it from GitHub")
    env = " ".join(k + "=" + shlex.quote(v) for k, v in (e.split("=", 1) for e in a.env))
    script = POD.format(name=a.name, commit=commit, magezero=a.magezero, env=env, hours=a.hours)
    body = {"name": f"mz-{a.name}", "computeType": "CPU", "cpuFlavorIds": ["cpu5c", "cpu3c"], "vcpuCount": a.cpu,
            "imageName": "buildpack-deps:22.04", "containerDiskInGb": 60, "dockerStartCmd": ["bash", "-c", script],
            "networkVolumeId": VOLUME, "volumeMountPath": "/workspace", "dataCenterIds": [DATA_CENTER]}
    req = urllib.request.Request(f"{API}/pods", method="POST", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {os.environ['RUNPOD_API_KEY']}",
                                          "Content-Type": "application/json", "User-Agent": "magezero"})
    try:
        with urllib.request.urlopen(req) as r:
            pod = json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"POST pods: {e.code} {e.read().decode()}")
    print(pod["id"], pod.get("costPerHr"), commit)


if __name__ == "__main__":
    main()
