---
title: "The seed helper waits for the entity the caller names"
date: 2026-11-23
status: accepted
supersedes: 2026-11-05-the-seed-waits-for-the-shell-not-the-row
tags: [maestro, e2e, testing, contracts]
---

## Context

`Maestro/helpers/seed.yaml` seeds through the `todo-debug://seed?…` deep link
and waits for the agenda shell (`nav_tab_today`). That was the whole of its
waiting contract, and the 2026-11-05 entry recorded it as deliberate: the
helper is handed a query string and not a typed entity, so it cannot know the
id the flow will address the seeded row by.

That reasoning holds for an id the *app* generates. It does not hold for the
rows a flow addresses by their title: the row of a task titled "Buy milk" is
`task_item_buy_milk`, the rule is `TestTags.slug`, and the seed query that
wrote it names that title. The helper had the information all along; what it
lacked was a way for the caller to say *wait for this*.

The cost was a wait every flow had to remember, and the flows that forgot it
failed on a device for a reason the YAML does not show: the shell wait returns
at once (the app was already up before the link arrived), and the row the seed
wrote is still on its way. Four flows in the tree asserted a seeded row with
nothing waiting for it — `agenda/07-large-overdue`, `tasks/08-date-buckets`,
and the two capture flows that assert a row of the demo dataset.

## Idea

- Let the helper wait for the row, and let the caller say which row.
- Keep the helper generic: it must not parse `QUERY`. The query language is
  the seeder's (`SeedLinkKeys`), and a second reader of it in a YAML helper is
  a second dialect of it.
- Keep the seed that writes nothing working: `session=anonymous`, `cleanup=`
  and `reset=` have no row to wait for.

## Decision

**The seed helper takes an optional `WAIT_FOR` — the element the flow is going
to assert on — and waits for it before it returns.**

**What the caller names.** A title-named entity — a task, a note — by the
element its title produces (`task_item_buy_milk`, `note_item_by_title_alpha`);
an id-named entity — a project seeded with `id=` — by that id
(`menu_project_node_roadmap`). The title→id rule is the app's own
(`TestTags.slug`); the helper does not recompute it, and
`SeedHelperWaitTest` holds every caller to it.

**What the helper does with it.** It waits for that element with the item
budget, and the wait is guarded: no `WAIT_FOR`, no wait. The input is optional
by construction — the helper reads it through a `typeof` guard and turns an
undefined one into an empty target, rather than into Maestro's missing-variable
error.

**What the helper's wait does not do.** It does not scroll. A wait that
scrolls moves the viewport a flow is about to assert on, and the flow's own
`scrollUntilVisible` is the thing that absorbs a row below the fold. And it
covers only the screen the seed leaves the app on — the agenda. A note, a
project, a task in another tab, or a row the agenda lists further down is the
flow's to wait for, after it navigates: by the row rule (wait for the row) or
by the container rule (wait for the section that renders only because the
seeded task is in it).

**What a flow still has to do.** Name the entity it wants waited for, when the
helper's screen holds it. A flow that seeds a row elsewhere still waits for
that row itself.

**When the wait has to sit.** A wait covers an assertion only when it stands
between the seed that wrote the entity and the assertion. A wait that precedes
the seed says nothing about the entity — the row could have been on screen
before the seed wrote it — and a seed that *rewrote* the row needs a wait of
its own: the row is the same row, but what the flow asserts on is what the
last seed wrote. A rename is the sharp case: `menu/06-tree-live` seeds a
project, renames it, and asserts on the renamed row, so the wait that covered
the first seed does not cover the second.

**How an entity is waited for.** By the row the app renders it under, by the
container that renders only because the entity is in it, or by the name the
entity is called. The name is the handle a rename needs: the row was already
there under the old name, so only the new name tells the rename from the row
it renamed. The flow waits for the name; the row's id stays the assertion's
subject.

**What the rule is held against.** `SeedHelperWaitTest` holds the rule to
fixtures as well as to the tree: flows written to break it — a wait that
precedes the seed, two seeds of one row with a wait that covers only the first
— and a helper whose entity wait sits outside the guarded block. A gate that
cannot fail holds nothing.

