---
title: "A detekt rule that aborted the run, and a rule change the daemon never saw"
date: 2026-09-28
tags: [detekt, tooling, build, ci, retro]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Two tooling defects found while checking whether a lint guard could be extended. Neither
was a bug in the rule's logic, and together they made a working rule look broken for
hours.

## 1. `NoOpUpdateStateRule` aborted `:shared:detekt`

`isIdentityTransform` located the transform lambda with
`org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType`. That is an **inline**
function, so inlining it into our class makes the compiler emit a synthetic class named
`NoOpUpdateStateRule$isIdentityTransform$$inlined$collectDescendantsOfType$default$1`.
That class fails to load inside detekt's plugin classloader:

```
java.lang.NoClassDefFoundError:
  com/singularity/todo/detekt/NoOpUpdateStateRule$isIdentityTransform$$inlined$collectDescendantsOfType$default$1
  at com.singularity.todo.detekt.NoOpUpdateStateRule.isIdentityTransform(NoOpUpdateStateRule.kt:110)
```

The class is present in both `detekt-rules/build/classes` and the packaged jar — the
linkage itself fails at load time, not because the file is missing.

**Why this is worse than a crash.** A rule exception escapes to detekt, which then fails
the task with `Analyzing <file> led to an exception` and **writes no report**. The
previous run's `detekt.md` / `detekt.xml` stays on disk. Reading that file reports a
clean, stale, wrong result. The trigger is narrow but ordinary: `updateState(reduce)`
where the argument is a name reference rather than a lambda literal.

Fixed by replacing the inline call with an explicit tail-recursive walk over `children`.
`NoCombineSideEffectRule` was already walking `node.children` by hand and is unaffected —
it was the only rule in `detekt-rules` importing `psiUtil`.

**Rule:** do not use inline Kotlin-compiler PSI extensions inside a detekt plugin. The
`children` walk the other rule already does is not more code and does not depend on the
compiler's classloader agreeing with detekt's.

## 2. Rule changes do not reach a warm Gradle daemon

`NoCombineSideEffectRule` was extended to cover `updateState`, and it appeared not to
work: in one transform, `seed(...)` was reported and `updateState(...)` was not, with
both names present in the source, the compiled class and the jar.

The decisive test was a marker string. `PROBE-MARKER` was added to the rule's finding
message. The ruleset fired, and the message detekt printed was the **old** one — across
`--rerun-tasks`, across `--no-configuration-cache`, and with the jar confirmed to
contain the new constant. Under `--no-daemon` the marker appeared immediately, and the
`updateState` extension was then detected on the first attempt, with no further
investigation.

**The Gradle daemon caches the resolved detekt plugin classpath.** Editing a rule in
`detekt-rules` has no effect on `:shared:detekt` until `./gradlew --stop`.

A stale extraction of the same rule classes also exists at `/tmp/dj/` from an earlier
session, and it does **not** contain the change even on runs that reported it — so it was
ruled out as the load path. It is a plausible second staleness source on a machine where
detekt does load from it, and worth clearing if a rule change ever appears to do nothing
twice in a row.

This is very likely the real cause behind a run of this project's "the rule never ran"
episodes, each of which was diagnosed at the time as a registration or configuration
mistake:

| Episode | Diagnosis at the time |
|---|---|
| `2026-09-26-preflight-retro-findings` R1 | rulesets had no `detekt.yml` block |
| `2026-09-26-konsist-architecture-tests` finding 13 | `NoFactoryViewModelRule` provider missing from the ServiceLoader file |
| `2026-09-26-detekt-rules-activation-audit` | `NoCombineSideEffectRule` and `NoGlobalScopeLaunchRule` deleted as orphans |
| `2026-09-27-mr1-retro-findings` R2 | the skill documented a rule that had been deleted |

Those diagnoses are not necessarily wrong — a missing config block *is* real. But a
change that cannot be observed is indistinguishable from a change that did nothing, and
each of them was verified by editing a rule and re-running.

**Rule:** after editing anything in `detekt-rules`, run `./gradlew --stop` before
concluding the change did or did not work. A rule test passing proves the logic; only a
run against real code proves the wiring.

## 3. A task that throws leaves its previous output in place

Combined with the above, this produced measurements that were confidently wrong — a
`seed` finding attributed to a file that no longer contained `seed`, and several
"0 findings" readings taken from a report three minutes stale.

`just lint` and `check.sh` treat detekt as authoritative. A crash therefore reads as a
pass to anything that only inspects the report.

**Rule:** when a build task fails, do not read its previous output. Check the task
outcome first — the report is evidence only if the run reached the report-writing step.

## What shipped

- `NoOpUpdateStateRule` no longer uses `psiUtil`; the crash is gone and the input that
  used to abort the run now completes.
- `NoCombineSideEffectRule` covers `updateState` and `setState`, verified with a positive
  control — the anti-pattern reintroduced into `CalendarSyncViewModel`'s transform is
  reported.

Tests: 1140 passed, 0 failed. detekt: 0 findings.

## Links

- `2026-09-28-mr4-combine-soundness` — the MR whose planned rule change was rejected on
  the strength of these false negatives, and corrected afterwards
- `2026-09-26-preflight-retro-findings` — the activation-checklist rule, which is right
  and still incomplete: it does not mention the daemon
- `2026-09-26-detekt-rules-authoring` (skill) — where the daemon caveat belongs
- `2026-09-27-framework-drift-resolution` — "verify a rule exists before relying on it"
