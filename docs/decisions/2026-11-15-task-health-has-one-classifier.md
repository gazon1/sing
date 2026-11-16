---
title: "Task health has one classifier, and every surface asks it"
date: 2026-11-15
status: accepted
tags: [tasks, agenda, statistics, ai, health]
---

# Task health has one classifier, and every surface asks it

## Context

The app reports the same three problems with a task list in three places: the
Statistics Health tab counts them, the AI tools describe them to a reader, and the
Agenda's built-in views list them. Each needs the answer to one question — *which
bucket is this task in, given that it can be in several at once?* — and the answer
has a precedence: overdue first, then blocked, then stagnant, then fine.

The precedence was written twice. Once in `TaskHealth.kt`, where the Statistics
summary classified tasks, and again in `TaskToolSupport.kt`, where the AI tools
did. The two read the same selectors, so they agreed on the day they were written
and nothing kept them agreeing. They had already drifted in a way no test could
see: the stored staleness setting is a number of days where `0` means "leave it at
the default", the Statistics path translated `0` to the 21-day default, and the
tools passed `0` straight into the selector — a zero-day window. On the default
configuration the two agreed; on a user who had set `0` the tools called every
undated task stagnant and the card called none.

Two smaller gaps sat beside it, both from #2977's audit:

- The Stagnant health card counted with the stored threshold, and the agenda view
  it opened (`AgendaPresets.byStagnant()`) used the 21-day default. Moving the
  slider made the card say "12" and the list it opened hold a different set.
- The health cards showed a number and a label and nothing about the rule the
  number was made with, which is the part a user cannot infer.

## Idea

Three shapes were considered for the classifier:

1. **Keep two copies and add a test that compares them.** Cheap, and it catches
   drift only for the fixtures the test happens to build — the `0` case would have
   needed someone to think of it.
2. **Move the precedence into the AI tool and have Statistics call the tool.**
   Inverts the dependency: a statistics screen would reach into the AI feature for
   a domain rule.
3. **One small domain object, both callers constructed from it.**

## Decision

Option 3. `HealthRules` in `feature/tasks/domain/logic/TaskHealth.kt` holds the
three selectors, built once per instance, and answers `bucketOf(task, context)`.
`summarizeTaskHealthFromSelectors` classifies through it, and `TaskToolSupport`
classifies through it. The stored-setting translation is `stagnationWindowOf(Int)`,
also in that file, called by both.

The Stagnant drill-down reads the stored threshold the way the Today tab reads its
work hours: a composable in `AgendaRouteMapping.kt` collects
`stalenessThresholdDays` and builds the preset from it. The decision itself is a
pure function, `stagnantDefinition(Int)`, so it can be pinned without mounting
Compose.

The health cards gained a one-line `hint` stating the rule
(`TaskHealthBucket.explanation()`), on the existing `StatCard`.

## Rationale

The precedence is a product decision, not an implementation detail of either
screen. Putting it in the tasks domain — where `TaskHealthBucket` already lives —
gives it one owner without making either caller depend on the other. `HealthRules`
is a class rather than a function because the selectors should be built once for a
whole list, which is the same reason `Blocking.blockedTaskIds` takes a list and
returns a set.

`stagnationWindowOf` exists because "what does a stored 0 mean" is the exact
question the two paths answered differently. A shared function makes it a
definition rather than a coincidence.

The threshold in the drill-down is read at the screen, not the preset, because a
preset is pure data that a saved view can store (`AgendaDefinition` is serialized
into `saved_agenda_views`). A threshold baked into `byStagnant()` would be frozen
at save time and wrong the moment the user moved the slider — the same defect the
`relativeDueDate` prefill was introduced to avoid.

## Consequences

- The Statistics card and the list it opens now count the same tasks under any
  configured threshold. `StagnantDefinitionTest` pins that the configured window
  reaches the definition, and `HealthClassificationAgreementTest` pins that both
  surfaces put each task in the same bucket, including the stored-`0` case.
- The AI tools and the Statistics screen report identical counts. A future change
  to the precedence must edit `HealthRules`, and both surfaces move together.
- `AgendaStartRoute.toDefinition()` returns the *default-window* Stagnant preset,
  because a pure function cannot read settings. The screen is the authority; the
  two produce the same sections and differ only in the window value. This is
  documented at the function.
- The health cards carry one extra line of text. No new test tag: the existing
  `health_bucket_*` tags are on the card, and the hint is inside it.

## Links

- Issue #2977 — "close the Attention discoverability gap using existing Health and Agenda"
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskHealth.kt` —
  "why health is computed, never stored" is stated in its KDoc, alongside
  `TaskComputed`'s rule that a derived value is never stored in Room
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/TaskToolSupport.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaRouteMapping.kt`
