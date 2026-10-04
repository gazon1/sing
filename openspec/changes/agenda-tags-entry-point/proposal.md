# agenda-tags-entry-point

## What

Add a first-class way to open an agenda built from a set of tags, without first
constructing a saved view by hand.

## Why

The section configurator can already build a multi-tag section, including
"match all of these tags" as of 2026-10-04. Nothing in the application ever
*starts* one. The only route to such an agenda is: open a saved-view editor, add
a section, pick a tag template, select the tags, name the view, save it, open it.

That is a five-step path to a thing a user wants in one step — and the tags are
already on screen when they want it, usually filtered from a search.

The engine was never the gap. This is a product surface that was deferred when
the configurator landed, and the deferred half is the half users touch.

## Scope

### In scope

- An entry point reachable from an existing screen that already has a set of
  tags in hand.
- The resulting agenda, whether transient or persisted — see design.md, this is
  the open decision.
- The single-tag case, which must not take a different route than the multi-tag
  case.

### Out of scope

- The "match all of these tags" toggle, which shipped on 2026-10-04.
- Any change to how the configurator builds or resolves a section.
- Saved-view management: renaming, duplicating, copying to another profile.
- Search-result-to-agenda, which is a superset of this and is called out in
  design.md as a deliberate non-goal here.

## Why a spec and not a change

This introduces a user-visible route to a capability that already exists. The
observable behavior worth specifying is not "a selector resolves" — that is
already covered — but "the user can get there, and what they get when they
arrive".

## References

- `docs/decisions/deferred-backlog.md#agenda-reachability-byTags-no-ui-entry`
- `docs/decisions/2026-10-04-multi-select-sheet.md` (the multi-select primitive
  this reuses)
- Issue #81

## Status

**Proposed.** The transient-versus-persisted decision is unresolved.
