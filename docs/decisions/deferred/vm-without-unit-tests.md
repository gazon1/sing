---
title: "Vm Without Unit Tests"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `ViewModelTestCoverageTest` fails on any ViewModel without a test unless it is in the `KNOWN_UNCOVERED` set, and the KDoc requires each allowlist entry to name its backlog entry here — so the debt is a reviewable list rather than a silent default. The residual is the allowlist itself and is tracked as #83; this entry closed because the mechanism that keeps the debt visible exists.

**Found in:** MR-0, свип ViewModel vs *ViewModelTest.

**Symptom:** 12 production ViewModel'ов не имеют выделенного `*ViewModelTest`:
`SavedAgendaListViewModel`, `TagGroupsViewModel`, `TagsViewModel`, `SearchViewModel`, `ArchiveViewModel`, `AttachmentsViewModel`, `AuthViewModel`, `CalendarSyncViewModel`, `AccountSettingsViewModel`, `ProfileSwitcherViewModel`, `AppVersionGateViewModel`, `AiUsageViewModel`.

`SavedAgendaListViewModel` относится к agenda-views фиче и будет покрыт в MR-3.

**Status: RESOLVED** (2026-10-04). This entry and `vm-without-test` below were
the same finding, written twice: this one names the plan, the other names the
debt. It is kept as the historical record and is not the entry to read.

What happened: `SavedAgendaListViewModelTest` was written (jvmTest, alongside
`SavedAgendaViewModelTest` and `SavedAgendaViewsRepositoryImplTest`), and the
Konsist idea was replaced by a cheaper JVM arch test,
`ViewModelTestCoverageTest`, which fails on any ViewModel lacking a test unless
it is in an explicit `KNOWN_UNCOVERED` allowlist. The remaining debt and the
next VMs to drain are tracked in **`vm-without-test`**.

---
