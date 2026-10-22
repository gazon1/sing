# The desktop task stack starts elsewhere

Status: **deferred**
Tags: **navigation nav3 desktop parity**

Related: ADR `2026-10-02-nav-entries-dedup-deferred` (addendum 2026-10-21),
`openspec/specs/nav3-desktop-jvm-entry-dispatch`

## What

The tasks nested graph opens with a different screen on Desktop than on Android, in two
ways that the user can see.

**The date is lost.** The floating action button on the Today tab opens the new-task
screen with today's date already filled in. On Android it does. On Desktop the
new-task screen opens with no date at all — the user has to pick the date the tab was
already showing them.

**There is an extra screen underneath.** Opening a task's detail screen from the agenda
works on both platforms. On Android, pressing back leaves the graph and returns to the
agenda. On Desktop, pressing back shows the new-task screen — a screen the user never
asked for — before it will leave the graph.

Both come from one line. The Desktop entry seeds its stack with a hard-coded
"new task" screen and then, only if the requested screen is a task detail, pushes the
real one on top. The Android entry seeds its stack with the screen that was actually
requested.

There is a third symptom, of the same family: **the section is lost.** Creating a task
from a saved agenda view pre-selects that view's section. On Desktop the pre-selection
never arrives.

## The premise the decision rests on is false

The decision to seed with a fixed screen was made for a good reason and is written
down — ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch`. The renderer dispatches on
the top of the stack, not on the start parameter, so a stack seeded with one screen
would render that screen even when a different one was asked for. The fix was to push
the asked-for screen onto the stack when the two differ.

That ADR states the case it did not have to handle: "for `Create`, the pattern works
because `start` and `stack.seed` are both `Create(null)`". They are the same *kind* of
screen, but they are not the same screen. The start route carries the date to pre-fill
and the section to pre-select; the seed carries neither. The premise was true when the
decision was made and stopped being true when the start route grew parameters.

The gate that enforces the decision has the same blind spot: it checks that a stack
seeded with the new-task screen and started at a task detail pushes the detail route
before rendering. It says nothing about a stack seeded with the new-task screen and
started at the new-task screen — with a date.

So this is not a case of an unenforced rule drifting. It is a rule whose enforcement
covers one of the two ways it can be broken.

## Why it is still here

The graph deduplication deliberately left the tasks entry platform-specific, because
its two versions were genuinely different — the Desktop one seeds the stack itself and
pushes the detail route onto it. That was recorded as a deliberate deferral, and the
difference it creates was recorded as "a real difference".

It is not a real difference. It is the same contract implemented twice, and the two
implementations disagree about what the contract is. The deduplication work measured
one platform-specific difference and found it to be a bug; this one was left alone
because it was visible, documented, and had a spec written around it.

The visible symptom of that is that the spec for the Desktop entry dispatch describes
the Desktop behaviour and calls the two platforms identical. It says the seed is the
new-task screen — which is true on Desktop and false on Android — and it never says
what the Android seed is, because that part was never written down.

## What is not in this change

- **No change to the graph itself.** The graph is already one implementation, in common
  code. This is about the stack the entry hands it.
- **No change to the platform-specific entry.** It can stay where it is; what it
  passes changes.
- **No change to how a task is opened from the agenda.** The route the agenda asks for
  is already correct; it is what the entry does with that route that differs.

## What has to be decided before implementing

- **Does the entry seed the stack with the requested route, or does the requested route
  replace the seed?** Seeding with the requested route is what Android does and it
  removes every symptom at once. Pushing it onto a placeholder seed is what Desktop
  does and it is the reason two of them exist.
- **What happens to the Desktop's "the graph survives a tab switch" behaviour?** The
  Desktop entry keeps its stack alive across a tab switch by holding it in the shell.
  Whatever replaces the seed must not break that.
- **Does the gate grow a second case?** The rule that enforces the current decision
  covers a stack seeded with one screen and started at another. It needs to also cover a
  stack seeded with one screen and started at the *same* screen with different
  parameters — otherwise the fix can be undone by the next route that grows a field.
- **The ADR needs an addendum.** `2026-10-03-nav3-backstack-top-vs-start-dispatch`
  states a premise that is no longer true; the addendum records that and points at the
  decision that replaces it.