**How the order is read.** The gate reads the tree's commands the way they
run: every command carries the path of its own place in the flow — its index
in its file, behind the `runFlow` that pulled that file in. A wait that runs
*before* the seed inside a helper is therefore before the seed, not beside it.
A flat index would give every command of a helper the index of the `runFlow`
that pulled it in, and the wait would look like it ran after the seed.

## Rationale

The 2026-11-05 entry's real point was that a helper must not guess. Guessing
would mean parsing `QUERY` and re-implementing the slug rule in YAML and
JavaScript — a second implementation of a rule the app owns, and one no test
could hold to the app. Naming the element instead keeps the helper honest: it
waits for what it is told to wait for, and the naming is checked against the
app's own tags.

The wait has to be in the helper rather than in the flow, because the failure
it prevents is a *forgotten* wait. A wait that every flow has to remember is
a wait some flow will not have; a wait the helper makes for the flow that
seeds is made every time. The flows that needed the wait anyway keep it — the
helper's wait does not scroll, and it does not know about another screen.

The guard is what makes the input optional in a language where a missing
variable is an error: `WAIT_FOR` undefined is an empty target, and an empty
target is no wait. A seed that changes the session, cleans up or resets the
profile therefore behaves exactly as it did before.

## Consequences

- `Maestro/helpers/seed.yaml` waits for the shell and, when the caller names
  one, for the entity. The header carries the contract.
- The contract is written in three places — the helper's header,
  `Maestro/CONVENTIONS.md` and the `singularity-todo-maestro-flows` skill —
  and held by `SeedHelperWaitTest`: the helper keeps a guarded wait, a seed
  that writes no entity names none, a `WAIT_FOR` names an entity the seed
  wrote, and every flow that asserts a seeded entity waits for it first.
- A flow that seeds an entity the agenda does not show still waits for it
  itself, by the row rule or the container rule.
- `scripts/check-ui-wait-budgets.py` now reads the waits nested in a
  conditional `runFlow` as well: the helper's entity wait is a wait the gate
  holds to the budget of its kind.
- The helper does not parse `QUERY`, and never will: the query language
  belongs to the seeder.
- `agenda/07-large-overdue` waits for the section the thousand seeded tasks are
  in, right after the seed that wrote them: a link that arrives while that
  seed is still writing finds the app on the seed's own screen, and the flow
  has to wait for the rows before it sends the next one.
- `menu/06-tree-live` waits for the name the rename gave the project, and
  asserts on the row's id: the row was already there under the old name, so
  the row's id cannot tell the rename from the row it renamed.

## Verified on device

`realme RMX3938 (0O65321F32204DD5)`, the E2E build:

- `tasks/08-date-buckets` — the flow that names the entity it wants waited
  for. The trace shows the helper's wait running (`Assert that id:
  ${WAIT_TARGET} is visible... COMPLETED`) and the flow's own assertion on
  the same row passing right after it.
- `menu/06-tree-live` — the rename wait, on the menu's sheet.
- `agenda/07-large-overdue` — the section wait after the big seed, and the
  thousand-row count the flow asserts on.

## Links

- `Maestro/helpers/seed.yaml` — the helper; its header carries the contract.
- `Maestro/CONVENTIONS.md` — the helper table and "Waiting for what you seeded".
- `.agents/skills/singularity-todo-maestro-flows/SKILL.md` — the helper list and
  the seeding walkthrough.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedHelperWaitTest.kt` —
  the gate that holds the helper and its callers to this entry.
- `Maestro/flows/tasks/08-date-buckets.yaml` — a flow that names the entity it
  wants waited for.
- `Maestro/flows/agenda/07-large-overdue.yaml` — a flow that waits for the
  section the seeded task is in.
- `Maestro/flows/menu/06-tree-live.yaml` — a flow that waits for a renamed
  entity by the name it was given.
- `docs/decisions/2026-11-05-the-seed-waits-for-the-shell-not-the-row.md` — the
  entry this one supersedes.
- `docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md` — the
  seed owns the row of its title.
- `config/docs/ui-wait-budgets.txt` — the budgets the helper's waits are held
  to.
