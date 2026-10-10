---
title: "A Flow Can Be Unrunnable And Every Check Still Pass"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** #87

**Found in:** 2026-10-04, the same first `smoke` run. `profile/02-isolation.yaml`
was the regression guard for profile isolation — the bug where all profiles
shared one Room namespace. It could not have passed:

- it waited for `id: saved_agenda_name_input` where the profile dialog tags its
  field `profile_create_name_input`;
- it tapped `id: dialog_confirm` inside an `AlertDialog` that never exposed its
  testTags to UIAutomator;
- it seeded with a URL whose `profile=` parameter was ignored.

**All three defects passed every gate in the repository.** `MaestroFlowTagsTest`
checks that ids are *declared in* `TestTags.kt` — both wrong and right ids are
declared, so it saw nothing. `find-unwired-surfaces.py` does not parse flows. The
desktop Compose tests assert on the semantics tree, where the tag works. And no
gate ran the `smoke` tag at all, so nobody had watched it fail.

**The generalisable point, and the reason it is worth a backlog entry:** a flow
is a test that ships with no compiler. `longPress:` did not fail to compile — it
failed at Maestro's parser, on a run that had never happened. Nothing in CI
turns "the flow file exists" into "the flow was executed", so a file can sit in
the repository for months carrying a typo, and its presence reads as coverage.

This is the strongest argument yet for `maestro-ci-job-unproven` being the first
thing to close: a Maestro job that actually runs is the only check in the
repository that would have caught any of the three defects above.

---
