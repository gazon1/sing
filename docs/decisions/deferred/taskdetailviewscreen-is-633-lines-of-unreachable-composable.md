---
title: "Taskdetailviewscreen Is 633 Lines Of Unreachable Composable"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Found in:** 2026-10-06, while running the new `static` gate job against the tree.

**Status: RESOLVED 2026-10-07.** The screen was deleted; the file was the last thing
holding the finding up.

`shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/
TaskDetailViewScreen.kt` was 633 lines with no production call site, so
`scripts/find-unwired-surfaces.py` reported it and the `static` job stayed red.

Its header recorded why it was still there:

> This screen has no call site — `find-unwired-surfaces.py` reports it, and
> `dad11e6b`'s note says deleting another branch's deliberate carrier is the
> owner's call, not this one's.

That was a decision deferred and then not revisited — the failure mode
`an-open-backlog-entry-does-not-mean-the-work-is-still-open` describes. The owner
re-decided on 2026-10-07 and chose deletion over baselining.

The supporting evidence for deleting rather than baselining: the only remaining
mention of the file in the tree was a KDoc in `TaskDetailProposalSection.kt` saying
the section was "Moved out of `TaskDetailViewScreen` when that screen was deleted",
and a test KDoc in `TaskDetailTimeTrackingSectionTest.kt` recording that nothing
composed it. Both were rewritten rather than left dangling. `:shared:compileKotlinJvm`
builds after the deletion, so nothing resolved against it.

**Not to do:** re-add a second task-detail screen as a clock-suppression carrier. That
is what produced the 633 lines, and the reason the #187 one-screen invariant matters
is that two screens under one route and one ViewModel shipped with time tracking on
one platform and not the other — which is the bug
`TaskDetailTimeTrackingSectionTest` now guards against.
---
