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
error. `WAIT_FOR` names an element *id*: the helper waits with
`visible: {id: "${WAIT_TARGET}"}`, so a name the entity is called is not a
value it can resolve — the gate holds callers to ids.

**The order the helper waits in.** The link opens the seed, the shell wait
lets the app come up, and the entity wait absorbs the arrival of the row. The
order is part of the contract: a wait that runs *before* the link waits for
whatever was on screen already — the row the seed is about to rewrite
satisfies it, and the flow goes on to assert on a row that has not been
written yet. The gate holds the helper to that order.

The shell wait has to stand *between* the link and the entity wait, and that is
a third step rather than a detail of the second. The app was already up before
the link arrived, so the shell wait returns at once — the row the seed wrote is
still on its way. An entity wait above it is a wait for a row that may not have
been written yet: the stale-row race the shell wait exists to close. The gate
reads the shell wait by exclusion — the entity wait is the only wait that
targets `$WAIT_TARGET` — so the check does not depend on how the shell wait is
written.

**What the helper may wait for.** The screen the seed leaves the app on: the
agenda's Today tab, with its Overdue and Today sections and the rows of the
tasks in them. The helper does not navigate, so an element that is not on that
screen is not there to be waited for — the wait would run out its timeout on an
element the app never renders, and the flow would fail on the helper before it
had a chance to navigate to the entity itself. A `WAIT_FOR` that names a note,
a project, a profile or a task in another tab is therefore refused by the gate:
the flow waits for those itself, after it navigates. The gate knows the screen
from the seed's own query — a task that is overdue or due today is in the Today
tab, a task due later is in the Upcoming tab, a task without a date is in the
Inbox — and it holds the caller to it. A date the seed resolves against the
device's own "today" (`YYYY-MM-DD`) is not decidable statically, so such a task
is treated as not on the screen.

**What the helper's wait does not do.** It does not scroll. A wait that
scrolls moves the viewport a flow is about to assert on, and the flow's own
`scrollUntilVisible` is the thing that absorbs a row below the fold. And it
covers only the screen the seed leaves the app on — the agenda. A note, a
project, a task in another tab, or a row the agenda lists further down is the
flow's to wait for, after it navigates: by the row rule (wait for the row) or
by the container rule (wait for the section that renders only because the
seeded task is in it).

**What a link writes.** One kind. The seeder takes the first kind the query
names — task, then note, then project, then the bulk projects
(`DebugSeedActivity.seedEntity`) — so a link that names a task and a note
writes the task and nothing else. The gate's model writes one kind too: a
model that invents the note would hold a flow to a wait for a row that is
never written, and it would hand that row the container of the task, so a wait
for the section would "cover" an entity the seed did not write.

A link that cleans up, resets or changes the session writes no entity at all:
the seeder answers it and returns before it writes anything
(`DebugSeedActivity.seed`), whatever else the query carries.

**What a count the seeder refuses writes.** Nothing. `SeedCount.parse` takes
a count in `1..10000` and refuses anything else, and the seeder then writes
no row at all. Such a seed creates no entity, so it names none to wait for.

**What the helper may name as a target of its wait.** A row of the screen
the seed leaves the app on, and the section that holds it. Three things are
not: a row of a *multi-row* seed (the list the seed filled is longer than the
screen, so the row may be below the fold, and the helper does not scroll), a
section the flow *named before the seed* (it was there before the seed, so a
wait for it returns at once and cannot be the evidence that the row the seed
wrote has arrived), and anything on another screen.

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

**Whose wait it is.** The helper's wait runs inside the invocation of the seed
that asked for it, so it is evidence for that seed's entity and for no other.
A wait the helper made for a later seed says nothing about the entity an
earlier seed wrote — and the row the earlier seed wrote is already on screen,
so the later wait returns at once and proves nothing about the write the flow
asserts on. Two seeds of one row, where only the second one names the row in
`WAIT_FOR`, are therefore a flow the gate rejects: the row was there before
the second seed ran, so nothing waited for what the second seed wrote.

**What the helper may name as a target of its wait.** The elements the seed
brings onto the screen the app is left on: the row of a task that is due
today and the only row of its seed, or the section that holds it. Three kinds
of element are not among them:

