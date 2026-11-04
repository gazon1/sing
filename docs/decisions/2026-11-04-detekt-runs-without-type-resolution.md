---
title: "The detekt gate runs without type resolution, so its analysis rules never run"
date: 2026-11-04
status: accepted
tags: ["tooling", "detekt", "gates"]
---

# The detekt gate runs without type resolution, so its analysis rules never run

## Context

ADR `2026-11-03-the-formatting-rules-have-one-owner` handed unused imports to ktlint's
`standard:no-unused-imports`. Enabling that rule surfaced 18 unused imports, and three more
arrived later. Detekt's `UnusedImport` — the rule that had been configured to catch them —
reported none of the 21, and the tree was clean under it. That ADR recorded the fact but said
the reason "was not established".

The suspicion at the time was that the rule *scanned* the desktop app's test sources but could
not *resolve* them: `desktopApp`'s detekt task lists `src/jvmTest/kotlin` in `source`, while the
task's classpath, if it has one, is the main compilation's. That would have been one defect
among many, because every semantic rule that needs resolution would skip the same files.

It is not what happened. The gap is wider and simpler.

## What the tool actually does

Two parts: what was measured, and what was read from the pinned sources.

**Measured against the artifacts this build resolves** (`dev.detekt:*:2.0.0-alpha.6`, the jars
Gradle already has under `~/.gradle/caches/modules-2/…`):

- **Nothing sets a classpath.** No module's `detekt { }` block — `build.gradle.kts`,
  `desktopApp/build.gradle.kts`, `shared/build.gradle.kts` — and not the root `detektChanged`
  task sets `classpath`. The aggregate task runs with no types to resolve against.
- **The rules that need types declare it.** `javap` on `detekt-rules-style-2.0.0-alpha.6.jar`
  shows `dev.detekt.rules.style.UnusedImport`, `…style.UnusedPrivateFunction`,
  `…style.UnusedPrivateProperty` and `…style.UnusedVariable` each declaring
  `implements dev.detekt.api.RequiresAnalysisApi`. `Deprecation` lives in
  `detekt-rules-potential-bugs` as `dev.detekt.rules.potentialbugs.Deprecation` — not in
  `style`, despite the rule's historical home.
- **Their PSI-only siblings do not.** `javap` shows `…style.UnusedParameter` and
  `…style.UnusedPrivateClass` declaring no `Requires*` interface, so those two run. "Unused" in
  the name predicts nothing; the interface does.
- **The repository's own rules are PSI-only.** No file under `detekt-rules/src/main/kotlin/`
  mentions `RequiresAnalysisApi`; the custom rules implement plain `Rule`. The ones that exist to
  police test code therefore *do* run over the test sources.

**Read from the plugin sources at the pin**, in order (these symbol names are as written at
2.0.0-alpha.6; if one does not resolve, the conclusion still stands on the two observables
above and on the notice in the last bullet):

1. The aggregate `detekt` task is registered by `DetektPlain.registerDetektTask`. It sets
   `baseline`, `source`, `includes` and `excludes` — and **no `classpath`**.
2. `Detekt.kt` appends `ClasspathArgument(classpath)` to the CLI arguments.
3. `CliArgument.kt` maps an empty classpath to `--analysis-mode light`, and a non-empty one to
   `--classpath … --analysis-mode full`.
4. `detekt-core`'s `RuleDescriptorKt.getRules(analysisMode, …)` drops every rule that implements
   `RequiresAnalysisApi` when the mode is not `full`, logging
   "… requires type resolution but it was run without it."
5. In this version the style `Unused*` rules listed above implement `RequiresAnalysisApi`, and so
   does `dev.detekt.rules.potentialbugs.Deprecation`; `UnusedParameter` and `UnusedPrivateClass`
   do not.

The repository runs the **aggregate** task — `./gw :shared:detekt :desktopApp:detekt …`, and
`just lint` is the same list — and the scoped `detektChanged` task registered in the root build
script sets only `baseline` and `source`, so it inherits the same empty classpath. Nothing in any
module's `detekt { }` block sets `classpath`. So every one of those runs is
`--analysis-mode light`, and every rule that needs types is dropped before a single file is read.
`UnusedImport` never ran — not over the test sources, and not over main either.

The build already said so: `check.sh` justifies compiling `androidMain` with the aside that
"detekt runs without type resolution", and the same sentence sits next to the host-test step. The
gate knew; the rule-ownership notes did not.

Two facts that the test-classpath theory cannot explain corroborate this:

- detekt was equally silent on `okio.Path` in `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`
  and on `PriorityPalette` in `shared-ui` main sources. Both are ordinary main-source imports in
  modules that resolve their own dependencies.
- One of the 18 imports was in `shared/build.gradle.kts`, which no detekt `source` list names at
  all — detekt never scans build scripts. That one has a separate, trivial reason.

The per-compilation tasks the plugin also registers — `detektJvmMain`, `detektJvmTest`,
`detektAndroidDebug` and so on — *do* set `classpath` from the compilation and run in full mode.
They are not what this repository invokes.

## Idea

- Attach a classpath to the aggregate task, so the analysis rules start running.
- Leave the aggregate task as it is, and stop describing it as enforcing rules it cannot run.
- Replace the aggregate task with the per-compilation tasks.

## Decision

