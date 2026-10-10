---
title: "Vm Without Test"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#83](https://github.com/gazon1/sing/issues/83)

**Found in:** MR-6, while writing `ViewModelTestCoverageTest` (the Phase 6
"every VM has a test" gate). Pre-existing — none of these were introduced by the
agenda work.

Ten ViewModels ship with no test of their own (was written as eleven; see the
correction below). The rule enforces that this
stops growing; this entry is the debt itself.

| ViewModel | Why it is hard to test today |
|-----------|------------------------------|
| `AccountSettingsViewModel` | Thin wrapper over settings read/write; needs a SettingsRepository fake that does not exist yet |
| `AiUsageViewModel` | Reads LLM usage records straight off the DAO; no fake repository for the usage table |
| `AppVersionGateViewModel` | ~~No test drives it~~ — **closed 2026-10-04**, see below |
| `ArchiveViewModel` | Archive/trash reads go through the task repository; the VM's own state machine is untested even though the queries are |
| `AttachmentsViewModel` | File IO behind `FileSystem`; needs a fake filesystem with checksum support |
| `AuthViewModel` | OAuth session transitions; `core-auth-oauth-is-entirely-unwired` (above) means the flow is not reachable, so there is nothing meaningful to assert yet |
| `CalendarSyncViewModel` | Owns a long-lived debounced collector; the virtual-time setup is the hard part |
| `ProfileSwitcherViewModel` | Reads the profile list; needs a `FakeProfileRepository` wired through the same scope discipline as `ProfileAwareCurrentUser` |
| `SearchViewModel` | `activeFilter = null` is a documented signal, not an error, so a naive test asserts the wrong contract |
| `TagGroupsViewModel` | Cascade deletes are the interesting path and they are covered at the repository level (`TagGroupDeleteCascadeTest`), not at the VM level |
| `TagsViewModel` | **A false positive, corrected 2026-10-04** — `TagRenameTest` *does* drive the VM through `onIntent(TagsIntent.Rename…)` and asserts both the stored row and the observed state. Covered; only the *naming* does not match the rule |

**Correction, 2026-10-04.** Two claims above were wrong and are fixed in the
table:

- `TagsViewModel` **is** covered — `TagRenameTest` constructs the VM and drives
  it through the rename intent, with success, boundary and rejection cases. The
  original note ("covers the rename use case, not the VM") was a misread: the
  test asserts `vm.state.value` as well as the stored row. It is on the
  allowlist only because no test class is *named* `TagsViewModelTest`. That is
  the rule being strict, not a coverage hole — a deliberate trade, since a
  looser rule ("any test mentioning the VM") would pass a file that constructs
  it in a fixture and asserts nothing about it.
- `AppVersionGateViewModel` had **no** test at all; the only mention in the tree
  was `FakeRemoteConfigPort`, a *fake* in the desktop helpers. Both branches of
  its version comparison had never run. Now closed:
  `AppVersionGateViewModelTest` (7 cases — below/at/above minimum, code-not-name
  comparison, CheckAgain re-read, and the failed-refresh fallback that otherwise
  strands the user on a permanent spinner).

So the real list is **ten**, not eleven, and one of the two "easy ones" turned
out to be the genuinely dangerous one: it is the only VM whose failure mode
locks every user out of the app.

**Do this first:** `TagGroupsViewModel` and `SearchViewModel` — both have their
hard repositories already tested, so the remaining work is the VM's own state
machine rather than new infrastructure.

**Rule:** the allowlist lives in `KNOWN_UNCOVERED` in
`shared/src/jvmTest/kotlin/com/singularity/todo/arch/ViewModelTestCoverageTest.kt`.
Removing an entry there without adding a test breaks the build; adding a class
that no longer exists also breaks the build, so the two lists cannot drift
silently.

---
