---
title: "One name answers one question; the four vocabularies are checked in step"
date: 2026-11-06
status: accepted
tags: [tasks, naming, agenda, ai, domain]
---

# One name answers one question; the four vocabularies are checked in step

## Context

Four vocabularies answer the same questions about tasks and each uses its own words:
`TaskFilter` (16 shapes), `TaskSelection` (the AI tool's 11 selections),
`AgendaPresets` (the agenda's tabs and sections) and the agenda's section headers —
the titles a user reads above a list of tasks. Nothing checked that they stayed in
step, so a name could come to mean two things without anyone noticing — which is
exactly what "Inbox" did (see
[2026-11-04-inbox-names-the-unscheduled-tab.md](2026-11-04-inbox-names-the-unscheduled-tab.md)):
the agenda tab meant "no due date", the filter meant "not deferred", and the two
disagreements were written down in prose that a rename could outrun.

The prose was not the problem. The problem was that the agreement between the four
vocabularies lived only in prose, and prose is not checked by anything.

## Idea

Write down the question each name answers, and check the four vocabularies against
each other. Either a shared name means the same thing everywhere, or the difference
is declared, on purpose, with a reason.

## Decision

`TaskVocabulary` (commonMain, next to the names it describes) is a code registry: every
filter shape, every selection wire name, every agenda preset and every agenda section
header is registered with the question it answers, and the names are compared
case-insensitively.

The rule is **one name, one question**. Where two vocabularies use the same name and
answer different questions, the difference is declared as a `Divergence` with a reason,
rather than left to be discovered. Two divergences are declared today: `today` (the
agenda tab shows overdue on top of today's tasks) and `upcoming` (the agenda tab is a
horizon, tomorrow to next week, where the filter is exactly today). The third one —
`inbox`, the read-only legacy alias of `active_not_deferred` — is gone: the alias was
removed on 2026-11-06, so the wire no longer answers to that word.

A date-shaped name's answer is not written by hand either. The question a name gives
is looked up from the date buckets it holds, and the buckets are read out of the
preset's own selectors (`Selector.heldBuckets()`), so the answer a preset gives is the
answer the code gives. A preset that starts holding different buckets cannot keep its
old answer — the drift test holds the registry to both.

A drift test (`TaskVocabularyTest`) enforces the rule: it fails when a name appears in
two vocabularies with different questions and no declared divergence, when a declared
divergence no longer diverges (a stale declaration), and when a filter shape, selection,
agenda preset or section header is missing from the registry.

## Rationale

The bug was never the word "Inbox". It was that four vocabularies answered the same
questions with different names and nothing checked they stayed in step. Renaming fixes
one name; a registry fixes the class.

The registry is code rather than a generated export, because the check needs to live
next to the thing it checks and fail in the normal test run. A generated artifact
(`tool-surface.json` style) would need a Gradle task, a second file to keep in step,
and would still not answer "do these four agree".

Divergences are declared instead of hidden because some of them are real product
decisions, not accidents: the agenda's `today` tab deliberately stacks overdue on top,
and `upcoming` is deliberately a horizon. Declaring them puts the difference in a
reviewable place, where a change to it is a deliberate act rather than an accident.

Completeness is checked by reflection over the four vocabularies, so a new filter shape,
preset or section header fails the test until someone says which question it answers.
That is the point: the question is the thing that was missing.

## Consequences

- Adding a `TaskFilter` shape, a `TaskSelection`, an `AgendaPresets` preset or an agenda
  section header now fails `TaskVocabularyTest` until the registry names the question it
  answers. That is the intended cost.
- The names a user reads are in the registry too, so the guarantee holds on the layer
  where the word "Inbox" actually diverged rather than only on the internal one.
- The registry is the machine-checkable companion to `docs/CONTEXT.md`, which remains the
  single source of truth for domain vocabulary (see
  [2026-09-26-domain-glossary-policy.md](2026-09-26-domain-glossary-policy.md)). The
  glossary explains the terms; the registry checks that the code agrees.
- `TaskVocabulary` is read by the drift test only, so it is exempt from the
  unwired-surface gate via `scripts/find-unwired-surfaces-baseline.txt`.
- A name shared by two vocabularies with the same question needs no declaration; a
  name shared with a different question cannot be added without a declared divergence.

## Links

- Issue #1950
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskVocabulary.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskVocabularyTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskSelection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- [2026-11-04-inbox-names-the-unscheduled-tab.md](2026-11-04-inbox-names-the-unscheduled-tab.md) — the naming split this registry checks
- [2026-09-26-domain-glossary-policy.md](2026-09-26-domain-glossary-policy.md) — the glossary this registry sits beside
