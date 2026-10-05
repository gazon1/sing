---
title: commonMain imports JVM APIs with no gate to stop it
date: 2026-10-04
status: accepted
---

# commonMain imports JVM APIs with no gate to stop it

## Context

`sync impl plan.txt` item **M1** flagged one `java.security.MessageDigest` import in
`commonMain` (`core/files/FileChecksum.kt`) and proposed okio. While fixing it, a grep
of `commonMain` for `^import (java|javax|android)\.` returned **twelve** call sites
across ten files.

Every one of them compiles today and has a passing test. That is the point: the module
compiles `commonMain` for JVM and Android only, so a JVM import in shared code is
indistinguishable from correct code on every build this repository runs. The failure
mode is a compile error, on a target nobody has, discovered by whoever adds it — not a
review comment at the point of writing.

The list, before the fix in this entry:

| File | Import | Live? |
|---|---|---|
| `core/files/FileChecksum.kt` | `java.security.MessageDigest` | yes — attachment checksums, backup integrity |
| `core/sync/ConflictResolver.kt` | `java.security.MessageDigest` | yes — dies with the row checksum |
| `core/ids/IdGenerator.kt` | `java.util.concurrent.atomic.AtomicInteger` | yes |
| `core/ui/DraftMviViewModel.kt` | `java.util.concurrent.ConcurrentHashMap` | yes |
| `core/attachments/AttachmentId.kt` | `java.util.UUID` | yes |
| `core/log/LogBundleExporter.kt` | `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.Locale` | yes |
| `feature/timetracking/…/TimeTrackingSection.kt` | `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.Locale` | yes |
| `feature/tasks/…/LogbookSection.kt` | `java.util.Locale` | yes |
| `feature/ai/KoogAgentService.kt` | `java.net.HttpURLConnection`, `java.net.URI` | yes |
| `feature/ai/tools/AdrTools.kt` | `java.lang.System.getProperty` | yes |
| `WhatsNewPrefs.kt`, `DataStoreDraftStore.kt`, `PreferenceWrappers.kt`, `CalendarSyncSettingsRepositoryImpl.kt` | `java.io.IOException` | yes |

Ten of the twelve are live production code. None of them is sync-related except two.

## Decision

**Fix the one the plan named. Gate the class. Baseline the rest.**

1. `FileChecksum` uses `okio.Buffer().write(data).sha256().hex()`. okio is already a
   dependency, is multiplatform, and the encoding is byte-identical to the old
   `"%02x".format(...)`.
2. `CommonMainJvmApiTest` fails the build on a new `java|javax|android` import in
   `commonMain`.
3. The eleven survivors are baselined **with a reason each**, so the gate blocks growth
   without pretending the debt is gone.

## Rationale

**Why a baseline instead of a clean sweep.** Eleven sites is a project-sized change,
unrelated to whatever feature is in flight, and it cannot be safely interleaved with
unrelated work: a half-done sweep either leaves a gate that passes for reasons nobody
can reconstruct, or a gate that fails and blocks everything. What is cheap and
permanent is the *guard*; the cleanup is per-site work that belongs next to whoever
already has that file open.

**Why a gate is the higher-leverage half.** The finding is not really twelve bugs, it
is that nothing stopped the twelfth. Every other architecture rule in
`shared/src/jvmTest/kotlin/com/singularity/todo/arch/` exists because a specific class
of defect was found at least once. This class had a dozen instances and no gate, which
means it was not a coincidence.

**Why the baseline cannot rot.** `every_baseline_entry_still_exists` fails when a
listed import disappears, so a fixed site must have its line deleted. The same pattern
as `ScopedWriteQueryIsolationTest`'s allowlist and `find-unwired-surfaces-baseline.txt`
— the shared trick is that a stale entry is an error, not a silent pass. Without that
assertion a baseline becomes a graveyard and the gate decays into decoration.

**Why not add a third target to prove the point.** Tempting, and it would find
everything at once — but a target means a CI job, a toolchain, and a review surface
larger than the bug it exposes. The gate finds the same imports in milliseconds.

## Consequences

- `FileChecksum` is multiplatform. The old `"%02x".format(it)` was itself a JVM
  formatter, so the obvious in-place fix would have moved the same bug one level down;
  `ByteString.hex()` avoids it.
- Attachment and backup checksums are computed slightly faster (no `getInstance`
  lookup per call) and produce identical bytes, so existing stored checksums still
  verify. `FileChecksumTest` pins the digest against known vectors and is unchanged.
- The gate is `fast`-tagged and reads only the `commonMain` tree — a few milliseconds,
  and it is registered like the other arch gates so CI enforces it.
- Eleven known violations remain, each with a file and a reason in the test. A future
  agent touching `IdGenerator.kt` or `KoogAgentService.kt` sees the reason in place and
  can close the entry as part of that work, which is strictly cheaper than rediscovering
  it.
- Two of the twelve are scheduled to disappear on their own: `ConflictResolver.kt` dies
  with the sync row-checksum removal. When it does, the baseline entry must be deleted
  or the build fails — which is the intended behaviour, not a nuisance.

## Update, 2026-10-05

The eleven survivors are gone, and the list above was short in a way that mattered:
`commonMain` held **21** JVM references across 14 files, and the gate above could see
only 17 of them — four were fully qualified rather than imported, and one baseline entry
named a file that no longer held the import. The gate now matches references anywhere in
code, and checks that a baseline entry's `file` is the file that holds the site.

The decision to gate rather than sweep still stands; the sweep happened, later than this
entry expected, because the count turned out to be double what the baseline recorded.
See [2026-10-05-commonmain-jvm-references-closed](./2026-10-05-commonmain-jvm-references-closed.md).

## Links

- [2026-10-05-commonmain-jvm-references-closed](./2026-10-05-commonmain-jvm-references-closed.md)
  — closes the survivors and repairs the gate
- `shared/src/commonMain/kotlin/com/singularity/todo/core/files/FileChecksum.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/CommonMainJvmApiTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/ScopedWriteQueryIsolationTest.kt`
  — the stale-entry-assertion pattern this reuses
- `/home/max/Documents/sync impl plan.txt` §15, item M1
- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md` — the change that
  removes the second `MessageDigest` import
