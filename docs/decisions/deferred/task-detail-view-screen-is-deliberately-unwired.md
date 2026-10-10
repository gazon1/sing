---
title: "Task Detail View Screen Is Deliberately Unwired"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** the auth-and-sync plan, when `find-unwired-surfaces.py` reported
`TaskDetailViewScreen()` as having no call site. It is the last such finding on
`origin/main`, and it has been there through three separate commits.

**Status: OPEN — the owner decided on 2026-10-07 to keep the screen and record it,
rather than delete it. Issue #216 closed (kept unwired).**

**Tracking:** #216 (closed — kept unwired).

The screen has zero production call sites. That is not an accident and not an oversight,
and the two commits that made it so each recorded why:

- `dad11e6b` — "one task detail screen, because a merge had quietly made two". A merge
  had left the project with two detail screens, and the decision was to keep one. That
  commit also recorded the reason this file exists: **deleting another branch's
  deliberate carrier is the owner's decision**, and it declined to make it.
- `f8e38643` — "the three screens read the wall clock, and one of them is not wired".
  This added the screen back as what that commit calls a *clock-suppression carrier*,
  and said in its own subject line that it is not wired.

So the screen is kept because deleting it may remove behaviour that exists nowhere else,
and the open question is not "is it dead" but "is the clock-suppression it carries still
needed, and if so, where does it live". Deleting 633 lines to make a gate green would
have answered that question by accident.

**Exempted** in `scripts/find-unwired-surfaces-baseline.txt` as
`TaskDetailViewScreen`. The exemption is only honoured because
`check-unwired-backlog-refs.py` resolves this heading — which is the point: the entry is
what makes the exemption honest, and the gate fails if it ever stops resolving.

**To close this,** decide one of:

1. Move the clock-suppression behaviour into `TaskDetailScreen` and delete the screen.
2. Wire it, if two detail screens is after all the intent — and say so, since that
   reverses `dad11e6b`.
3. Keep it unwired and accept it as long-lived debt, in which case this entry stays and
   the exemption stays.