The aggregate `detekt` task stays analysis-free for now, and the repository records that fact
instead of implying otherwise. The deferral has a home: it is tracked as **`detekt-classpath`**,
recorded in this file's "Follow-up" section and in step 3b of
`.agents/skills/singularity-todo-detekt-workflow` ("Adding a new rule"). That is where the ground
gets assigned, and it is the thing to reopen when the analysis rules are wanted — this paragraph is
the decision, not a dead end. The ground these rules would cover is owned by **no gate today**:
detekt's real coverage is its syntax-only rules plus ktlint's layout rules, and that is the claim
the documentation now makes. Turning analysis on wakes every rule in the pinned detekt that
implements `RequiresAnalysisApi`, across five modules at once, with an unknown finding count and
no baseline for any of them; that is a change of its own, not the answer to "why was one rule
silent".

**How this list was produced.** Take every rule entry carrying `active: true` in
`config/detekt/detekt.yml`, find the class that implements it in the pinned
`dev.detekt:detekt-rules-*:2.0.0-alpha.6` jars, and check whether that class declares
`dev.detekt.api.RequiresAnalysisApi`. The command is under "Consequences" and works for one name
at a time, which is the point: every name below is checkable, and a name that cannot be checked
does not belong here. Ten explicitly-active rules come out inert —
`ArrayPrimitive`, `CastNullableToNonNullableType`, `Deprecation`, `ElseCaseInsteadOfExhaustiveWhen`,
`LongParameterList`, `SpreadOperator`, `UnsafeCallOnNullableType`, `UnsafeCast`,
`UseAnyOrNoneInsteadOfFind`, `UseIsNullOrEmpty` — and so do the four style `Unused*` rules named
above. The same pass puts `UnusedParameter` and `UnusedPrivateClass` on the *other* side: they are
explicitly active and they run. The rules that are active by detekt's default config, in the
modules that build upon it, are dropped the same way.

Unused imports stay with ktlint. Nothing about the ownership decision changes; what changes is
the reason recorded for it: detekt was not a second, weaker verdict that disagreed with ktlint.
It was no verdict at all.

`desktopApp` is also the one module whose detekt block does not set
`buildUponDefaultConfig = true`. That is a separate inconsistency, not the cause here — the rule
was listed explicitly with `active: true` — and it is left alone.

## Rationale

A gate that reports green about rules it never ran is worse than a gate with fewer rules, because
the green is read as coverage. The honest state is the smaller claim: detekt enforces the rules
that work on the syntax tree, and ktlint owns the layout. Recording which rules are outside that
claim is what makes the gate legible; silently attaching a classpath would have converted a
documentation defect into a large, unreviewed change in what the tree must satisfy.

Attaching the classpath was rejected for this change rather than rejected outright. It is the
right end state if the analysis rules are wanted, and it needs its own measurement: a full detekt
run with analysis, a count per rule, and a decision per finding.

## Consequences

- Every rule that implements `RequiresAnalysisApi` is inert in this repository today, on every
  source set — including the test sources the desktop app lists. `UnusedImport` was one of them.
- ktlint's `standard:no-unused-imports` is the only rule catching an unused import, and it catches
  them. The 21 it found are gone.
- A reader can check the claim without trusting this note, in two ways.
  **The class**, one command per rule name:

  ```bash
  jars=$(find ~/.gradle/caches/modules-2/files-2.1/dev.detekt -name 'detekt-rules-*-2.0.0-alpha.6.jar' | grep -v sources)
  javap -classpath "$jars" dev.detekt.rules.style.UnusedImport | head -2
  # → ... extends dev.detekt.api.Rule implements dev.detekt.api.RequiresAnalysisApi
  ```

  Run against `dev.detekt.rules.style.UnusedParameter` the same command shows no `Requires*`
  interface, which is why that rule is not in the list above.
  **The behaviour**: set `UnusedImport: active: true` in `config/detekt/detekt.yml` and run
  `./gw :desktopApp:detekt --rerun-tasks`. Detekt prints its "requires type resolution but it was
  run without it" notice for the rule and reports nothing — the observable that gave the original
  silence away.
- A future rule that needs types will be just as silent. When one is added, the choice is to
  attach a classpath to the task that runs it, or to say in the rule's own comment that it does
  not.

## Follow-up

The deferral above is tracked as **`detekt-classpath`**, and this section is its record until a
tracker item references this file:

- **What it is:** attach a classpath to the aggregate `detekt` task (or move the gate to the
  per-compilation tasks the plugin already registers with one), so the rules in the list above
  start running.
- **What it needs:** a full analysis run, a finding count per rule, and a decision per finding
  bucket — the measurement this change deliberately did not take.
- **When it must be looked at:** whenever a rule is added to `config/detekt/detekt.yml`. Step 3b
  of `.agents/skills/singularity-todo-detekt-workflow` ("Adding a new rule") says so, and is the
  only place the repository checks. Without that step a new analysis-dependent rule is inert and
  green, which is the exact failure this ADR was written about.

There is no automated gate for "a rule that cannot run was configured". Detecting that would need
the config-to-class mapping this ADR describes, run as a check; it is deliberately not added here
because it is the same change as attaching the classpath, seen from the other side.

## Links

- ADR `2026-11-03-the-formatting-rules-have-one-owner.md` — the handover this explains.
- ADR `2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md` — the move before it.
- `docs/architecture/quality.md` — where the rule ownership is indexed.
- `config/detekt/detekt.yml`, `config/detekt/detekt-rules-module.yml`
- `desktopApp/build.gradle.kts`, `shared/build.gradle.kts` — the tasks' `source` lists.
- detekt `2.0.0-alpha.6`: `DetektPlain.kt`, `Detekt.kt`, `CliArgument.kt`, `RuleDescriptor.kt`,
  and `dev.detekt.rules.style.UnusedImport`.