- **A row of an overdue task.** The Today tab collapses the overdue section
  once the profile holds enough overdue tasks
  (`AgendaPresets.LARGE_OVERDUE_THRESHOLD` — twenty), and the helper neither
  expands the section nor scrolls to the row. A wait for such a row runs out
  its timeout on a row the app does not render, even though the seed wrote it.
  The overdue *section* is a target — its header is rendered whether the
  section is expanded or collapsed — so an overdue seed is waited for by its
  section, the container rule.
- **A row of a multi-row seed.** The list the seed filled is longer than the
  screen, and the helper does not scroll.
- **A section the flow named before the seed.** It was there before the seed
  ran, so the wait for it returns at once.

An entity on another screen — a note, a project, a task in another tab — is
not among them either: the helper does not navigate, so its wait would run out
its timeout on an element the app never renders.

**How an entity is waited for.** By the row the app renders it under, by the
container that renders only because the entity is in it, or by the name the
entity is called. The name is the handle a rename needs: the row was already
there under the old name, so only the new name tells the rename from the row
it renamed. The flow waits for the name; the row's id stays the assertion's
subject. A node can carry both — the task editor's title field publishes its
value as the node's text — so a wait may address the entity by the id of the
field it is in *and* by the title it holds:
`visible: {id: task_editor_title_input, text: "Buy milk"}` is one wait for one
entity, and not a wait for whichever entity the screen happens to show.

**When a container covers an entity.** The container rule asks for a section
that renders *only* because the entity is in it. A section that was already
there before the seed cannot be that section: the wait for it returns at
once, and the row the seed wrote is still on its way. The gate reads the
flow for the tell it can see — a section the flow named before the seed was
there before the seed — and holds the flow to the row rule in that case. What
the gate cannot see is the state of the app before the flow ran: a section a
previous run left behind is invisible to it, and a flow that waits for a
section it did not bring in owns that.

**What counts as an assertion on the entity.** An assertion by the id of the
row the app renders the entity under, and an assertion whose text is exactly
the name the entity is called. A text that is not the name — a count, a
heading, a pattern — is not about the entity. An assertion by the name is
therefore an assertion on the entity, and something has to wait for it. Both
spellings of an assertion count: the block form (`assertVisible: {text: …}`)
and the short one (`assertVisible: "…"`), where the text is the value of the
command itself. A gate that reads only the block form does not see the short
one at all, and the flow it belongs to passes the gate while asserting on a row
that may not have arrived.

**What a wait covers.** A wait that waits for the element to be *gone* —
`notVisible`, `absent` — says the opposite of what an assertion needs, and
covers nothing.

**How many entities a seed writes.** A seed that writes N rows writes N
entities: every row is an entity of its own, with its own name and its own row
in the tree. A wait therefore covers one entity — the entity whose row it
names, whose name it names, or whose container it names — and not the seed as
a whole. A wait for the first row of a two-row seed says nothing about the
second row: it is another entity, with an assertion of its own to wait for.
The container is the one handle that covers a whole seed, because the section
that holds the rows holds every entity of the seed.

