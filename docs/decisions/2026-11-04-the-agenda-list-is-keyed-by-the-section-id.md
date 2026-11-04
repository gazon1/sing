---
title: The agenda list is keyed by the section id
date: 2026-11-04
status: accepted
tags: [agenda, lazycolumn, ui]
---

# The agenda list is keyed by the section id

## Context

The agenda list scoped its `LazyColumn` item keys by the section's **display name**:
`header-${section.name}` for a section header and `${section.name}/${task.id.value}` for a
row. The name was never unique — a single-project selector renders as "Project", a tag
selector as "Any tag: N" — so a saved view holding two such sections repeated a key and
the whole list died with `Key "header-Project" was already used`. The name-scoped key had
been chosen on 2026-09-30 as a defence against a *different* crash: one task rendered in
two sections, which the `discard` flag is meant to prevent.

## Idea

1. Scope the keys by the section's id, which the agenda already treats as a section's
   identity.
2. Scope the keys by the section's position in the list.
3. Leave the keys alone and make the display names distinct.

## Decision

Both item keys are scoped by the section's id: `header-${section.id}` for the header and
`${section.id}/${task.id.value}` for a row. The id is the same handle the section's '+'
button already uses for its prefill draft, so the list now keys on what the rest of the
agenda keys on.

## Rationale

The name was only ever a proxy for identity, and a poor one: it is user-facing text that
the selector composes, so two sections of one saved view can legitimately read alike. The
index would have been unique too, but it moves when the list is reordered, which would
move a task's identity with it; the id does not. Distinct display names are not something
the agenda can promise — the name is a rendering of the selector, not a key.

The `discard` rule from 2026-09-30 still holds: a task must not be rendered in two
sections. It is now a rule about what the sections contain, not about how the list
survives a misconfiguration.

## Consequences

- An agenda list is keyed by `section.id`. Never by `section.name`, which is not unique.
- A `LazyColumn` key in the agenda is scoped by the section's id whenever a task can
  appear in more than one section.
- A section's display name is free text for the reader and may repeat inside one saved
  view.
- A saved view's sections hold distinct ids — the id is what the list keys on, so an id
  that repeats is an id that repeats a key.
- The `discard = true` rule for overlapping bucket sections still applies; it keeps a task
  out of a second section rather than making the list tolerate it.

## Links

- `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/AgendaDefinition.kt`
  (`Section.effectiveId`)
- Supersedes in part: [2026-09-30-agenda-section-discard-missing.md](2026-09-30-agenda-section-discard-missing.md)
- Regression test: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/AgendaSectionKeyUiTest.kt`
