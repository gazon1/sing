# The cost model (H1/H2) and the two assumptions behind it

Question: what does a KMP module's full recompile cost as a function of its size and plugin mix — `T = a + b·N` — so that a
hypothetical split can be priced before it is built.

Stand: `cost-model/`, its own Gradle build with the repository's `gradle.properties` (6 GB daemon, `in-process`,
`workers.max=2`, configuration cache on), N generated files of the tree's measured average length (~132 lines computed from
`shared/src` at run time), measured through `cycle.sh` with `PROJECT_DIR`/`EDIT_HOOK`. 15 configurations × 5 counted cycles
(plus a warm-up each) = 90 cycles, sizes interleaved.

## The curves

| mix | a (s) | b (s/file) | R² | N points |
|---|---|---|---|---|
| bare KMP | 0.20 | 0.01708 | 1.000 | 1, 10, 44, 100, 250, 500, 1237 |
| + Compose | 0.22 | 0.01956 | 1.000 | 100, 250, 500, 1237 |
| + Compose + Koin | 0.40 | 0.02040 | 1.000 | 100, 250, 500, 1237 |

At N = 1237 (the compile iteration's size) that is 21.3 s bare, 24.4 s with Compose, 25.6 s with Compose + Koin.

**H1 holds: the price is linear in file count.** R² is 1.000 on every curve and the residuals are inside the run-to-run
spread. The per-file cost is 17–20 ms; the fixed part is small (0.2–0.4 s), which is why many small modules are affordable.

**H2 holds: the plugins change the slope, not the intercept.** Compose adds 14 % to `b`, Koin another 4 %. Both are far below
the Gate D threshold (a lever would be ≥ 2×), so **the price is intrinsic to compiling Kotlin, not to our plugins** — a
plugin-free module would not be dramatically cheaper than a Compose one.

## Gate B — the model's shape: pass

Fit on N = 100, 250, 500; predict N = 1237, which is ×2.5 beyond the fit — an extrapolation, labelled as one:

```
predicted 25.6 s vs measured 25.6 s (-0.1 %)
```

So the linear shape is right where it matters.

## Parity with `:shared` — reported, not gated: fails by a lot

```
compose-koin curve at N = 1238 -> 25.7 s vs :shared measured 60.055 s (-57 %)
```

A synthetic module of the same size and plugin mix compiles **2.3× faster** than `:shared`. `parity.md` attributes the
gap from the compiler's own `PERF:` phases: the GC difference is 0.2 s (and GC sits *inside* the phases, not beside
them), the 230-entry classpath is +0.8 s (measured), and analyzed line count is negative (the stand analyzes *more*). The gap is **code generation**: the backend does 6.1× the work per file
(+12.9 s of the 31 s), IR lowering 3.3× (+6.3 s), IR translation 3.2× (+5.7 s), and the frontend only 1.34× (+5.9 s).
*Which* backend construct is responsible — serializers, Compose codegen, value classes, inline operators — is **not**
established; #2605 measures it, and #2606 records that even removing the whole surplus is −21 %, below the −30 % bar.

The curves are therefore used two ways in `replay.md`: raw (ratios only) and calibrated ×2.3 to the measured baseline,
because a uniform scale changes no ratio but makes the absolute numbers comparable. The direction of the bias is known:
the stand prices *annotations*, not code, so every real module — including the ones a split would create — costs more
than modelled, and the replay's absolute numbers are a floor.

## Gate E — the two assumptions the replay rests on

Two modules in the stand (`module-b` depends on `module`), 100 files each, a body-only edit in both.

| measurement | value |
|---|---|
| `:module:compileKotlinJvm` | 2.759 s |
| `:module-b:compileKotlinJvm` | 2.630 s |
| serial sum | 5.389 s |
| makespan (2 workers) | 2.759 s |
| measured wall | 6.008 s |
| body-only edit rebuilds the dependent module | **no** |
| signature edit rebuilds the dependent module | **yes** |

**Cascade: passes.** A body-only edit leaves the dependent module `UP-TO-DATE`; adding a public function rebuilds it. The
replay's ABI branch is therefore built on a measured behaviour, not an assumption.

**Additivity: fails, and the parallel aggregation is wrong.**

1. The two tasks cost 2.76 s and 2.63 s, while the same 100-file module alone costs 1.9 s (the bare curve at N = 100). A
   compile task in a multi-module build is **~40 % more expensive** than the same task alone, so summing per-module prices
   under-predicts a split build by about that much.
2. The two tasks ran **sequentially**, not in parallel: the makespan (2.76 s) is nowhere near the measured wall (6.0 s).
   `:module-b` depends on `:module`'s jar, so the compiler of one cannot start before the other finishes. The `workers.max=2`
   that makes independent tasks parallel does nothing for a dependency chain — which is exactly what a module split creates.

**Consequence:** the replay's "optimistic" cell (parallel aggregation) does not exist in a real split build, and its serial
cell under-prices each module by ~40 %. Both errors point the same way: the replay makes a split look **better** than it is.

## What this changes for the plan

Gate E's own criterion was "either check fails → the replay's arithmetic is void → stop". The cascade half holds; the
additivity half does not. `replay.md` therefore reports the scenarios as a *direction*, not as a decision, and the verdict
section says plainly which numbers survive.

The honest next step is not another model: it is **one real two-module split measured end to end** — which is what
`core/ui`'s five import lines make cheap. That measurement would replace both the stand and this model.

## What this does not establish

- The plugin cost was measured on a synthetic module; a real Compose UI tree may cost more per file than `@Composable` stubs.
- `androidMain` and the Android target are untouched (H8), so a split's Android price is unmeasured.
- The 2.3× parity gap is unexplained in detail — resource generation and classpath size are the obvious candidates, none of
  them isolated here.
