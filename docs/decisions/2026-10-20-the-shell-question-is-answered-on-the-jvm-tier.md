---
title: "The shell question is answered on the JVM tier"
date: 2026-10-20
status: accepted
tags: [testing, desktop, android]
---

## Context

The device tier's smoke test (`AppStartupTest.the_shell_is_addressable_by_test_tag`)
asks three questions in one test:

1. Did the auth bypass reach the build? — `BuildConfig.TEST_AUTH_BYPASS` is a
   flag of the `androidApp` module, and the APK is what carries it.
2. Is the app not on the sign-in screen? — for the real entry point.
3. Do the shell's test tags reach the **accessibility** tree, as a
   `resource-id`?

The third question is what the run costs ten emulator minutes for, and it is
the one that has a portable half. "Does a Compose `testTag` reach the
semantics tree at all?" is a question about the shared UI, and the shared UI
runs on the JVM tier in seconds. The part that cannot travel is the
*accessibility* part: `testTagsAsResourceId` is an Android semantics property,
and the desktop actual of `Modifier.mapTestTagsAsResourceIds()` is a no-op.

The JVM tier is where the "tests actually ran" question is cheapest to
answer, and it is the tier that runs in seconds on every change.

## Idea

- **A.** Leave the question on the device tier. It is where the answer matters.
- **B.** Split the question: the portable half moves to the JVM tier, the
  device-only half stays where it is, and a document says which is which.
- **C.** Move the whole question to the JVM tier, on the argument that the
  shared UI is the same.

## Decision

**B.**

- `ShellTestTagReachabilityTest` (desktopApp, JVM) asserts that the shared
  UI's test tags reach the **semantics** tree, and that a raw-tree inventory
  reports what is there rather than what was asked for. It runs in seconds and
  needs no emulator.
- The device tier keeps the third question in the part that is Android's:
  whether the tag arrives as a `resource-id` a UIAutomator can find.
- `docs/testing/what-each-tier-proves.md` states, for each tier, what runs,
  what it costs, and what it can see — so the split is written down rather
  than inferred from which suite a test happens to live in.

Two claims are kept apart on purpose: a tag can be applied to a node and
still be invisible to an accessibility layer, because the node merges into an
ancestor. So `awaitTag` (the merged tree, the one an accessibility layer
reads) and `tagInventory` (the raw tree) are different assertions.

## Rationale

- **The device tier's ten minutes are the scarcest resource in this repo.**
  The emulator is the most flaky surface in CI, and every minute of it is a
  minute of a red run that has to be triaged. Spending it on a question the
  JVM tier can answer in seconds is spending it on nothing.
- **A question that can travel should travel.** "Does the tag reach the tree"
  is about the shared UI — the same `shared/src/commonMain` code the desktop
  shell renders. Answering it on the tier that renders it makes it a
  *regression* test: it fails the moment the tag stops being applied, in
  seconds, on every change, instead of nightly.
- **C would answer a different question.** The desktop shell's own chrome
  carries no test tags — the drawer entries and the FAB are addressed by
  content description — and the Android shell's tags (`nav_tab_*`,
  `nav_menu_button`, `tasks_fab`) are applied in `shared/src/androidMain`,
  which the JVM tier cannot render. A JVM test that claimed to cover the
  Android shell would be covering nothing.
- **The split has to be written down.** Without the document, a reader sees
  two tests asking about tags and cannot tell which is the real one. The
  document is what makes the JVM test's scope honest.
- **Two trees, two claims.** "The tag was applied" and "the tag is visible to
  an accessibility layer" are different facts, and the node-merging behaviour
  makes them genuinely different. A test that only made the first claim would
  pass on a tree where the tag is invisible.

## Consequences

- **The device smoke keeps the question that is Android's**, and the JVM tier
  gains a seconds-long regression test for the shared UI's tags.
- **The device tier is still the only place the third claim is made.** A
  reader who wants the `resource-id` question answered has to look there,
  which is why the document says so in the tier's own table.
- **The document is a maintenance surface.** A tier that gains or loses a
  question has to be reflected in it, and a stale document would send the
  next reader to the wrong tier.

## Links

- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ShellTestTagReachabilityTest.kt`
  — the portable half
- `docs/testing/what-each-tier-proves.md` — the split, written down
- `docs/testing/android-instrumentation-runbook.md` — the device tier
