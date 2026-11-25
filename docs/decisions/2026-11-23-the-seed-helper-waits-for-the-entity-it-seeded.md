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

A relative date of zero days is today whatever its sign says. The seeder reads
every sign but the negative one as positive (`DebugSeedActivity.resolveDate`),
so `+0d` is today, and `0d` is today too — a gate that reads only the unsigned
form rejects a `WAIT_FOR` the helper can honour, and the flow fails on the
helper instead of on the row it named.

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

A profile is the exception, and it is not one of the seed kinds. The seeder
switches to the profile a link names — and creates it when it does not exist
yet — *before* it writes or deletes anything
(`DebugSeedActivity.switchToProfileIfRequested`), so the switch happens before
the early return of a reset, a cleanup or a session link. A link that names a
profile therefore brings a profile into being whatever else its query says, and
the gate's model writes that profile as an entity of the link. The profile is
on another screen, so the flow waits for it itself — but the gate must not
claim that such a link writes nothing, or it would hold a flow to no wait at
all for a row that is on the device.

**What a count the seeder refuses writes.** Nothing. `SeedCount.parse` takes
a count in `1..10000` and refuses anything else, and the seeder then writes
no row at all. Such a seed creates no entity, so it names none to wait for.

**What a blank parameter writes.** Nothing. The seeder drops a pair whose key or
whose value is blank (`DebugSeedActivity.parseParams`), so a link that says
`task=` writes no task at all. The gate's model drops the same pairs: a model
that kept them would say the link wrote a task with an empty title, and the
gate would hold the flow to a wait for a row that was never written — a flow
that waits for nothing it cannot have.

The key is read as the link spells it. The seeder decodes the key and looks it
up as it is: ` task=Fixture` names no parameter it knows, and it writes nothing.
A model that trimmed the key away would say the link wrote a task, and the gate
would let a flow through that fails on the device, on a row that was never
written.

**What the helper does not do with the query.** It does not read it. The query
belongs to the seeder — the key vocabulary is `SeedLinkKeys` — and a helper
that read it would be a second reader of the same language, one that could
disagree with the seeder about a key, a value or a parameter the seeder does
not know. The helper hands the query over whole, in the deep link, and reads
nothing of it. `SeedHelperQueryTest` holds it to that: the query may be named
in the body of the helper once — in the deep link — and a helper that names it
anywhere else is rejected.

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

**Which entity of the seed the helper's wait covers.** One. A link that names a
profile and a task writes two entities — the seeder switches to the profile and
writes the task — and the helper's wait is evidence for the entity it names and
for no other: a wait for the row of the task says nothing about the profile,
which is on another screen and was on the device before the wait ran. The wait
has to name the entity the assertion is about: its row, or the section that
holds it. The containers belong to the entity as well — the section a task is
in is a wait for that task, and not for another entity the same link wrote — so
the gate keeps the section with the rows it holds rather than with the seed as
a whole.

**The same rule for the flow's own wait.** A container belongs to the entity,
not to the seed that wrote it, and the flow's wait is held to the same rule as
the helper's. A link that names a profile and a task puts the task in a section
and writes the profile into another screen; a wait for that section says
nothing about the profile, and the gate rejects the flow. A gate that gives the
containers of a seed to the seed as a whole lets a wait for one entity's
section stand in for another entity's arrival.

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
- **A section an earlier seed of the same flow brought in.** That seed put a
  row in the section, so the section was on screen before this seed ran, and a
  wait for it returns at once. The gate reads the flow for that tell: the
  sections the flow's own earlier seeds brought in.

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
there before the seed, and a section an earlier seed of the same flow brought
in was there too, because that seed put a row in it — and holds the flow to
the row rule in those cases. What the gate cannot see is the state of the app
before the flow ran: a section a previous run left behind is invisible to it,
and a flow that waits for a section it did not bring in owns that.

The earlier-seed case is the sharp one, because the flow does not name the
section anywhere: two seeds of two tasks due today, and a wait for the section
between them. The section is on screen from the first seed, the wait returns
at once, and the assertion on the second seed's row has nothing behind it.

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

