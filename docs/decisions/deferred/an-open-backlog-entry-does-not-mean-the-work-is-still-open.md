---
title: "An Open Backlog Entry Does Not Mean The Work Is Still Open"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** [#88](https://github.com/gazon1/sing/issues/88)

**Found in:** 2026-10-04, the first iteration of the "what next" sweep — while
asking which recorded findings were still true, instead of which were still
*written down*.

Nine entries carried `Status: OPEN` and described work that had shipped. Not one
had been closed: `is-saving-clobber` (the guard existed, nothing pinned it),
`section-prefill-dynamic-date` (solved by a `relativeDueDate` field, a different
fix from the two either-or options the entry offered), `agenda-section-add-button-noop`,
`agenda-editor-no-selector-parameter-configuration`, `agenda-views-not-in-backup`
(agenda_views only — eight tables really are still missing), `notification-text-null-invisible`,
`docs-rot-agenda-selector-count`, `kover-full-jvmtest-run-unmeasured` (measured,
entry left open), and `vm-without-unit-tests`, which was a **second entry for
the same finding** that `vm-without-test` already tracked.

Two of them were actively misleading rather than merely stale. The kover entry's
"do this first" was a measurement nobody had run, and the flag it defended was a
workaround for a workaround. The prefill entry offered two fixes, both wrong, and
would have led the next person into a `Clock`-injection refactor of an `object`
that never needed one.

**Why this is the expensive failure mode:** an open entry reads as a live
commitment, so the cost is not the stale text. It is that a backlog nobody
trusts stops being read at all — and the findings were real when they were
written. Nine of them were.

**What catches it, and what does not.** Nothing in the toolchain did, because
every gate here answers a different question: does this compile, does the
feature have a test, is the tag in the registry. None of them asks *is this
finding still true*. A status line is only as current as the last person who
remembered to look.

**Do this first, next time:** sweep the backlog as part of the retro-gate, not
as a separate task later — the retro is the only moment when the session that
made the change still knows what it changed. A status line written during the
change costs nothing; the same line reconstructed a week later is archaeology.

---
