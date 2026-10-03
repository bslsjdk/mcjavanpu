# Meng NPU - status board

Updated: 2026-10-04 00:35 (DeepSeek / Mengxi)

This file exists because three parties touch this project (Mengxi, Yuanbao, GPT) and a plan that
only lives in a chat window gets lost. Read this before touching the terrain path.

## One line

Terrain takeover is **CLOSED** (`NpuTerrainGate.takeoverAllowed == false`), because our interpreter
has not yet been *proven* to reproduce Minecraft's own sampler. The tool that proves it (parity
harness) shipped this round and will open the gate by itself after 3 clean specimens.

## Why the gate is closed (Yuanbao's call - I agree with it)

`NpuTerrainVanilla` evaluates a re-implementation of `final_density.json`
(`NpuDfJson` -> `NpuDf` -> `NpuNoise`). That is **not** Minecraft's `DensitySampler`. Terrain that
is close to vanilla but not equal poisons everything built on it (lighting, carving, surface,
water) and surfaces later as a crash that looks unrelated to terrain.

So the rule is: **nothing reaches a DensityBuffer until parity is measured.** No exceptions, no
"it looks right". This is the same thing GPT said from the other side - accelerate the 26.3
`DensitySampler`, do not replace world generation.

## The evidence path - how to read it

`NpuParity` runs on the RETURN side of `DensitySampler$Bound.sampleVolume`, only while the gate is
closed (afterwards the buffer holds our own output and comparing it would prove nothing). Every
64th chunk volume, up to 3 volumes, it snapshots vanilla's buffer, evaluates the same volume with
our tree on a low-priority background thread, and compares point by point.

Log lines to look for:

```
parity run #1 vol=16x384x16 step=1/1/1 points=98304 max_abs=0.000e+00 mean_abs=0.000e+00 bad=0 ...
parity: clean run 1/3 (2 specimen(s) left)
parity: 3 clean runs, worst difference ... - opening the terrain gate
terrain gate: takeover ENABLED - interpreter output will replace vanilla
```

Decision rule: `bad == 0 && max_abs <= 1e-4` for 3 separate volumes. `bad` counts points differing
by more than 1e-3. A mismatch also logs `first_bad@<index> vanilla=... mine=...` so a wrong node can
be found instead of guessed at.

If it never opens: that is the answer, not a bug. Look at `first_bad`, and at
`NpuDfJson.lastUnsupported()` - an unrecognised node is silently compiled to a constant 0.

## What changed this round

1. **Density tree is compiled, not walked** (`NpuDfProgram`, C2ME's approach to the same problem).
   The tree becomes a flat instruction stream: no virtual call per node, no recursion, constants
   folded at compile time, `cache` nodes dropped (a flat pass visits each node once per sample, so
   the memo could never hit). `range_choice` compiles to a real branch, not "compute both and
   select". The tree walk stays as the fallback if lowering fails.
   Cost is reported as `prog_insn=` / `prog_regs=` in `NpuTerrainVanilla.summary()`.
2. **The prefetch loop no longer bails on a stats flag.** `NOISE.enabled` defaults to false and the
   loop checked it before doing anything - which is why `processed=0` with `failed=0`. Same class of
   mistake as `BLOCKS` before it: a measurement switch must never act as a permission gate.
3. **Parity harness** (above), plus `NpuTerrainVanilla.currentSeed()`.
4. Work set stays at 3x3 with `MAX_IN_FLIGHT=12`. 9x9 was tried and starved the load thread
   (`pending=80`, p99 26 ms) - the work set is a planning range, execution must stay batched and
   rate-limited.

## Open items

- **Yuanbao / native**: the server reports `svc_avg_us=6967` for a 225x16 matrix that should cost
  about 0.1 ms. Almost none of that is arithmetic - it is graph lookup, buffer preparation, and
  requantisation. Needs instrumentation inside the service:
  `graph_lookup_us / tensor_prepare_us / qnn_execute_us / requantize_us`.
- **Yuanbao / native**: `max_elements=16384` vs a 128x512 (65536) C matrix - the semantics need to
  be written down in the service, or the next reader will mis-size a submission.
- **Mengxi**: `per_sample_us` from the lowered program. If the interpreter is still slow, the next
  lever is batching across y (hoisting the x/z-shared subtrees), not more micro-tuning.
- `reloadchunks` reflection was switched to the `ChunkPos` signature; still unverified on device.

## Numbers worth keeping

- Per-submit breakdown (client side): `send=611us wait=11336us recv=598us svc=6967us`,
  `lock_wait_avg=1343us` - so TCP is innocent and lock contention is ~1.3 ms, not the 8.5 ms a
  small sample once suggested.
- CPU trilinear baseline (C++ -O3): 0.042 us/point. NPU on the same shape: 2.1 us/point.
  Per-point noise interpolation is too light for the NPU; that is why the matrix route for Perlin
  was dropped, and why the win has to come from batching, not from per-point offload.
