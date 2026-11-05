# The 2.3× parity gap — where the missing 34 s actually goes

The stand compiles 1,237 generated files with Compose + Koin in **25.6 s**; `:shared` compiles 1,244 real files with the
same plugin mix in **60.1 s**. Same machine, same daemon settings, same protocol. Three candidate explanations were tested;
one is still open.

| candidate | test | result |
|---|---|---|
| **GC / memory pressure** | GC metrics in both Build Reports | **no** — 0.908 s (19 collections) for `:shared`, 0.661 s (23) for the stand. 0.3 s of 34 s. |
| **Classpath size** | add `:shared`'s own 230 jars (15.3 MB snapshot) as `compileOnly` to the stand, same sources | **no** — 24.0 / 24.6 / 25.2 / 25.9 / 29.7 s, median 25.2 s against 24.4 s without. +0.8 s. |
| **Code volume** | `Number of lines analyzed` | **no** — the stand analyses *more* (192,972 against 166,175) and is still 2.3× faster. |
| **Code shape** | add `@Serializable` data classes, the serialization compiler plugin, and composable bodies with state | **partly** — 39.6 s median (38.8–43.1 counted), **+14 s** |

## What the shape experiment did

Same N = 1,237, same Compose plugin, plus what `:shared`'s sources have and the stand's did not:

- `:shared` has **868** `@Composable` functions and **277** `@Serializable` classes; the stand had one trivial composable
  per file and no serialization plugin at all;
- the composables were one-line returns (`fun label(i) = "..."`); real ones hold `remember`, `mutableStateOf` and branches;
- the serialization plugin generates a serializer per `@Serializable` class — compile-time work that scales with the
  number of such classes, not with line count.

That change moved 24.4 s → 39.6 s. The compiler's own throughput shows the same thing: `Code generation lines per second`
is **6,782** for `:shared` against **36,422** for the stand — 5.4× more work per output line on real code.

## What is still unexplained

Roughly **20 s** of the 60.1 s. The remaining candidates, in the order they should be tested:

1. **Deeper code complexity** — generics, inline functions, deep class hierarchies, `expect/actual`, Compose tree depth.
   The stub reproduces the *annotations* of real code, not its graph.
2. **Load, uncontrolled.** `:shared` was measured at 0.55–0.62 load per core; the stand at 0.07–0.25. Not 2.3×, but it is
   a real difference between the two datasets and nobody has separated it.
3. **The plugin set is still not identical** — `:shared` also applies kotlinx-serialization to real `@Serializable`
   hierarchies and Compose to a real UI tree; the stand's Compose runtime dependency is `runtime` only.

## Does this change the split decision?

**No, and it makes the model look worse rather than better.** The replay's verdict is a *ratio* between scenarios priced
with the same curve, so a uniform under-pricing cancels. What does not cancel is the direction: the stand's per-file price
(19.6 ms) is less than half of `:shared`'s implied one (~46 ms from its own two points), so every real module is more
expensive than modelled — including the modules a split would create. The absolute numbers in `replay.md` are therefore a
floor, not an estimate.

The practical consequence is for anyone tempted to use this stand for a future modularization question: it prices
*annotations*, not code. Calibrating against one real module before trusting it is mandatory.

## Reproducing

```bash
build-investigation/split-plan/cost-model/experiment-classpath.sh 1237   # classpath, 5 cycles
build-investigation/split-plan/cost-model/experiment-shape.sh 1237       # code shape, 5 cycles
```

The classpath list is `results/parity/shared-classpath.txt`, extracted from `:shared`'s own Build Report (`-classpath`
argument), so the experiment uses the real dependency set rather than a copy that can drift.
