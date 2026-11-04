---
title: "The seed link has one cleanup: the profile wipe, not a sweep by title prefix"
date: 2026-11-05
status: accepted
tags: [e2e, maestro, debug, deep-link, cleanup]
---

## Context

`DebugSeedActivity` answered two delete-instead-of-seed parameters:

- `reset=content` — wipes every row of the active profile's content, keeping
  the profile and the session.
- `cleanup=<prefix>[,…]` — deletes every task, note and project whose
  title/name starts with one of the given prefixes.

The runner used to send the second one, with a hand-maintained list of titles,
until `2026-10-28-the-bridge-policy-is-a-file.md` deleted the narrow cleanup
scope. `--maestro-cleanup` now offers `all` (a content wipe before every flow)
and `off`; the narrow scope is gone from the CLI, the implementation and the
tests, and the runner's only cleanup is the profile wipe.

What that decision deliberately left behind is the app-side parameter. It had
no caller in the tree — no Maestro flow, script or test sent `cleanup=`, and
`scripts/tests/test_maestro_cleanup_scope.py` asserts the runner does not — but
it survived as a second way to delete rows. Its KDoc called it a "mid-run tool",
and a comment in `seed()` still claimed *"the runner sweeps a successful run's
fixtures through the `cleanup=` link"*: the last place in the code that said the
runner sweeps by title prefix, and untrue since the narrow scope was retired.
Nothing in the documentation explained the second mechanism's scope, and a
reader could reasonably conclude the runner still used it.

## Idea

Two options, both permitted by the issue:

1. **Keep it as a deliberately named manual tool.** Rename it, state that it
   deletes the rows whose title starts with the prefix, and warn that the runner
   does not use it.
2. **Remove it.** The seed link answers only `reset=content`, and the repository
   carries one cleanup concept: the wipe.

The second was chosen.

## Decision

**The seed deep link no longer answers a `cleanup=` parameter.** The parameter,
its dispatch branch and the `cleanup()` helper are deleted. The only
delete-instead-of-seed parameter is `reset=content`, which the runner sends
before every flow.

A `cleanup=` key arriving from an old caller now falls through to seeding and
performs no delete: unknown keys were already ignored by the entity branches.

## Rationale

- **A boundary that must be maintained by hand fails silently.** A title-prefix
  list knows only the titles someone remembered to put in it; every title it
  forgets is a row that survives into the next flow and fails an assertion
  three steps later, in a different flow. That is exactly the defect the narrow
  runner scope was retired for, and keeping the same boundary in the app would
  keep the defect reachable.
- **The manual case is already covered.** A debugging session that wants a clean
  slate sends `reset=content`; it wipes the active profile's content and leaves
  the profile and session alone. A narrower "delete just these fixtures" tool is
  a convenience that reintroduces the list.
- **One mechanism is the one that can be documented.** `reset=content` has a
  stated inventory (`docs/architecture/wipe-inventory.md`, checked against the
  Room schema) and a stated concurrency caveat. The prefix sweep had neither.
- **No caller loses anything.** Nothing in the tree sent `cleanup=`, so removal
  changes no flow and no script.

## Consequences

- `todo-debug://seed?cleanup=…` is a no-op. A manual caller that relied on it
  must send `reset=content` instead, which wipes the whole active profile.
- `DebugSeedActivity` no longer imports `kotlinx.coroutines.flow.first`; the
  deleted helper was its only user.
- `2026-10-20-the-maestro-cleanup-never-matched.md` remains the record of the
  night the old sweep was measured, and `2026-10-28-the-bridge-policy-is-a-file.md`
  remains the record of the runner-side retirement. Both describe the sweep in
  the past tense; this entry completes the retirement on the app side.

## Links

- `2026-10-20-the-maestro-cleanup-never-matched.md` — why a title-prefix sweep
  was the wrong boundary in the first place.
- `2026-10-28-the-bridge-policy-is-a-file.md` — the runner-side retirement and
  the content wipe that replaced it.
- `2026-10-21-each-flow-gets-a-clean-device.md` — the wide cleanup scope.
