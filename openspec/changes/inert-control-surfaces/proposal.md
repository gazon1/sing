# inert-control-surfaces

## What

Close the holes in the gates that look for inert surfaces, and resolve what they found.

## Why

The gate that looks for empty click handlers was keyed on a list of ten parameter names.
That list is a stale snapshot of the same wrong idea: a handler called anything else is
invisible to it. The rule now keys on the *shape* — `on` followed by a capital letter —
and both exemptions are stated in the source and pinned by near-miss tests so they cannot
quietly grow into a new allow-list.

Two detector defects were found and fixed along the way:

- The detector freed a preview function body only at the end of the function, *after* the
  name check, so a `*Preview` function without an `@Preview` annotation was counted as
  having no callers at all.
- "No callers outside this file" was treated as "no callers". `TagCard` is called by
  `TagList` in the same file and was reported as unwired.

And two things the gates cannot see:

- `find-unwired-surfaces.py` does not cover `SyncViewModel`, a bound-but-never-injected
  ViewModel, or `SyncButton`, whose name ends in neither `Tile`, `Row` nor `Dialog`. Both
  were unreachable and both are now reachable — see `attachment-sync-setting`.
- `KoinGraphValidationTest` asks "does this resolve?", not "is this defined twice?". The
  appearance contributor was bound in two modules and the second silently replaced the
  first.

## How

- `NoEmptyOnClickLambdaPolicy.isHandlerParameter` — `on` + capital.
- Two justified exemptions: `Result.fold` branch labels, and an empty `onValueChange`
  beside a `readOnly = true` in the same call.
- `find-unwired-surfaces.py` — preview bodies blanked before counting; `Tile`, `Row`,
  `Dialog` suffixes; co-located callers counted.
- Positive and negative controls for every exemption and every detector fix.
- The 18 findings resolved individually. Where the action was impossible, the handler
  became nullable and the UI stopped rendering the control; where the feature was real but
  unreachable, it was wired.
- Baseline entries removed rather than regenerated, for `PomodoroScreen` and
  `BackupScreen`.

## Known limitations

- `find-unwired-surfaces.py` still cannot see a bound-but-never-injected ViewModel.
  Adding `Button` to the composable suffixes needs a sweep first; that is follow-up work.
- `ReminderTile` is finished and unreachable. Which screen should carry it is a product
  decision, so it is baselined with a live backlog reference rather than deleted to make a
  gate green.

## References

- `docs/decisions/2026-10-07-the-empty-handler-gate-keyed-on-a-list-of-names.md`
- `docs/decisions/deferred-backlog.md` — `reminder-tile-is-only-reachable-from-its-own-preview`