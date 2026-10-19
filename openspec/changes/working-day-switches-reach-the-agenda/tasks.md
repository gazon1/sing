# Tasks — working-day-switches-reach-the-agenda

- [x] Add `AgendaPresets.forToday`: a pure decision of whether the work-day
      preset applies, from the day of week and the two working-day switches.
- [x] Wire the Today route to it: collect `weekendSat` / `weekendSun`, and
      recompute the agenda from the time-of-day bounds whenever any of the six
      inputs changes — one effect, one source of truth, so a switch flipped
      back restores the work-day preset.
- [x] Move the Today branch into its own composable, because it no longer fits
      detekt's method-length limit.
- [x] Cover the decision with tests: weekday, Saturday and Sunday, each switch,
      and each switch not deciding the other day.
- [x] Record the decision: ADR
      `2026-10-19-a-setting-that-is-rendered-is-a-claim-the-gate-must-check.md`.

## Not in this change

The gate's own half of the same finding — the exclusion by domain directory,
the two controls that could not fail, the sync push phase's data-loss bug, the
detekt findings, and the `openspec validate` failure on
`offline-local-session` — is in `fix/three-red-gates` (PR #741).