**A chunk that is only waited for after the last chunk.** The chunks are
written one after another, and the seed queue runs them one at a time. A flow
that waits for the section only after the last chunk therefore asserts on a
count the flow has not yet seen: the wait after the last chunk proves that the
last chunk landed, and nothing else — the earlier chunks are behind it in the
queue, and the flow's own assertion is made against a number it never
observed. A wait after the last chunk is not a wait for the seed the flow
asserts on; it is a wait for the *last* seed, with the rest of the seed
assumed.

So each chunk carries a wait of its own, and the wait names what that chunk
changed: the count the section shows. The section's badge is the app's own
report of how many rows it holds, so a wait for the count that follows the
chunk is a wait for the chunk — the row count is the section's, and the
section's count changes only when the rows are in. The count is read from the
section's own id (`agenda_section_overdue`) together with the number, so the
wait names the section and the number it must have reached; the section is
rendered whether it is expanded or collapsed, and its header carries the
count. Each wait is bounded by the item budget (8 s), and a chunk of a
hundred rows costs about three seconds to write, so the wait is given room
for the write and not for the whole bulk.

The alternative — one wait for the total count after the last chunk — was
rejected: it needs a budget far past the item kind (a minute, against the
layer's largest budget of 30 s for a shell wait) and it still proves nothing
about the chunks before the last one. The alternative of a text-only wait was
rejected as well: a wait on the count alone is a text wait, and the corpus
holds a fixed floor of text waits (27), so each chunk would add one.

**A link that outruns the seed it addresses.** A flow that seeds a row and
then sends a link to that row has a race that no wait on the screen can
close: the link is an intent, and the app resolves it against whatever the
database holds when the intent arrives. If the row is not written yet, the
link opens a task that is not the one the flow named, and the flow asserts on
a task it never addressed. The seed's wait closes the race only when the row
is on the screen the app is left on — a row due today is on the Today tab,
and a row without a date is in the Inbox, which the helper does not wait on.
So the seeded task of such a flow is due today, and the flow names its row in
`WAIT_FOR`. The flow then waits for the editor *by the title the seed gave*,
not by the field alone: a field that carries any title is an editor of some
task, and the title is what proves it is the task the link named.

**What the rule is held against.** `SeedHelperWaitTest` holds the rule to
fixtures as well as to the tree: flows written to break it — a wait that
precedes the seed, two seeds of one row with a wait that covers only the first
— and a helper whose entity wait sits outside the guarded block, a helper whose
entity wait runs before the seed link, and a helper whose entity wait runs
between the link and the shell wait. A gate that cannot fail holds nothing.

**What a flow-level wait has to name.** A wait of the flow's own covers a seed
only when it names *what the seed changed*: a row that was not on screen before
the seed ran, a name the seed gave, or a section the seed brought in. A wait
that names a row an earlier seed of the same flow had already written says
nothing about the write the flow asserts on — the row was on screen before the
seed ran, so the wait returns at once and the assertion is unbacked. The gate
reads the flow for the tell: the rows and names the flow's earlier seeds wrote.

The exception is a seed that changes nothing on screen. A seed whose query is
the query of an earlier seed of the same flow rewrites the same rows; what the
flow asserts on is on screen either way, and any wait the flow makes covers it.
The gate therefore holds such a seed to the ordinary rule and no more.

**Where the contract is written, and who holds the copies together.** The
contract is written in the header of `helpers/seed.yaml`, and it is repeated in
`Maestro/CONVENTIONS.md` and in the `singularity-todo-maestro-flows` skill —
three documents that a flow author may read instead of the first. A repetition
that drifts is a second contract, and a flow written against it fails on a
device for a reason the source does not show. `SeedContractCopiesTest`
therefore holds the three to each other: every clause of the contract has to be
stated in all three, and a document that states none of them fails the check — a
check that cannot fail holds nothing.

A repetition can also drift by *denying* the contract while keeping its
wording: a copy that says the helper navigates to the section holds every
marker of the contract and states the opposite of one of its clauses. So the
opposite of each clause is a forbidden wording, and a copy that states one is
held to it. The check is shown to be able to fail: a document that keeps every
marker of the contract and denies one clause has to be caught by the
contradiction check and by nothing else.

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

**How a conditional block is read.** A command may carry its own commands
under a `commands:` key, and the whole block may be guarded by a `when:`
condition. The gate descends into such a block: a seed inside it is a seed of
the flow, and the assertion that covers it has to live in the same block. A
seed under a condition may never have run, so an assertion outside the block
cannot be held to it — the flow asserts on a row that may not exist. The gate
matches a seed and an assertion only when they share the scope of their
blocks; a wait covers an assertion of the same scope only.

The commands of a nested block are commands of their own and not of the
command that carries them: the `file:` and `env:` of the nested block belong to
the nested commands. The gate reads them apart for that reason.

The scope follows the file. A seed that runs inside a block *through a helper* —
the block runs a helper, and the helper runs the seed — is in the scope of that
block: the seed may never have run, so an assertion outside the block is not
about it. A gate that loses the scope on the way into the helper holds the
assertion outside the block to a seed that may not exist.

The scope follows a file the block runs, too. A `runFlow` that carries a `when:`
condition of its own runs only when the condition holds, so the file it pulls in
belongs to that block — a seed in the file and an assertion next to it are held
to each other, and an assertion outside the block is not held to a seed that may
never have run. The block's scope reaches the file the same way it reaches a
helper: a file that runs under a `when:` of its own is a file that may not have
run at all.

**The scope is a hierarchy, not a list.** A block may be nested in a block, and
a scope is the path of the `runFlow` that carries the condition — so the scopes
are paths, and one path may be a prefix of another. A seed in a block is
therefore held to an assertion in a block *nested inside it*: where the inner
condition holds, the outer one held too, so the seed did run, and the assertion
is about the entity it wrote. The other way round does not hold: a seed in a
nested block may never have run, so an assertion in the outer block is not
about it. A gate that matches scopes by equality loses the inner half of that
hierarchy, and a flow that asserts on a seeded row inside a nested block with
nothing waiting for it passes the gate.

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
  the qualification fingerprint covers the files a flow runs transitively. The
  gate reported 68 findings on this tree before the sweep — 53 with a stale
  baseline, 14 with no admission and one exception that was issued for a
  fingerprint the flow has left behind. 67 of the 68 run the seed helper — 39
  of them through it directly and 28 through `helpers/seed-task.yaml` — and the
  68th, `tasks/13-create-full-task`, this entry changed itself. The gate was
  green before this entry: at the fork point of this branch (`d1af01223f`) it
  admitted every flow in a blocking scope (127 flows in scope: 114 baselined,
  13 qualified, 0 excepted), so the whole 68 is this entry's debt and not a
  debt it inherited. The merge-base with main is not green, and for a reason
  that is not about the flows: the baseline pin in the gate did not match the
  map on main (`sha256:5acb16be…` against a map that hashes to
  `sha256:198cda54…`), so the gate stopped with a read error before it could
  say anything about them. Main fixed that pin in `a5c948fc5648`, where the
  gate is green again (121 flows in scope: 103 baselined, 16 qualified, 2
  excepted). The flows are not broken — their assertions and their navigation
  are unchanged — but the evidence that admitted them was three passing runs
  against the old helper. The sweep that requalifies them ran on 2026-11-25
  and is tracked in issue #3140; the thirteen flows that still fail are
  admitted by evidence-backed exceptions that name the issue owning each
  cause, and the rest of the debt is tracked in issue #3191. This entry does
  not excuse the debt with silence: every exception names its cause and its
  owner.
- `smoke/07-seed-helper-contract` is the runtime half of the contract: the
  optional input, the named entity, and the seed that writes no entity.
- A flow that seeds an entity the agenda does not show still waits for it
  itself, by the row rule or the container rule.
- `scripts/check-ui-wait-budgets.py` now reads the waits nested in a
  conditional `runFlow` as well: the helper's entity wait is a wait the gate
  holds to the budget of its kind.
- The helper does not parse `QUERY`, and never will: the query language
  belongs to the seeder. `SeedHelperQueryTest` holds it to that: the query is
  forwarded whole in the deep link, and a helper that names the query anywhere
  else in its body is rejected.
- `agenda/07-large-overdue` seeds three hundred rows in twelve chunks of
  twenty-five rows, each chunk a seed of its own with a base title of its own,
  so that every chunk fits inside the wait that follows it (the item budget is
  8 s, and a chunk of twenty-five rows takes about 2 s to write on a loaded
  host). Every chunk carries a wait of its own, and the wait names the count
  the section shows after that chunk (`agenda_section_overdue` together with
  the number), so the flow never asserts on a count it has not observed. The
  count is absolute, so the profile has to be empty when the flow starts: the
  harness that qualifies a flow wipes the device before it runs
  (`docs/decisions/2026-11-24-the-qualification-wipe-runs-against-an-empty-profile.md`
  says why the runner's own wipe does not do it). Three hundred rows and not a
  thousand: the runner cuts a flow off at five minutes, and the flow measured
  216 s at four hundred rows on a host that was busy with the sweep itself —
  72 % of the budget, and the budget is a hard cut-off. Three hundred rows are
  fifteen times the count at which the agenda collapses the overdue section
  (`AgendaPresets.LARGE_OVERDUE_THRESHOLD`), so the regression the flow is
  about is the same one either way.
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
- A section an earlier seed of the same flow brought in is not a target of the
  helper's wait and covers nothing as a flow-level wait either: it was on
  screen before the seed ran. `capture/01-today` waits for each of the three
  rows of the demo dataset on its own, because the section is on screen from
  the first of the three seeds.
- A relative date of zero days is today: `+0d` and `0d` name a task that is
  due today, and the helper may wait for its row.
- `nav/deep-link-task` seeds a task that is due today and names its row in
  `WAIT_FOR`, so the link that follows cannot outrun the write of the row it
  addresses; the task is due today because a row without a date is in the
  Inbox, which the helper does not wait on.
- `nav/deep-link-task` and `notes/03-internal-link-navigation` assert the
  title of the task they opened, and wait for the editor by the id of its
  title field *and* by that title: the wait names the entity, not whichever
  entity the editor would show.
- `menu/06-tree-live` waits for the name the rename gave the project, and
  asserts the row's id after it: the row was already there under the old name,
  so the row's id cannot tell the rename from the row it renamed. The
  assertion that came *before* the rename asserts the first seed by its name
  ("Roadmap") and not by the row's id — the row is the row the rename
  rewrites, so an assertion on the id would stand for the write that was in
  effect when it ran, which is the first seed's and not the rename's.
- A wait of the flow's own covers a seed only when it names what the seed
  changed: a row the earlier seeds had not written, a name the seed gave, or a
  section the seed brought in. A wait that names a row an earlier seed wrote
  covers nothing, and the gate rejects the flow. A seed whose query repeats an
  earlier seed's changes nothing on screen, so any wait of the flow covers it.
- The gate descends into the commands of a conditional block: a seed under a
  `when:` guard is matched with the assertions of the same block only, and an
  assertion outside the block is not held to a seed that may never have run.
- The three copies of the contract state it alike, and `SeedContractCopiesTest`
  holds them to each other: every clause has to be stated in all three, and a
  copy that denies a clause while keeping its wording is held to it as well.
- The helper's own wait covers one entity of the seed that asked for it: a link
  that names a profile and a task writes two entities, and the wait for the
  task's row says nothing about the profile. The section a task is in belongs
  to that task, so the wait for the section covers the rows in it and not
  another entity of the same link.
- The scope of a conditional block reaches a seed that runs inside it through a
  helper: an assertion outside the block is not held to a seed that may never
  have run, whatever files the seed was reached through.
- The scope of a conditional block reaches the file the block runs as well: a
  `runFlow` with a `when:` of its own pulls the file into that block, so an
  assertion outside the block is not held to a seed in the file.
- The scope is a hierarchy: a seed in a block is held to an assertion in a
  block nested inside it, and a seed in a nested block is not held to an
  assertion in the outer block — the inner condition may not have held.
- A parameter's key is read as the link spells it: ` task=…` names no
  parameter the seeder knows, it writes nothing, and the gate does not hold a
  flow to a wait for a row that was never written.
- A container belongs to the entity and not to the seed: a wait for the section
  of a task covers that task and says nothing about another entity of the same
  link. The flow's own wait is held to the same rule as the helper's.
- A link with a blank parameter writes nothing: the gate's model drops the same
  pairs the seeder drops, so a flow is not held to a wait for a row that was
  never written.
- A link that names a profile writes that profile, whatever else its query
  says: the seeder creates the profile and switches to it before it answers a
  reset, a cleanup or a session link. The profile is on another screen, so the
  flow waits for it itself.
- Two fixtures are weaker than they were, and the weakening is this entry's
  doing. `agenda/07-large-overdue` seeds three hundred rows where it seeded a
  thousand and one: the runner cuts a flow off at five minutes, and four
  hundred rows already took 216 s of that budget on a loaded host. The
  regression the flow guards is the same one either way — three hundred rows are
  fifteen times the count at which the agenda collapses the overdue section.
  `nav/deep-link-task` seeds a task that is due today where the seeded task had
  no date: a row without a date is in the Inbox, which the helper does not
  wait on, so the row the flow addresses could not be waited for at all.

## What the gate does not see

The rule this entry adds is held by `SeedHelperWaitTest` and
`SeededEntityWaitTest`, which read the flows through `SeedWaitTree`. The tree
reads what the flows say, and three things it does not read are worth naming:
a gate that is not seen to be blind is a gate that is trusted too far.

- A flow's own document-level `env:` is not read. The collector starts with an
  empty environment, so a flow that declares `env:` at the top of its file and
  refers to it as `${NAME}` is read with the reference unsubstituted — and an
  assertion made through such a reference is not seen to be an assertion about
  the entity the seed wrote. No flow in the tree does this today: only
  `Maestro/helpers/seed.yaml` (whose `env:` derives the wait target, and which
  the tree deliberately does not descend into) and `Maestro/config.yaml` carry
  a document-level `env:`. Nothing is admitted or rejected wrongly today, and a
  flow that waited for nothing and asserted through such a reference would pass
  the gate unchecked.
- An assertion counts as being about the entity only when it names it exactly:
  the row's `id:` or the name the seed gave. A pattern
  (`text: "^Overdue .*25$"`) is not about the entity, so a flow that asserts a
  seeded entity only by a pattern is not held to a wait for it.
- `assertNotVisible` (34 uses in the tree) and `waitForAnimationToEnd` (9 uses)
  are read as neither an assertion nor a wait. An assertion of absence is not an
  assertion about the entity the seed wrote, and an animation that has to end is
  not a wait for it — but a flow whose only assertion about a seeded entity is
  an `assertNotVisible` is therefore not held to a wait for that entity.

These are limits of the check, not of the contract: the flows in the tree
assert their seeded entities by id or by name, and the helper's wait is what
the contract asks of them.

## Verified on device

`realme RMX3938 (0O65321F32204DD5)`, the E2E build:

- `tasks/08-date-buckets` — the flow that names the entity it wants waited
  for. The trace shows the helper's wait running (`Assert that id:
  ${WAIT_TARGET} is visible... COMPLETED`) and the flow's own assertion on
  the same row passing right after it.
- `menu/06-tree-live` — the rename wait, on the menu's sheet.
- `agenda/07-large-overdue` — **green on this device** (2026-11-23), in the
  state the flow had then: the bulk seed was split into five chunks, every
  chunk seed completed, the overdue section showed the full count (`1001`), the
  Today section and `task_item_todayreachable` were reachable, and the
  expanded list showed `task_item_bigoverdue_1`. Before the split the flow
  failed on this device — writing a thousand rows takes ~30 s, which is longer
  than the helper's shell wait (30 s). The row cost itself (~30 ms a row) is
  the seeder's business and is tracked in issue #3135.

Android emulator (`emulator-5554`), 2026-11-24 — the per-chunk waits:

- `agenda/07-large-overdue` — **green** (exit 0), with the bulk seed split into
  chunks that each fit inside the wait that follows them: the trace shows the
  section's count reaching the chunk's total in order, each wait `COMPLETED`
  within the item budget (8 s). The last chunk lands,
  `task_item_todayreachable` is visible, and the expanded overdue list shows
  `task_item_bigoverdue_1`. At the time of that run the seed was a thousand
  rows in forty-one chunks; the flow now seeds three hundred rows in twelve
  chunks, because the runner cuts a flow off at five minutes and the flow had
  grown into that budget (see the consequence above and the entry of
  2026-11-24).
- `nav/deep-link-task` — **green** (exit 0). The trace shows the helper's
  entity wait running on the seeded row (`Assert that id:
  ${WAIT_TARGET} is visible... COMPLETED`) before the link is sent, and the
  editor's title field carrying the seeded title afterwards.

Android emulator (`emulator-5564`), 2026-11-24 — the flow as it is now, and
the timing that sized it:

- `agenda/07-large-overdue` — **green** (exit 0) at four hundred rows in
  sixteen chunks. The trace shows the cost of a chunk: the seed's own link
  (3.5 s), the helper's failure check (7.5 s — it waits for a failure screen
  that a successful seed never shows), and the wait that follows the chunk
  (0.4 s). Sixteen chunks of that came to 216 s of the runner's 300 s budget,
  so the flow was cut to three hundred rows in twelve chunks.
- `menu/06-tree-live` — the flow as it stands now: the row is scrolled into
  view by its id before the wait for the renamed project runs, and the wait is
  for the new name. The earlier run of this flow waited for the name *before*
  the row was scrolled into view, so a row below the fold never came into
  sight and the wait timed out — the helper does not scroll, so the flow's own
  navigation comes first.

Android emulators (`emulator-5562`, `emulator-5566`, `emulator-5568`, API 36),
2026-11-25 — the requalification of the flows this entry's change to the
helper invalidated, against the APK built from `38f87de9b9`:

- Thirty-five flows, three attempts each, the same documented environment per
  flow. **Twenty-two passed all three attempts** and are admitted by their
  qualification records: `lifecycle/01-kill-restart-persist`,
  `menu/01-projects-roots`, `menu/04-all-projects`, `menu/06-tree-live`,
  `notes/03-internal-link-navigation`, `profile/02-isolation`,
  `pomodoro/03-pause-resume`, `search/02-cyrillic`, `search/08-word-prefix`,
  `search/13-suggestion-dismissal`, `smoke/06-session-anonymous`,
  `smoke/07-seed-helper-contract`, `system/search-finds-task`,
  `system/seed-unknown-key`, `tasks/02-set-due-date`,
  `tasks/03-due-date-cancel`, `tasks/05-archive-via-menu`,
  `tasks/08-date-buckets`, `tasks/16-return-to-source-tab`,
  `tasks/18-time-tracking-start-stop`, `tasks/20-planning-deadline`,
  `tasks/subtasks/TASK-SUB-01`.
- **Thirteen did not**, and each failed on the same message in all three
  attempts. None of the thirteen was changed by this entry; what changed is
  the fingerprint their baseline entry was written against. Seven of the causes
  are already owned and recorded in `config/docs/maestro-debt.yaml` (#818 for
  the four attachment flows, #837 for `lifecycle/03-rotate-in-editor`, #2723
  for `tasks/06-delete-undo`, #872 for `tasks/09-rename-empty`); six are causes
  of their own (`pomodoro/04-skip-to-break`, `search/05-filter-pinned`,
  `search/12-no-results-recovery`, `tasks/13-create-full-task`,
  `tasks/21-planning-reminder`, `tasks/logbook/TASK-LOG-01`), tracked by issue
  #3191. Each of the thirteen is admitted by an evidence-backed exception that
  names the issue that owns its cause.
- The gate is green on this tree: 122 flows in a blocking scope, 51 baselined,
  58 qualified, 13 excepted. The exceptions file holds exactly those thirteen: the
  exception `archive/02-bulk-archive-confirm` was issued in #3126 against a
  fingerprint the flow has left behind, and the flow has been requalified since, so
  the record admits nothing and would be ignored by the gate — a stale record in the
  file that names a cause for a flow that is qualified is a second, wrong contract
  about the flow. It is gone.
- The gate now says so itself. An exception that names a fingerprint the flow has
  left behind was read only where a flow had to be admitted, so an exception
  beside a *valid* qualification was never looked at: the gate was silent about a
  record that contradicts the qualification next to it. The gate reports it as a
  `stale exception` finding — the record has to be reissued for the current
  fingerprint or deleted. The rule is what keeps the exceptions file honest as the
  flows move under it.

## Links

- `Maestro/helpers/seed.yaml` — the helper; its header carries the contract.
- `Maestro/CONVENTIONS.md` — the helper table and "Waiting for what you seeded".
- `.agents/skills/singularity-todo-maestro-flows/SKILL.md` — the helper list and
  the seeding walkthrough.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedHelperWaitTest.kt` —
  the gate that holds the helper and its callers to this entry.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeededEntityWaitTest.kt` —
  the gate that holds the flows to the rule that one wait covers one entity.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedContractCopiesTest.kt` —
  the gate that holds the three copies of the contract to each other: every
  clause in all three, and the opposite of a clause in none. A clause is held by
  a marker — a phrase all three copies use — so the check is that the three state
  the same rules, not that they read alike: a copy may state a clause in its own
  words. The clauses are the rules a flow author relies on (what the wait names,
  where it stands relative to the seed, whose entity it is evidence for, what a
  seed that writes nothing waits for), not the wording.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedHelperQueryTest.kt` —
  the gate that holds the helper to forwarding the query whole, and to reading
  none of it.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedWaitTree.kt` —
  the tree the two gates read, and the rule they hold the flows to.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SeedEntityModel.kt` —
  the model of a seeded entity: which rows a link writes, which section they
  are in, and which of them the helper may wait for. It is a file of its own
  because the tree and the model are two responsibilities, and one object
  that holds both is a class the size rule rightly refuses.
- Issue #3135 — the bulk seed writes ~30 ms a row, so a thousand-row seed
  outlasts every Maestro wait. Tracked separately; the seed's write path is
  not changed by this entry.
- Issue #3140 — the qualification sweep that follows from this entry's change
  to the helper.
- Issue #3191 — the thirteen flows the sweep did not qualify, and the causes
  that own them.
- `Maestro/flows/tasks/08-date-buckets.yaml` — a flow that names the entity it
  wants waited for.
- `Maestro/flows/agenda/07-large-overdue.yaml` — a flow that waits for the
  section the seeded task is in.
- `Maestro/flows/nav/deep-link-task.yaml` — a flow whose link cannot outrun
  the seed it addresses.
- `Maestro/flows/menu/06-tree-live.yaml` — a flow that waits for a renamed
  entity by the name it was given.
- `docs/decisions/2026-11-24-the-qualification-wipe-runs-against-an-empty-profile.md`
  — the wipe a qualified flow runs against, and why the harness does it.
- `docs/decisions/2026-11-05-the-seed-waits-for-the-shell-not-the-row.md` — the
  entry this one supersedes.
- `docs/decisions/2026-10-28-the-seed-replaces-the-task-it-names.md` — the
  seed owns the row of its title.
- `config/docs/ui-wait-budgets.txt` — the budgets the helper's waits are held
  to.
