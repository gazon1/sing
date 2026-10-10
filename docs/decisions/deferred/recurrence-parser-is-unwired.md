---
title: "Recurrence Parser Is Unwired"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability audit, via `find-unwired-surfaces.py`
detector 7 (dead-symbol). The baseline line carried a backlog reference to this
entry that did not exist, so the reference was unresolvable.

**Status: CLOSED (deleted)**

**Symptom:** `RecurrenceParser.kt` is 309 lines with 27 `@see` KDoc references
and zero production call sites. It is the inverse of an unwired forward
operation — the parsing direction is implemented, the *applying* direction
(`TaskRepository` → recurrence expansion) is not, so nothing ever asks the
parser for a recurrence.

**Already ruled out:** not reachable by reflection, DI or route — plain Kotlin,
no Koin binding, no `interface` implementor.

**Try next:** decide whether recurrence is a product feature. If yes, the missing
half is the apply path (an infinite `Task` generator consumed by the agenda or
calendar), and the parser is a reasonable starting point. If no, the 309 lines
are a candidate for deletion. As with `core-auth-oauth-is-entirely-unwired`, a
dead-code sweep should not make the product decision either way.

---
