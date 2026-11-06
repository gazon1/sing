# Stage 2/3 — module probes and the minimal pair

Each module gets the same protocol as Stage 1: forced compile → `UP-TO-DATE` settle → one body-only
edit applied from a patch → measured compile → `git apply -R`. Cache disabled, one factor at a time.

## Existing modules (`in-process`)

| Module | Composition | Edit | Forced / settle | Measured | Sources in iteration | Verdict |
|---|---|---|---|---|---|---|
| `:core-domain` | KMP, jvm + android targets, **no Compose, no Room/KSP, no Koin** | `TextMatch.kt`, private `matchesAt` body | 19 s / `UP-TO-DATE` 1 s | 9 s wall / **7.333 s** | 44 | **non-incremental**, `KT-62686` |
| `:core-database` | KMP + Room/KSP, no Compose | `ProposalKindCodec.kt`, private `readPriority` body | 38 s / `UP-TO-DATE` 1 s | 24 s wall / **21.636 s** | 83 | **non-incremental**, `KT-62686` |
| `:shared` | KMP + Compose + Koin + KSP | `RedactingLogWriter.kt`, private `redact` body | 141 s / `UP-TO-DATE` 4 s | 64 s wall / 62.028 s | 1,237 | **non-incremental**, `KT-62686` (cycle A of Stage 1) |

All three report `Classpath changes: NoChanges` and the same
`REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)`.

**Confound, stated:** in the `:core-database` measured run `:core-database:kspKotlinJvm` **executed**
— any source edit in that module is a KSP input, so the module cannot show an edit without KSP.
That row therefore does not separate KSP from the edit; it is the reason the `:core-domain` row
exists, and `:core-domain` has no KSP at all.

These three rows localize but do not prove: the modules differ in size, plugins and dependencies.
`:core-domain` is the strongest of them (44 sources, no plugin beyond the KMP plugin), but a module
with 44 files is still not a minimal reproducer.

## The minimal pair (`build-investigation/kt-62686/probe/`)

One Gradle build, two modules, the **same source file** and the **same edit**, differing only in the
applied plugin:

- `:probe` — `kotlin("multiplatform")` 2.4.10, `jvm()`, `jvmToolchain(21)`, one file in `commonMain`;
- `:probeJvm` — `kotlin("jvm")` 2.4.10, `jvmToolchain(21)`, the same file in `main`.

| Module | Strategy | Measured | Verdict |
|---|---|---|---|
| `:probe` (KMP) | `in-process` | 1 s wall / 0.577 s task | **non-incremental**, `KT-62686` |
| `:probe` (KMP) | `daemon` | 1 s wall / 0.521 s task | **non-incremental**, `KT-62686` |
| `:probeJvm` | `in-process` | 2 s wall / 0.423 s task | **incremental** (`Incremental compilation completed`, no `REBUILD_REASON`) |
| `:probeJvm` | `daemon` | 1 s wall / 0.328 s task | **incremental** |

All four runs: `Classpath changes: NoChanges`, one modified source, task executed.

The KMP module reports `Cleaning 3 output directories` and
`Non-incremental compilation will be performed: Incremental compilation might be incorrect
(KT-62686)`; the JVM module reports `Incremental compilation completed`.

## What this establishes, and what it does not

Established by measurement:

1. The Compose compiler plugin, the Koin compiler plugin and Room/KSP are not required: a module
   with none of them falls back.
2. The execution strategy is not required: both strategies fall back on the same module and patch.
3. Size is not required for the fallback: a one-file module falls back. Size sets the cost — the
   62 s in `:shared` is the recompilation of 1,237 files.
4. The difference in the pair is the **KMP JVM compilation configuration against the plain JVM
   compilation configuration**: the same file compiled by `kotlin("jvm")` is incremental under both
   strategies. The plugin and the compilation model it creates change together, so this does not
   name a mechanism inside the Gradle plugin.

Not established:

- the compiler-internal reason. `Process SignatureChanged(fqName = .kotlin_module)` appears in the
  fallback **and** in the incremental `:probeJvm` runs, so it is not the discriminator; it is
  recorded because it is the last IC decision line before the bailout in the KMP runs.
- that *every* KMP module falls back (only jvm targets were measured; the android target was not
  exercised) and whether this is a Kotlin 2.4.10 regression. Both need an upstream answer, and
  `probe/` is the artifact to attach to it.
- what makes `:shared` slower per edit than `:core-domain`: that is the number of files the
  non-incremental compile must process (1,237 against 44), not a second cause.
