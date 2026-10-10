---
title: "Six Smoke Flows Still Red After The Harness Fix"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** [#92](https://github.com/gazon1/sing/issues/92)

**Found in:** 2026-10-04, the second full `smoke` run on the fixed harness.

**Result: 13 passed, 6 failed.** The three runs that day went 9/10 → 8/11 → 13/6,
and the middle dip is the interesting one: it is what the per-flow relaunch cost
before it waited for the activity instead of a duration (see
`2026-10-04-maestro-flow-isolation.md`).

Fixed and now green: `01-restore`, `02-isolation`, `03-cycle-tabs`,
`02-menu-settings`, `menu-sheet`, `04-delete`, `07-cyrillic-title` — seven of the
eleven the first run lost.

**Still red, one line each, with no diagnosis yet:**

| Flow | Known last reason |
|---|---|
| `01-round-trip` | unknown — the `clearState` command is gone and the flow no longer matches nothing |
| `search-finds-task` | `nav_tab_today is visible` after the `todo-debug://seed` deep link |
| `05-archive-via-menu` | unknown |
| `06-delete-undo` | unknown — now selects `snackbar_action`, so the locale bug is out of the picture |
| `08-date-buckets` | unknown — three `openLink` seeds in a row |
| `09-rename-empty` | unknown — `longPressOn` is fixed, the parse error is gone |

**What is honest about this entry:** five of the six are undiagnosed. The
per-flow logs for them were rotated out of `~/.maestro/tests` before the run's
tail was read, so the table above records what is known and no more. Re-running
one flow at a time with `FLOW=… bash scripts/run-maestro.sh` is ~2 minutes each
and yields the answer immediately; doing all six that way is the obvious first
move, and it is not done yet.

**The pattern worth watching:** the flows that still fail are the ones that
reach their state through a `todo-debug://seed` deep link rather than through
`launch-clean`. Four of the six do. The deep link depends on a live, initialised
process, and per-flow isolation forces exactly one more restart in front of it —
so the fix is likely one more wait (after the `openLink`, before the first
assertion) rather than six separate bugs. That is a hypothesis, not a
conclusion, and it is cheap to test: add the wait, re-run, see which of the six
move.

**Attempted 2026-10-05, not completed — the device would not boot.**
`scripts/ensure-emulator.sh` started `Medium_Phone`, but `adb devices` went
`emulator-5554 offline` and then dropped the device from the list entirely while
the emulator process was still alive; 12 polling attempts over ~4 minutes never
reached `device`. No flow was run, so **no new diagnosis was produced and the
table above is unchanged**. This is the host-side gfxstream instability recorded
in `2026-09-28-emulator-gfxstream-colorbuffer-segv.md` and in `AGENTS.md` ("Maestro
currently fails in this environment"), showing up as a device that never
registers. Re-run the six one at a time on a host where the emulator boots; the
per-flow `FLOW=…` recipe in the paragraph above is still the right first move.

Related: a debug APK now carries its git sha in `versionName`
(`0.1.0+g<sha>`, see `androidApp/build.gradle.kts`) and `run-maestro.sh`
refuses to run when the installed binary's sha disagrees with the checkout.
That check is why the next run cannot silently report a result for a
snapshot-restored APK.
---
