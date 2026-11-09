---
title: "The seed waits for the shell, and the flow waits for the entity it seeded"
date: 2026-11-05
status: accepted
tags: [maestro, e2e, testing, contracts]
---

## Context

`Maestro/helpers/seed.yaml` seeds through the `todo-debug://seed?…` deep link
and then does exactly one wait: `extendedWaitUntil` for `nav_tab_today`, the
agenda shell, with the shell's budget (30 000 ms). That is the whole of the
helper's waiting contract, and every flow that seeds through it depends on it.

The helper says so in its own header, and the maestro-flows skill repeats it —
but neither is the decision log, and the log's own record of this area says
something else. `2026-10-12-maestro-e2e-fixes.md` §9 reads: "`helpers/seed.yaml`
ends with a tab round-trip (inbox → today)". §17 of the same entry retracts it
— the taps were removed as not load-bearing — but §9 was never corrected, so
the entry still describes a helper behaviour that no longer exists. A reader
who takes the log at its word expects the helper to switch tabs.

## Idea

- Rewrite §9 of the 2026-10-12 entry so it describes the helper as it is now
  — no: an entry records what was true when it was written, and §9 was true on
  2026-10-12.
- Leave the contract where it already lives — the helper's header, the
  conventions file, the skill. They carry it, but they are not the log, and the
  log still contradicts them.
- Record the contract as its own dated entry, and point the old entry's §9 at
  it. This is what this entry does.

## Decision

**The seed helper waits for the agenda shell, and the flow waits for the
entity it seeded.**

**What the helper waits for.** The agenda shell — `nav_tab_today` — and
nothing else. That is the last thing the helper does.

**Why the helper cannot wait for the seeded row.**

- The seeded entity is addressed by an id the flow cannot always know: "a
  link carries an id, and the id the app generates is not one a flow can know"
  (`Maestro/flows/notes/03-internal-link-navigation.yaml`). A project is
  addressed by its id — `menu_project_node_roadmap` — which the query may
  carry (`id=roadmap`) and otherwise the app generates. The helper is handed a
  query string, not a typed entity, so it cannot know which addressing scheme
  the flow will use.
- The seed owns the row of its title: the seed replaces the task it names
  (`2026-10-28-the-seed-replaces-the-task-it-names.md`), so the row the flow
  will address is the seed's to make, not the helper's to predict.
- `session=anonymous` creates no entity at all, so there is nothing to wait
  for.

**What a flow does after seeding.** Every flow waits for the entity it
seeded, in the helper's own words: "Callers still `scrollUntilVisible` their
own row, which is what actually absorbs a slow arrival." A flow that seeds
`session=anonymous` needs no row wait — it waits for the shell and for nothing
else.

## Rationale

One deep link seeds five kinds of thing — a task, a note, a project, a
profile, an anonymous session. A wait the helper could perform for all of them
would have to know the tag scheme of each, and the tag scheme is the flow's
knowledge: the flow knows what it seeded and therefore what it will look for.
Waiting in the helper would not shorten anything; the row's arrival is exactly
what the flow's own `scrollUntilVisible` absorbs.

The tab round-trip §9 describes was a fix for a symptom with another cause.
The new row did arrive — below the rows earlier runs had left behind, which is
what made it look missing (§17). A helper that waits for the shell and leaves
the row to the flow is the smaller contract, and it is the one the flows
already assume.

The contract belongs in the decision log because the log is where a reader
looks for the reason a thing is the way it is. It was in the helper header and
in a skill, which is where the next reader does not look when the question is
"why does the seed not wait for my row".

## Consequences

- The seed helper waits for the agenda shell (`nav_tab_today`) and for nothing
  else. A flow that seeds waits for the entity it seeded; a flow that seeds
  `session=anonymous` waits for the shell alone.
- Never make the seed helper wait for a seeded row: the address of the seeded
  entity is the flow's to know (a generated id, or the `id=` the query
  carries), the row belongs to the seed that owns its title, and
  `session=anonymous` creates no entity.
- A flow that seeds a row scrolls to it — `scrollUntilVisible` — rather than
  trusting the helper to have waited for it.
- §9 of `2026-10-12-maestro-e2e-fixes.md` is superseded in part by this entry
  and stays as the record of what was believed on 2026-10-12.

## Links

- `Maestro/helpers/seed.yaml` — the helper; its header carries the same
  contract in the same words.
- `Maestro/CONVENTIONS.md` — the helper table and the wait conventions.
- `.agents/skills/singularity-todo-maestro-flows/SKILL.md` — the helper list
  and the seeding walkthrough.
- `Maestro/flows/notes/03-internal-link-navigation.yaml` — a flow that seeds a
  note and cannot know the id it carries.
- `Maestro/flows/menu/03-open-project.yaml` — a flow that seeds a project
  with an explicit `id=` and waits for it by that id.
- `docs/decisions/2026-10-12-maestro-e2e-fixes.md` — §9's round-trip claim,
  superseded in part by this entry; §17 removed the taps.
- `docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md` — the
  seed owns the row of its title.
- `config/docs/ui-wait-budgets.txt` — the shell budget the helper waits on.
