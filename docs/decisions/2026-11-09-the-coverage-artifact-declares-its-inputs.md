---
title: "The coverage artifact declares the inputs it reads"
date: 2026-11-09
status: accepted
tags: ["build", "coverage", "gradle", "kover"]
---

# The coverage artifact declares the inputs it reads

## Context

CI's `Coverage floors` step (`:koverVerify`) failed on 2026-11-09 with a Gradle 9
validation error:

```text
    Task ':core-database:koverGenerateArtifact' uses this output of task
    ':core-database:kspKotlinJvm' without declaring an explicit or implicit dependency
```

Kover's `koverGenerateArtifact` reads the module's compiled classes and the
KSP-generated sources that sit next to them. Both are outputs of other tasks, and
neither side declared the relation, so Gradle refused to guess the order.

This was not a regression of the branch that fixed it. `:core-database` was split
out of `:shared` and wired into the gates and into CI in commits that are
already on `origin/main`; on `main` the coverage step simply never ran — the
`Gradle (tests)` step failed first, and the coverage step was skipped after it.
Fixing the tests exposed the next hidden defect in the chain.

The same error had already been fixed once in this repository, for
`:adr-core:koverGenerateArtifact` and `:adr-core:compileKotlinJvm`. That fix
lives in `adr-core/build.gradle.kts` and cites 2026-10-16.

## Idea

- Declare the dependencies Gradle asks for, instead of reordering tasks or
  silencing the validation.
- Keep the fix local to the module that has the problem.

## Decision

`core-database/build.gradle.kts` declares the inputs of its coverage artifact:

```kotlin
tasks.matching { it.name == "koverGenerateArtifact" }.configureEach {
    dependsOn("compileKotlinJvm", "kspKotlinJvm")
}
```

Both names are needed. Declaring only `kspKotlinJvm` — the one the CI error
named — moves the failure to the next producer, `compileKotlinJvm`
(`build/classes/kotlin/jvm/main`), which Kover reads for the same reason.

## Rationale

**Gradle's own remedy, verbatim.** The validation error lists three remedies;
declaring the dependency is the first, and it is also the only one that states
what the build actually means: the coverage artifact is computed from these
outputs, so it runs after them.

**The failure was a lie about correctness, not about order.** An undeclared
relation between two tasks means the result depends on execution order. The
build passed for a while because the order happened to be right; the validation
is what turned a latent race into an error.

**The fix is local.** The defect is in `:core-database`, the only module that
applies KSP. `:adr-core` already carries its own copy of the same remedy.

**Reproduction is a cold build, and the root aggregate is the trigger.** The
validation fires only when the output directory does not already exist, so a
warm `build/` hides it — and a warm build is what every local run has. The
command that reproduces it is the root aggregate on a cold module:

```bash
git clean -fdx -- core-database/build
./gw :koverVerify -Ptest.population=fast
```

Targeting `:core-database:koverGenerateArtifact` alone does **not** reproduce
it: the aggregate's graph is what puts the JVM compilation and the KSP task next
to the coverage artifact. That is why the defect survived several attempts to
reproduce it locally before the aggregate was tried.

## Consequences

- `:koverVerify` passes on a cold `core-database/build`; the `Coverage floors`
  step can reach its real verdict instead of failing on the build's plumbing.
- The coverage artifact now always runs after the JVM compilation of
  `:core-database`, which is the order it always needed.
- The reproduction command above is the cheapest way to see this class of
  defect again: a cold module and the root aggregate, not a single task.

## Links

- `core-database/build.gradle.kts`
- `adr-core/build.gradle.kts` — the same remedy for `:adr-core`
- `settings.gradle.kts` — the settings-level Kover aggregation
- `build.gradle.kts` — the root `koverReport` that runs every JVM test task
