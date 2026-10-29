---
title: "The Compose compiler plugin is why :shared never compiles incrementally"
date: 2026-10-29
status: accepted
tags: ["build", "performance", "compose"]
---

# The Compose compiler plugin is why :shared never compiles incrementally

## Context

Every Gradle invocation in this repository recompiles `:shared` from scratch.
The symptom is that a run of **one** test class costs 1m53s while the whole
fast population — 517 classes, 4340 test methods — costs 2m10s. Ninety-five
percent of the "narrow" loop is compilation, not tests.

The build says so itself, with `--info`:

```
Task ':shared:compileKotlinJvm' is not up-to-date because:
  Task.upToDateWhen is false.
i: Non-incremental compilation will be performed: Unknown inputs changes
Caching disabled for task ':shared:compileKotlinJvm' because:
  'Task outputs cacheable' not satisfied
```

`ADR 2026-10-22-gradle-memory-budget` explains that `kotlin.compiler.execution.strategy=in-process`
was chosen so the Kotlin compilation fits inside the 6 GB daemon, and states that
**"Incremental compilation is NOT lost by this"**. That claim is wrong, and the
comment next to it in `gradle.properties` repeats it.

## Idea

Measure instead of reasoning. Four candidate causes were eliminated one at a time.

## Decision

The **Compose compiler plugin** is the cause. The evidence is a differential
measurement over four modules, two with the plugin and two without:

| Module | Compose plugin | Second consecutive build |
|---|---|---|
| `:shared` | yes | recompiles |
| `:desktopApp` | yes | recompiles |
| `:adr-core` | no | UP-TO-DATE |
| `:core-base` | no | UP-TO-DATE |

`KSP`, `commonMain` and the module size were each ruled out: `:desktopApp` has
neither KSP nor a `commonMain` source set, and it still recompiles.

The Compose **stability configuration** — the suspect, because the repository's
own comments say the contract is passed as a plugin option and plugin options are
not part of the compile task's up-to-date inputs — was tested directly by
commenting `stabilityConfigurationFiles` out of `shared/build.gradle.kts` and
building twice. The task recompiled anyway. The stability contract is not the
cause; the plugin is.

Two further candidates were measured and eliminated: the strategy itself
(`-Pkotlin.compiler.execution.strategy=daemon` recompiles too — it only makes the
recompile faster, 54s against 1m07s) and the configuration cache
(`--no-configuration-cache` recompiles too).

**The build cache is not a separate candidate — it is the same cause.** The
compile task of a module with the Compose plugin is not cacheable either, and
the two symptoms line up module for module:

| Module | Compose plugin | Second build | Build cache |
|---|---|---|---|
| `:core-base` | no | UP-TO-DATE | cache key issued |
| `:adr-core` | no | UP-TO-DATE | cache key issued |
| `:desktopApp` | yes | recompiles | `Task outputs cacheable not satisfied` |
| `:shared` | yes | recompiles | `Task outputs cacheable not satisfied` |

`:desktopApp` is the decisive one: it has the Compose plugin and neither KSP
nor a `commonMain` source set, and it is as uncacheable as `:shared`. So
`org.gradle.caching=true` is already on and buys nothing for these two
modules — not because the cache is misconfigured, but because the plugin
withholds the task from it. There is no build-script change that gives it
back.

## Rationale

There is no small fix. The Compose compiler plugin is required to compile
`@Composable` code, 241 of the module's 1238 files carry `@Composable` — and
the plugin applies to the whole module, so the other 80% pay for it — and the
plugin marks the Kotlin compilation permanently out of date and permanently
uncacheable. Splitting the module or dropping the plugin are both larger than
the problem they solve.

So the finding is recorded instead, and the loop is measured honestly: **the
floor of a narrow run in this repository is the full compile of `:shared`, and no
tooling in it can go below that.** The impact map
(`scripts/test-impact-map.py`) narrows the *selection* to 78 classes, but 69 of
them are tree-wide gates that must re-read the tree the change just touched —
so a narrow run is still about 40% of the full population.

What this buys is that the next person does not spend an afternoon in the same
place. The candidates that remain, in order:

1. **Splitting the Compose-heavy code into its own module**, so the module a
   test touches usually does not contain a Compose compiler. This is the only
   one that touches both symptoms at once: a module without the plugin is
   UP-TO-DATE and cacheable, so the second build of a commit is free.
2. **A shared snapshot of the parsed tree**, written once by a task and read by
   every arch gate — the 49 tree-wide gates are 42% of the suite's CPU, and each
   of the four forks pays the walk.

## Consequences

- `ADR 2026-10-22-gradle-memory-budget` and the comment in `gradle.properties`
  claim the in-process strategy costs no incremental compilation. It costs all
  of it. The claim is corrected here rather than in two places at once.
- A narrow run is expected to cost about two minutes, and the expectation is
  written down so that a future change to the build is measured against it
  rather than assumed.

## Links

- `shared/build.gradle.kts` — the Compose stability configuration and the
  `upToDateWhen` block that made this look like a stability problem
- `docs/decisions/2026-10-22-gradle-memory-budget.md` — the in-process claim
- `config/docs/gradle-memory-budget.properties` — the fork count and heap
- `scripts/test-impact-map.py` — the map that narrows the selection but not the cost
