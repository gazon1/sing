---
title: "The seed helper waits for the shell; the flow waits for the entity it seeded"
date: 2026-11-04
status: accepted
tags: [maestro, e2e, testing, seeding]
---

## Context

`Maestro/helpers/seed.yaml` is the suite's only seed entry point: it opens the
`todo-debug://seed?QUERY` deep link and returns. Its own wait is on the agenda
shell (`nav_tab_today`), and that wait is only meaningful when the link
cold-starts the app. In every flow that starts with `launch-clean` the shell is
already up before the seed is applied, so the helper can return before the row
it wrote exists — and a flow that then asserts the row has nothing absorbing
the arrival.

The helper cannot simply wait for the seeded row. A query may write more than
one entity; a note or a project gets an id the flow cannot know; a seed of an
existing title updates the row rather than adding one; and `session=anonymous`
creates no entity at all, so there is no row to wait for. The waiting rule is
therefore a contract between the helper and its callers, and a contract that is
not written down is a rule every flow re-derives — or forgets.

## Idea

Two options were open. The helper could derive the row's test tag from the
title it was given and wait for it, which puts the wait in one file but cannot
cover a multi-entity seed, a generated id, or the anonymous seed — the helper
would have to know which of its own queries produced an addressable row. Or the
contract could say plainly that the caller waits, and every caller does. A
third possibility, a flow-local `delay`, was never a real option: a fixed sleep
is either slower than needed or shorter than needed, and the repository's
conventions forbid it.

## Decision

The helper waits for the agenda shell and returns; the caller waits for the
entity it seeded before asserting on it. A wait on a container that renders
only when the entity exists — a Plans timeline section, the agenda behind a
health bucket — counts as that wait, because the container is drawn only while
a row falls in it. A flow that then asserts the row itself waits for the row
too, with `extendedWaitUntil` rather than `scrollUntilVisible`, so the wait
does not scroll the container out of the viewport. A seed of
`session=anonymous` creates no entity, so it waits for nothing. The one
exception is a flow asserting the entity's *absence* on a screen that filters
it out; that absence is asserted directly, and the entity's wait belongs on
the screen that shows it.

The helper carries this contract in its own header, and the conventions file,
the Maestro skill and its runbook state the same rule.

## Rationale

The caller is the only party that knows what it is going to assert on. A Plans
flow asserts a section; a task flow asserts a test tag; a search flow asserts
a result the query has to produce first. Putting the wait at the point of the
assertion is the only place that knows which of those is the arrival signal,
and it keeps the helper free of a query parser that would have to reproduce
the app's slug rules for generated ids. The cost is that every flow carries
one more step — absorbed by `seed-task.yaml`, which seeds the fixed title
"Buy milk" and waits for that row itself, and by the shared `plans-section`
helper for the timeline.

## Consequences

- After `seed.yaml`, a flow that seeds a task, a note or a project waits for
  that entity before asserting on it — a wait, never a bare `assertVisible`.
- `session=anonymous` seeds no entity, so it needs no row wait.
- A wait on a container that only renders when the entity exists counts as the
  wait for the entity; a flow using one says so in a comment.
- A row wait inside a scrolling timeline uses `extendedWaitUntil`, not
  `scrollUntilVisible`: a scroll can carry the section the flow waited for out
  of the viewport.
- An absence assertion is the documented exception; the entity's wait belongs
  on the screen that shows it.
- Do not re-add an inbox→today tab round-trip to the helper. The 2026-10-12
  round-trip was removed because the row it was meant to surface had simply
  arrived below rows earlier runs had left behind.
- The 2026-10-12 entry is pointed at by a banner, not by a `supersedes:` link.
  The digest treats any of the three supersede declarations — a status, a
  `superseded-by`, a forward `supersedes` — as "this decision no longer
  stands" and drops the linked entry whole; that file's other decisions are
  still in force, so only a reader, not the generator, is told that §9 has
  moved.

## Links

- `Maestro/helpers/seed.yaml` — the contract in the helper's header
- `Maestro/CONVENTIONS.md`, `.agents/skills/singularity-todo-maestro-flows/`
- Supersedes the round-trip claim in
  [2026-10-12-maestro-e2e-fixes.md](2026-10-12-maestro-e2e-fixes.md) §9
- Issues #1906, #1996, #1997
