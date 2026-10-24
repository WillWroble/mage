# Brief: GNN inference speed

## Goal
Make MageZero's GNN evaluation keep up with the game generator. Measure first, then remove what caps throughput.
All timing runs need Will's RTX 4070 (12 GB). A cloud session has no GPU: it can read and change code, not measure.

## Where things are
- Model and server: `WillWroble/MageZero`, branch `graph-encoder` (`de22504`, 2026-10-04)
  - `src/magezero/model.py`: `NetGraph`. d=512, 4 heads, FF 1024; 2 bottom-up local passes, each a `LocalLayer`
    per node type run in order ABILITY -> CARD -> PERMANENT -> STACK_OBJECT -> ZONE -> PLAYER -> ROOT;
    then a 2-layer global TransformerEncoder over [CLS, internal nodes] padded to the batch's largest state; 4 heads.
  - `src/magezero/server.py`: Flask on waitress, one worker thread that batches queued requests, msgpack.
- Client: `WillWroble/mage`, branch `graph-encoder`,
  `Mage.Server.Plugins/Mage.Player.AI.RL/src/mage/player/ai/RemoteModelEvaluator.java`.

## What currently caps a batch (read from the code, not measured)
| setting | where | value | effect |
|---|---|---|---|
| waitress threads | `server.py:43` | `threads=6` | each `/evaluate` call holds a thread until its batch is done, so at most 6 requests are ever queued |
| client states per call | `RemoteModelEvaluator.java:41` | `maxBatchSize = 4` | with the above: at most ~24 states per forward pass |
| client concurrent calls | `RemoteModelEvaluator.java:36` | `MAX_CONCURRENT_CALLS = 16` | doesn't help past the 6 server threads |
| client flush interval | `RemoteModelEvaluator.java:40` | `batchInterval = 2500` µs | |
| server batch cap | `server.py:20` | `MAX_BATCH = 64` requests | unreachable with 6 threads |
| pending evals per tree | `Mage.MageZero/config/config.yml` | `max_pending: 4` | evaluations in flight per game |

Smaller costs in the server:
- `TORCH_THREADS = 1` (`server.py:16`), which only matters if the server ever runs on CPU.
- Three `print`s per request plus one per batch (`server.py:135, 149, 155, 159`).
- `request_states` splits states in a Python loop.

## Generator side (Will's machine, measured by Will)
- 16 game threads, offline MCTS vs MCTS, 300 sims/s per thread (4,800 sims/s total). Per-thread speed was flat from 4 to 16 threads.
- Peak heap about 20 GB at 8 threads (68 GB given). 100 games at budget 300 in about 10 minutes.

## Estimates (from reading the model, not measured)
- About 15–20 MB activation memory per state at about 1,500 nodes / 300 internal nodes (fp16 autocast, inference),
  so roughly 600 states per batch fit (300–2,000 depending on graph size).
- About 8 GFLOP per state; the gathers and scatters are memory-bound, so about 0.8 ms of GPU per state.
- If most of today's ~50 ms is fixed overhead, throughput stops improving at about 64–128 states per batch,
  with a ceiling of about 1–2k states per second.
- To keep the GPU busy at ~50 ms latency: about 75–100 evaluations in flight (games × `max_pending`).

## First measurement
Time one forward pass at batch sizes 1, 8, 32, 128 and 512, replicating a real state from the dataset, and record
milliseconds and peak memory. Run each size twice and keep the second. Use a typical state; the `[REQ]` lines print
`nodes=` / `kept=`.

```python
for b in (1, 8, 32, 128, 512):
    g = collate_graphs(*zip(*(states * b))).to(DEVICE)
    torch.cuda.reset_peak_memory_stats(); torch.cuda.synchronize(); t = time.perf_counter()
    with torch.no_grad(), torch.amp.autocast("cuda"):
        server_model(g)
    torch.cuda.synchronize()
    print(b, f"{(time.perf_counter() - t) * 1000:.1f} ms", f"{torch.cuda.max_memory_allocated() / 2**30:.2f} GB")
```

A flat curve means batching is the lever: raise waitress threads and states per call.
A linear curve means compute per state is the lever.

## Open questions for Will
- Where does today's ~50 ms go: HTTP and msgpack, Python, or the forward pass?
- Once batches are large, is it worth sorting states by size to cut padding in the global layers?
