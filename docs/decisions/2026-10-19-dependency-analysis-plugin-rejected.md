---
title: "The autonomousapps dependency-analysis plugin cannot see a KMP module, so it is not adopted"
date: 2026-10-19
status: accepted
tags: ["tooling", "gradle", "kotlin-multiplatform", "dependencies", "gates"]
---

## Context

The repository already answers "is this declared dependency used?" — and answers it
by reading what Gradle resolved rather than by guessing from the catalog. The gate
is `scripts/check-dependency-usage.py`, and its docstring explains why the obvious
approach is wrong:

> A Gradle coordinate does not determine an import package:
> `org.jetbrains.compose.material3:material3` → `androidx.compose.material3`,
> `io.insert-koin:koin-core` → `org.koin.core`. So "does the module string appear
> in an import" is not a prefix test. Measured on this tree: a first-two-segment
> heuristic over all 98 library entries reports 45 unused — and every one is used.

The plan proposed replacing that hand-built analysis with
`com.autonomousapps.dependency-analysis` 3.19.2, a Gradle plugin that computes
the same thing from bytecode and reports it as `buildHealth` JSON. The plugin was
connected, and it answered.

## Idea

Connect the plugin at the root (`apply false`) and apply it in `:shared`, then
build a gate on its `buildHealth` output: a declared dependency that the plugin
reports as unused is a finding.

The connection needed two things to work at all:

- **The classloader.** The plugin's Gradle integration loads in the same classloader
  as the Android Gradle Plugin, and the Kotlin Gradle Plugin sits in the parent
  classloader. The plugin's `project.extensions` lookup of the Kotlin extension
  therefore failed until the two were arranged by hand.
- **The DSL shape.** `dependencyAnalysis` at project level is a *sub-extension*,
  so the block is `dependencyAnalysis { issues { ignoreSourceSet(…) } }` — the
  `issues { }` block does not sit inside an `all { }` wrapper, and a bare `all`
  does not compile.

Both were solved. The plugin ran, and it reported.

## Decision

**Not adopted. The plugin was fully reverted** — `build.gradle.kts`,
`shared/build.gradle.kts`, `gradle/libs.versions.toml` and
`scripts/build-version-catalog-gate.py` are back to their previous content, and a
residue check over those files is clean. The build is green after the revert.

The reason is not that the plugin is a bad tool. It is that **its analysis of this
project's main module is empty**. Run against `:shared`, it reports:

| Dependency | Files that use it | Plugin's verdict |
|---|---|---|
| `compose.material3` | 183 | unused |
| `compose.runtime` | 285 | unused |
| `koin-core` | 38 | unused |
| `kotlinx-serialization-json` | many | unused |

Every one of them is used, and every one of them is used *in Kotlin source*, not
in a manifest or a resource. The plugin's evidence is bytecode — and a Kotlin
Multiplatform module's `commonMain` reaches the analysis as a **klib**, not as JVM
class files, so the analysis has nothing to look at. The reason was not isolated
to the last commit; the observable result is the same one that sank the naive
prefix heuristic: the plugin's list of "unused" is a list of the dependencies this
project actually uses most.

## Rationale

A gate built on that output would report dozens of false findings, and the
response to dozens of false findings is an allowlist — which is precisely the
failure the existing gate's docstring refuses:

> a 45-entry list of definitely-used libraries is indistinguishable, to the next
> reader, from a 45-entry list of genuinely dead ones.

There is a second, independent reason to stop here, and it is the reason the
plugin was attractive in the first place. The existing gate is **not** a
hand-rolled version of the plugin's job. It answers a *different* question
correctly:

- It asks Gradle which files each configuration resolved, and which coordinate
  each file came from — so the answer is scoped to the source set that actually
  resolved the dependency, not to a global "is this package mentioned anywhere".
- It reads the *contents* of each jar and aar to learn the package roots the
  artifact contributes, including the `linkdata/package_<fqcn>/` entries of a
  KMP metadata jar. Without that second pattern every KMP library reads as
  contributing no packages at all — which is the set that includes every
  wrong-answer case (compose, koin, markdown, okio).
- It has a positive control and a ratchet: the allowlist is 11 entries with a
  ceiling file, and lowering the ceiling is the point.

So the plugin would have replaced a working, measured, ratcheted gate with an
empty one, in exchange for a build-time dependency.

The rejected alternatives:

- **Use the plugin and allowlist its output.** This is the trap the existing
  gate's docstring names: the allowlist would be filled with libraries that are
  demonstrably in use, and the next reader could not tell those apart from
  genuinely dead ones. The ceiling mechanism would then lock the wrong list in.
- **Use the plugin only for the JVM-only modules** (`androidApp`, `desktopApp`,
  `mcp-server`), where `jvmMain` does produce class files. This was not
  measured, and it would split one question into two tools with two verdicts —
  the multi-tool arrangement the `CLAUDE.md` guidance on a single source of
  truth argues against. The existing gate already covers those modules in the
  same pass.
- **Keep the plugin connected as an advisory signal.** A signal that says
  "everything is unused" is noise; noise in a gate registry is what
  `A gate that lies is worse than no gate` was written about.

## Consequences

- **The existing dependency-usage gate stays.** It is the tool that answers this
  question for a KMP project, and it answers it from what Gradle resolved.
- **No Gradle plugin dependency is added** to the build, and no
  `buildHealth` JSON parsing enters the gate surface. The `buildHealth` format
  is also a plugin-version-coupled schema; a gate on it would break on plugin
  upgrades in a way a gate on resolved artifacts does not.
- **The "45 unused" figure stays in the docstring as a warning**, and this
  record is the second time the same number has been produced by a different
  method. If a future attempt revisits this, the question to answer first is
  *what the plugin sees in a klib-backed module*, not *which gate to wire it
  into*.
- **The Kotlin-usage evidence the plugin could not provide** — that a
  dependency is used in `commonMain` and therefore in every platform source set —
  remains the property the current gate already models with its
  `SOURCE_SET_DIRS` map.

## Links

- `scripts/check-dependency-usage.py` — the gate that stays, and the docstring
  that explains why the coordinate→package mapping is not a prefix test
- `scripts/dependency-usage-allowlist.txt` and `.ceiling` — the ratchet
- `scripts/src/sgate/commands/_registry.py` — the `dependency-usage` control
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — the
  rule an allowlist full of false findings would break
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md` —
  the other track the same measurement effort killed
