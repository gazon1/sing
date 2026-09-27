---
title: Guard against duplicate detekt rule registration
date: 2026-09-28
status: accepted
tags: [detekt, tooling, ci, parallel-work, postmortem]
---

# Guard against duplicate detekt rule registration

## Context

On 2026-09-27 the `doc-and-skills` sprint wired `NoFactoryViewModelRule`: the rule file and
its unit tests had existed for weeks, but the provider was never listed in
`META-INF/services/dev.detekt.api.RuleSetProvider`, so detekt never loaded it and the rule
had never run.

Independently, on the `fix/mvi-no-op-update-state` branch, the same rule was registered —
same two edits, same intent, a few hours apart, neither branch aware of the other. Both
branches merged into `main`.

The merge produced:

```
config/detekt/detekt.yml                          two `no-factory-viewmodel:` blocks
detekt-rules/.../dev.detekt.api.RuleSetProvider   NoFactoryViewModelProvider twice
```

and `./gradlew :shared:detekt` failed with:

```
found duplicate key no-factory-viewmodel
```

## Idea

The failure mode is not exotic, and it is not visible where it matters:

- The **diff is innocuous** — the same single line added in two different places in the
  file. Nothing about either diff says "this is already registered elsewhere".
- **Review does not catch it**, because the reviewer sees one branch and has no reason to
  suspect the other.
- **The build does catch it**, but three minutes into a Gradle run, with an error that
  names `config/detekt/detekt.yml` and not the actual cause (two people registering the
  same rule).

A duplicated YAML key is a hard error, not a silent last-one-wins, so this cannot merge
green. But the cost of finding out is a broken build on `main` rather than a two-second
check at commit time.

## Decision

Add `scripts/check-detekt-registrations.sh`, and run it in the two places a contributor
actually passes through:

- `check.sh` as step 0, **before** any Gradle invocation
- `just lint` as the first line of the `detekt` recipe

It fails on four conditions:

1. a duplicated provider class in the ServiceLoader file
2. a duplicated top-level rule-set key in `config/detekt/detekt.yml`
3. a provider listed in the service file that has no class in `detekt-rules/src`
4. a provider class declared in the source that is not listed

## Rationale

This is the same reasoning as the rest of the doc-and-skills sprint, applied to the case
that produced it: a rule that exists only in prose gets followed until someone is in a
hurry, and a rule that gets followed inconsistently fails in the worst place. The check
is four greps — well under a second — against a failure that costs minutes and produces a
misleading message.

Placing it first in both entry points matters more than the check itself. The reason the
original error was hard to act on is that it arrived at the end of a long run, attributed
to a config file. A fast check that fails in the first second, naming the duplicated
class, converts a puzzling build break into an obvious one.

Condition 4 (declared but not listed) is the same class of bug as condition 1, and was
already documented in the `detekt-rules-authoring` skill as common mistake #1 — the rule
that compiles and never runs. Having the script enforce the documented rule is the point;
the note was not enough.

## Consequences

- The class of bug is caught at commit time instead of at merge time.
- `check.sh` and `just lint` both fail fast on a malformed rule registry, with a message
  naming the provider.
- The activation checklist in `singularity-todo-detekt-rules-authoring` gains a fourth
  step for the parallel-work case, and common mistake #1b covers the duplicate.
- The check is deliberately narrow. It validates the registry's internal consistency; it
  does not verify that a registered rule is *active* in `detekt.yml` (that remains the
  skill's positive-control step) or that its rule set id matches the provider's
  `ruleSetId`. Those are covered by `just lint` actually running detekt.

## Links

- `scripts/check-detekt-registrations.sh`
- `.agents/skills/singularity-todo-detekt-rules-authoring/SKILL.md` — activation checklist
- `docs/decisions/2026-09-27-doc-and-skills-sprint-results.md` — the sprint that wired the
  rule, and `2026-09-27-doc-and-skills-sprint-findings.md` — its triage log
- `docs/decisions/2026-09-26-konsist-architecture-tests.md` — the ADR that credited the
  dormant rule with a catch it could not have made
