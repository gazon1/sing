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

## The phase breakdown: where the 31 s sits

Both Build Reports carry the compiler's own `PERF:` lines, so the gap can be attributed without another experiment
(`grep '^ *PERF:' results/<run>/report.log`):

| phase | `:shared` (1,244 files / 166,175 lines) | stand, Compose + Koin (1,237 / 192,972) | ratio | delta |
|---|---|---|---|---|
| INIT | 265 ms | 131 ms | 2.0× | +0.1 s |
| **ANALYZE** (frontend) | 23,113 ms | 17,222 ms | 1.34× | **+5.9 s** |
| TRANSLATION to IR | 8,267 ms | 2,598 ms | 3.2× | **+5.7 s** |
| IR LOWERING | 9,111 ms | 2,771 ms | 3.3× | **+6.3 s** |
| **BACKEND** (codegen) | 15,389 ms | 2,526 ms | **6.1×** | **+12.9 s** |
| sum of phases | 56,145 ms | 25,248 ms | 2.2× | **+30.9 s** |
| GC (young) | 859 ms | 661 ms | — | +0.2 s |

Throughput tells the same story in one line: analyzed lines per second are 7,190 against 11,205 (1.6×), but **generated
lines per second are 10,798 against 76,371 (7×)**.

**So the phase is code generation, not the frontend:** the backend does six times the work per file and is 42 % of the
gap, while the frontend is only 1.34× slower.

**What inside the backend is not established.** Candidates: the serialization plugin (a generated serializer per
`@Serializable` class — 277 in `:shared`), Compose's generated restart/bridge code (868 `@Composable`), `@JvmInline` value
classes for ids, and inline flow/collection operators. The shape experiment below raised the stand by 15 s but added
serialization *and* Compose-shaped composables, **not** value classes or inline chains — so it cannot separate them, and
its own report carried no `PERF:` block. This is localisation, not an explanation; #2605 measures which construct it is.

That also explains the negative results: codegen work does not scale with the classpath, which is why adding 230 jars
changed nothing, and it does not scale with analyzed line count, which is why the stand could analyze more lines and still
be twice as fast.

## Follow-ups filed

- **#2605** — attribute the 12.9 s: re-run the shape configuration with a working `PERF:` block, and profile one `:shared`
  compile under JFR to name the backend frames.
- **#2606** — the ceiling: removing the entire backend surplus is 60.1 s → ~47.2 s, **−21 %**, below the −30 % the work was
  justified against. The line closes unless #2605 names one cheaply removable construct.

## What is still unexplained

The phases account for **30.90 s** of the 34.05 s gap. The rest is the time both compiles spend *outside* the reported
phases — class loading, snapshot work, compiler setup — which is **3.91 s** for `:shared` and **0.76 s** for the stand,
i.e. a 3.15 s difference. 30.90 + 3.15 = 34.05, so the arithmetic closes.

**GC is inside those phases, not beside them**: the `PERF: GC time` lines are a breakdown of the same run, and the
ANALYZE/BACKEND spans already contain the pauses. The GC *difference* between the two runs is 0.2 s, and that is the only
GC number worth quoting.

So the gap is localised, not explained: the report says **which phase** the surplus is in (the backend, +12.9 s), not
**why**.

The uncontrolled load difference remains a caveat rather than a candidate: `:shared` ran at 0.55–0.62 load per core, the
stand at 0.07–0.25. Cheap to settle if anyone needs it — re-run the six `:shared` cycles on a quiet host.

The shape experiment's own report carries no `PERF:` section (`results/parity/shape-1237-*/report.log`), so its +15 s
cannot be attributed to a phase; the measurement itself is sound (`Non-incremental`, `BUILD SUCCESSFUL in 40s`), only the
breakdown is missing.

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
