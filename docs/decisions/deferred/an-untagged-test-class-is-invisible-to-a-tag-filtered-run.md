---
title: "An Untagged Test Class Is Invisible To A Tag Filtered Run"
date: 2000-01-01
status: RESOLVED
tags: ["deferred"]
---

**Found in:** 2026-10-04, on the first CI run of the verifiability branch — the
run that finally executes `testAndroidHostTest`, which the old
`-Ptest.tags=fast,slow` filter had meant never ran at all.

**Status: RESOLVED (2026-10-05).** The open question below — should an untagged class
fail a gate? — is answered yes, by `TestTagCoverageTest`, and that gate's own
`@Test`-only blind spot was found and closed in the same change. See "Try next" below.

**Tracked as:** #74 (fixed in the same branch); the open question below is the
gate, not the test.

**Symptom.** `ReadToolsProfileAwareTest` has five structurally identical tests, and
which ones fail changes every run: 2 of 970 on a forced `main` run, 1 of 980 on
this branch, a *different* one each time. A probe of the same scenario in
isolation passes.

**Root cause — a race the test had with itself, not a tool bug.** The tool
correctly returned nothing. `ProfileAwareCurrentUser` seeds `scopedUserId`
synchronously in its constructor, so the construction-time value is right. The
race is one line later: `profiles.switchTo(...)` changes an upstream, and the only
thing that propagates that into `scopedUserId` is a collector on the **injected
scope**. The test injected `createBackgroundScope()` — `Dispatchers.Default` — so
whether the tool's `scopedUserId.flatMapLatest { … }` read the profile-scoped value
or the stale pre-switch one was a race. The task was stored under `profile/user`,
the filter used `user`, and the result was `expected: <1> but was: <0>`.

The class's own KDoc states the contract that was broken — *"In tests, inject a
`TestScope` or `backgroundScope`"* — and the test carried a comment justifying the
violation on a premise that is false: the tools under test **do** subscribe.

**The finding that outlives the fix.** The class carries **no `@Tag`**. A
tag-filtered run skips an untagged class silently, and nothing records the
omission. `TestTagsWiringTest` verifies that every *tag* is applied by a
composable; it does not verify that every test class carries one. So the class
could be arbitrarily broken — as it was — for as long as nobody added a tag.

**Try next — the open question.** Should an untagged test class fail a gate?

A class with no tag is not a test that is deliberately deferred; it is a test that
is *unrunnable* in a tag-filtered build, and the difference is invisible from the
source. If tag filtering is going away, the question is moot. If it stays for the
slow suite, an untagged class is a hole with no marker.

**Answered 2026-10-05 — yes, and the gate that does it had its own hole.**
`TestTagCoverageTest` (shared/src/jvmTest) fails any test class in a tag-filtered
source set that carries no `@Tag`. But it detected test members by matching `@Test`
alone, so a class whose tests are `@ParameterizedTest` registered as *having no
tests* and was reported clean. Two such classes were, at that moment, invisible to
CI for the second time — the gate about untagged classes was blind to the same
condition it was written for:

- `RecurrenceRuleMapperTest`
- `RruleGeneratorTest`

Both are now `@Tag("fast")`, and the gate matches every JUnit test annotation
(`@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`,
plus the `kotlin.test` spelling) instead of one. The check verifies what it claims:
a class with a test member and no tag fails, whichever annotation carries the test.

Scope note, checked rather than assumed: `detekt-rules` (18 untagged classes) and
`androidApp` (4) are **not** in the gate's source-set list, and do not need to be —
neither module's test task applies a tag filter, so an untagged class there still
runs. Adding them would have been the loud wrong fix. The list now names the
criterion it encodes: source sets *whose Gradle task translates `-Ptest.tags` into
a JUnit filter*.

Also worth noting: `koverXmlReport` depends on `testAndroidHostTest`, so the
`kover-report` job was **red on `main`** for this reason. A job that is red for a
reason nobody reads is the same failure as a gate that is green for a reason nobody
checks.


---

**Re-measured 2026-10-07.** The rule was right and the scope was not: `TestTagCoverageTest`
listed `shared/src/commonTest`, `shared/src/jvmTest`, `desktopApp/src/jvmTest` and
`mcp-server/src/test`, but not `shared/src/androidHostTest` — a source set that applies the
same `-Ptest.tags` filter. The first class added to it (`AndroidSyncDiGraphResolutionTest`)
was therefore never checked, and two further facts surfaced that the entry did not predict:
`includeTags` excludes untagged classes, and the Vintage engine drops Jupiter's `@Tag` from
JUnit4 ones entirely, so a Robolectric class cannot be selected by a tag filter at all.
`androidHostTest` is now listed, `testAndroidHostTest` is exempt from an explicit tag filter,
and the finding is in "the-android-graph-test-runs-but-cannot-open-a-database" in
`deferred-backlog.md`.