**A seed that writes more rows than a wait can outlast.** Writing a row costs
the seeder about 30 ms, so a seed of a thousand rows takes about half a minute
— longer than the shell wait the helper is given (30 s), which is the largest
budget the Maestro layer has. A seed that outlasts the budget cannot be waited
for at all: the wait times out before the rows are written. Such a seed is
therefore written in chunks: each chunk is a seed of its own, each chunk fits
inside the wait, and the flow waits for the section that holds the rows once
the last chunk is in. The chunks need base titles of their own, because a seed
titles its rows after the query it was given, and two chunks with the same
base title would write the same rows twice. The cost of a row is the seeder's
business and is tracked separately (issue #3135).

**What the rule is held against.** `SeedHelperWaitTest` holds the rule to
fixtures as well as to the tree: flows written to break it — a wait that
precedes the seed, two seeds of one row with a wait that covers only the first
— and a helper whose entity wait sits outside the guarded block, a helper whose
entity wait runs before the seed link, and a helper whose entity wait runs
between the link and the shell wait. A gate that cannot fail holds nothing.

**The runtime half.** The gate reads the YAML; it cannot hold what the helper
does on a device. `smoke/07-seed-helper-contract` is that half: a seed with no
`WAIT_FOR` (the input is optional, and the flow waits for the row itself), a
seed with a `WAIT_FOR` (the helper waits for the row, and the flow asserts on
it right away, with no wait of its own), and a seed that writes no entity
(`session=anonymous` — no row, so no entity is named, and the app still comes
up). The app is already up when the seeds run, so the helper's shell wait
returns at once — the case where a wait *before* the seed would be satisfied by
a row that was already on screen.

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
  wrote, a `WAIT_FOR` names an element the seed brought on screen, and every
  flow that asserts a seeded entity waits for it first.
- A `WAIT_FOR` names an element on the agenda's Today tab. A note, a project,
  a profile or a task in another tab is waited for by the flow, after it
  navigates — the helper does not navigate, and its wait would time out on an
  element the app never renders on the tab it is left on.
- Changing the helper invalidates the fingerprint of every flow that runs it:
  the qualification fingerprint covers the files a flow runs transitively, so
  the 69 flows that seed now have a stale baseline. They are not broken — their
  assertions and their navigation are unchanged — but the evidence that
  admitted them was three passing runs against the old helper. The sweep is
  tracked in issue #3140; it is not this entry's work, and the ADR does not
  excuse it with exceptions.
- `smoke/07-seed-helper-contract` is the runtime half of the contract: the
  optional input, the named entity, and the seed that writes no entity.
- A flow that seeds an entity the agenda does not show still waits for it
  itself, by the row rule or the container rule.
- `scripts/check-ui-wait-budgets.py` now reads the waits nested in a
  conditional `runFlow` as well: the helper's entity wait is a wait the gate
  holds to the budget of its kind.
- The helper does not parse `QUERY`, and never will: the query language
  belongs to the seeder.
- `agenda/07-large-overdue` seeds a thousand rows in five chunks of ~200, each
  chunk a seed of its own with a base title of its own, so that every chunk
  fits inside the shell wait (30 s). The chunks name no entity to wait for —
  the wait for the section that holds the rows comes after the last chunk, and
  that section is the container of every chunk's rows.
- A wait covers one entity. A seed that writes N rows writes N entities, and
  the assertions on the rows of such a seed are covered by the wait that
  names the row, the name, or the container — not by a wait that named one
  other row of the same seed.
- The gate's model of a seed follows the seeder, not the query: one kind per
  link (task, then note, then project, then bulk projects), a count the
  seeder refuses writes nothing, and a container belongs to the entity the
  seed wrote.
- A `WAIT_FOR` may only name a row that is on the screen the seed leaves the
  app on and that is there to be seen: not a row of a multi-row seed (the
  helper does not scroll), and not a section the flow named before the seed
  (the wait for it would return at once).
- `nav/deep-link-task` and `notes/03-internal-link-navigation` assert the
  title of the task they opened, and wait for the editor by the id of its
  title field *and* by that title: the wait names the entity, not whichever
  entity the editor would show.
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
- `agenda/07-large-overdue` — **green on this device** (2026-11-23), after the
  bulk seed was split into five chunks: every chunk seed completes, the
  overdue section shows the full count (`1001`), the Today section and
  `task_item_todayreachable` are reachable, and the expanded list shows
  `task_item_bigoverdue_1`. Before the split the flow failed on this device
  — writing a thousand rows takes ~30 s, which is longer than the helper's
  shell wait (30 s). The row cost itself (~30 ms a row) is the seeder's
  business and is tracked in issue #3135.

## Links

- `Maestro/helpers/seed.yaml` — the helper; its header carries the contract.
- `Maestro/CONVENTIONS.md` — the helper table and "Waiting for what you seeded".
- `.agents/skills/singularity-todo-maestro-flows/SKILL.md` — the helper list and
  the seeding walkthrough.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedHelperWaitTest.kt` —
  the gate that holds the helper and its callers to this entry.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeededEntityWaitTest.kt` —
  the gate that holds the flows to the rule that one wait covers one entity.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedWaitTree.kt` —
  the tree the two gates read; the model of a seeded entity lives here.
- Issue #3135 — the bulk seed writes ~30 ms a row, so a thousand-row seed
  outlasts every Maestro wait. Tracked separately; the seed's write path is
  not changed by this entry.
- Issue #3140 — the qualification sweep that follows from this entry's change
  to the helper.
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
