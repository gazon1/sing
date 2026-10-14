---
title: "Reminder Tile Is Only Reachable From Its Own Preview"
date: 2000-01-01
status: open
tags: ["deferred"]
---

**Status: RESOLVED (2026-10-14)**

**Decision:** Delete — no reminders list screen exists, and no caller was found
after scanning the entire `feature/reminders/` directory. `ReminderTile.kt` was
removed. A component with no caller and no plan is a trap for the next reader,
not an asset.

**Found in:** 2026-10-07, while widening `find-unwired-surfaces.py` detector 1 to
treat a `@Preview` call as not-a-call-site.

`ReminderTile` is a finished, styled reminder row: it takes a `Reminder`, a delete
handler and a modifier, and it renders correctly. It also has two previews, one light
and one dark, which is why it survived every earlier audit — it looks exercised.

Nothing outside `ReminderTile.kt` names it. The reminders screen never showed it, so
the screen has been rendering some other row for the whole life of the feature. The
audit that found the four attachments breakages reached the same conclusion about
attachments and missed this one: "the component is fully implemented" was read as
"the component is in use".

This entry exists because the detector now reports it and the honest responses were
either to wire it into the reminders screen (a product decision about which row the
screen should show, plus the visual check that needs a running emulator) or to record
the gap. Recording it is the one that does not lie about the state of the feature.

**Why the gate was green until now.** Detector 1 asked "does any file mention this
name?", and the component's own previews answered yes. `_without_previews()` in
`scripts/find-unwired-surfaces.py` now blanks preview bodies before the count, which
is what turns this from invisible into a finding. Note the deliberate non-signal:
co-location is not preview-ness — `TagCard` is called by `TagList` in the same file
and is correctly *not* reported.

**Try first:** open `feature/reminders/` and decide whether `ReminderTile` is the row
the screen should render. If yes, replace the current row with it and delete this
entry. If no, delete `ReminderTile.kt` and its previews outright — a component with no
caller and no plan is not an asset, it is a trap for the next reader.

---
