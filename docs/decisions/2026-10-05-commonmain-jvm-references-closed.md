---
title: commonMain referenced the JVM in 21 places, and the gate could only see 17 of them
date: 2026-10-05
status: accepted
---

# commonMain referenced the JVM in 21 places, and the gate could only see 17 of them

## Context

`2026-10-04-commonmain-jvm-api-gate.md` recorded the class of defect — `commonMain`
depending on `java.*` — and installed `CommonMainJvmApiTest` to stop it growing. The
entry was explicit about the trade: *baseline the rest, close them one at a time,
alongside work in those files*. Eleven survivors were listed, each with a reason.

That entry is now stale in a way that mattered. Counting what was actually in
`commonMain` gave **22 references across 15 files**, and only **17** of them were
things the gate could see.

| # | Shape | Seen by the old gate? |
|---|---|---|
| 12 | `import java…` / `javax…` | yes |
| 1 | `KClass.java.enumConstants` in `PreferenceWrappers.kt` | no — no `import` line |
| 1 | `e is java.io.IOException` inline in `SessionStore.kt` | no — fully qualified |
| 1 | `java.time.LocalDate.now()` inline in `AdrTools.kt` | no — fully qualified |
| 1 | `java.time.Instant.ofEpochMilli(…)` inline in `FakeAppDatabase.kt` | no — fully qualified |
| 1 | `java.util.UUID.randomUUID()` in a `${…}` hole in `RoomUsageRecorder.kt` | no — inside a string literal |
| 5 | recorded in the baseline but pointing at a file that no longer held the import | mis-recorded |

The last row is its own defect: baseline entries carry a `file`, and nothing ever
checked it. Matching was on the import *text*, so the entry naming
`LogbookSection.kt` stayed green after that file stopped importing `java.util.Locale`
— a different file still did. The record was wrong and no assertion could say so.

The `RoomUsageRecorder` row is the one that shaped the gate. It was found by a second,
cruder scan run alongside the fixed gate — a plain grep that skips `//` and `*` lines
— and it is the shape that makes "strip the comments and grep" insufficient: a `${ … }`
hole in a string is code, and a lexer that blanks the literal to find the surrounding
text eats the code inside it.

## Decision

**Close all of it, and make the gate match code rather than import lines.**

The ADR's advice was sound when written and is superseded by the count: this is not a
per-file chore any more, it is 21 sites in 14 files, and the gate that was supposed to
hold the line was itself the reason four of them went unnoticed. Leaving a half-swept
baseline behind a gate known to be blind is the exact outcome the original entry
warned about — a gate that passes for reasons nobody can reconstruct.

### The gate

Three changes, each closing a way the previous version went quiet:

1. **Match references, not imports.** A `java|javax|android` qualifier anywhere in code.
   Comments and string literals are blanked first, because `FileChecksum.kt`
   documents *why* it uses okio by naming the `java.security.MessageDigest` it
   replaced — a gate that fires on its own documentation gets switched off. A `${ … }`
   hole in a string is deliberately *not* blanked, which is the one place the
   literal-blanking is allowed to be wrong.
2. **Check the baseline's `file`.** `every_baseline_entry_names_the_file_that_holds_the_site`.
   Matching on text alone made the location decorative.
3. **Pin the scanner itself.** The control case feeds it an import, a fully qualified
   reference, a `KClass.java` extension, a JVM call inside a string template, and four
   near-misses that must *not* be reported. A regex gate fails by going quiet, and this
   project has paid that twice.

The lexer is one regex alternation rather than a `when` per state, because `findAll`
scans left to right and takes the earliest match — which is exactly the lexer's
ordering rule, for free. The first version was a 36-branch `when` that detekt
correctly called too complex to read, and that is worth recording as the reason: the
readable formulation and the enforceable one turned out to be the same formulation.

### The sites

| Site | Replacement |
|---|---|
| 5× `java.io.IOException` | one `catchDataStoreIoError()` on `okio.IOException` |
| `EnumPref`'s `KClass.java.enumConstants` | `EnumEntries` from `SomeEnum.entries` |
| `AttachmentId`'s `UUID.randomUUID()` | the project's own `nextId()` ULID |
| `IdGenerator`'s `AtomicInteger` | `kotlin.concurrent.atomics.AtomicInt` |
| `DraftMviViewModel`'s `ConcurrentHashMap` | a plain map |
| `LogBundleExporter`, `TimeTrackingSection`, `Formatters` | `kotlinx-datetime`, integer padding |
| `AdrTools`' `System.getProperty` | `HostEnvironmentPort` |
| `AdrTools`' `java.time.LocalDate.now()` | `core.platform.todayInSystemZone()` |
| `KoogAgentService.listModels` | Ktor client |
| `FakeAppDatabase`'s `java.time.Instant` | `kotlin.time.Instant` |
| `RoomUsageRecorder`'s `java.util.UUID` in a string template | `core.ids.nextId()` |

## Rationale

**`okio.IOException` and `AtomicInt` are the same objects under different names.**
`okio.IOException` is `actual typealias java.io.IOException` on JVM;
`kotlin.concurrent.atomics.AtomicInt` is the same story for `AtomicInteger`. Both
sites keep byte-identical behaviour on the two targets that exist, and stop naming
something that has no meaning on a third. That is the whole shape of this change: no
reimplementation where a multiplatform name for the same thing exists.

**The DataStore handler was copied five times, and one copy was invisible.**
Four private `catchIOExceptionEmitEmpty()` definitions were byte-identical, and the
fifth — the auth session store, written this week — inlined `java.io.IOException`
inside a `catch` precisely because it was not an import. The shared version takes an
`onError` callback, because the draft store logs the failure and the other four have
nothing to add; a swallowed I/O error is otherwise indistinguishable from a user who
has no settings yet.

**A port, not an `expect fun`, for the host environment.**
`platformModule()` is the module's only `expect`/`actual` seam. A new `expect fun`
would have been shorter and would have quietly added a second seam DI cannot override —
and `AdrStorage` was an `object`, so no test could have pointed it anywhere even with
one. Making it a class over `HostEnvironmentPort` is what produced `AdrStorageTest`.
`AndroidHostEnvironment` answers with the app data directory, which is now a *defined*
answer where the old code asked for two undocumented `System` properties on its way to
a null dereference.

**`EnumEntries` over reflection, because `KClass.java` returns `java.lang.Class`.**
`EnumPref` recovered a constant from a stored name with `enumConstants`. The caller
already holds `SomeEnum.entries`; no reflection is involved at any point now.

**Ktor was already a dependency.** `listModels` opened an `HttpURLConnection` per
call. The client is built lazily and owned by the service, which is a DI singleton —
so the tests that construct one and never list models pay nothing and leak nothing.

**`formatFileSize` had a live bug, and it was not a portability problem.**
`"%.1f GB".format(x)` with no `Locale` formats in the *device's* locale: a user with a
comma decimal separator saw `2,0 GB` next to every attachment and backup size, while
`formatElapsed` passed `Locale.US` and stayed fixed. Hand-rolled padding cannot have a
locale, so the question stopped arising. The rounding is now integer arithmetic with
quotient and remainder handled separately, so `bytes * 10` cannot overflow a `Long`.

## Consequences

- **Month names are English on every device.** `TimeTrackingSection` used
  `SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())`; it now renders `Nov 5, 14:30`
  everywhere. This is a deliberate loss: the task logbook already rendered English
  month abbreviations from a hand-kept table, and one screen answering in the user's
  language while its neighbour did not is the worse inconsistency. The abbreviations
  are now derived from the enum constant's own name, so a month added to `Month` cannot
  be missing from a table.
- **`formatElapsed` caught a regression I introduced.** Padding the leading minutes
  field to two digits changed `0:07` into `00:07`. The existing tests failed; the
  behaviour is the old one, with a comment saying why.
- **Attachment ids are shorter.** `att_<36-char uuid>` is now `att_<26-char ULID>`.
  Nothing parses the body, and rows written before the change keep their old form —
  which is only safe because the format is not parsed anywhere.
- **The baseline is empty.** Every exception that was recorded is closed, so
  `every_baseline_entry_still_exists` and the file check now hold vacuously. They stay,
  because the next exception needs them.
- **One inconsistency is untouched, on purpose.** `NullableStringPref.flow` and
  `EnumPref.flow` never applied the I/O catch that their three sibling wrappers do, so a
  corrupt store propagates there and is absorbed elsewhere. Fixing it changes behaviour
  on two flows nobody has reported a problem with, which is a separate decision.
- **The coverage ratchet measures a `fast`-only run, and that has a cost.**
  `just cr` runs `:shared:jvmTest :desktopApp:test koverXmlReport` with no
  `-Ptest.tags`, so a `slow`-tagged test contributes nothing to the floors. Turning
  `AdrStorage` into a testable class produced a test that has to create and delete
  directories, which is `slow` by the project's own definition — and `feature/ai` then
  measured 405/1721 against a floor of 405/1718, three lines of new production code
  with no *fast* test. The floor is the one whose note says a drop "hides an untested
  billing path", so it is not the place to make an easy concession.

  The resolution was to split the class along the line the tag already draws:
  `AdrStorageResolutionTest` (`fast`) asserts path resolution and frontmatter parsing
  and touches no file beyond one `stat` of a path that does not exist;
  `AdrStorageRoundTripTest` (`slow`) does the write/read round trip. The judgement that
  a read-only `stat` is not a process boundary is recorded in the fast class's KDoc so
  a reviewer can overrule it in one place. The fast half took `feature/ai` from
  405/1718 to 436/1721 — 23.57% to 25.33% — because `AdrStorage` had no test at all
  before this, and every floor that rose was adopted rather than left to slide back.
  The general gap is not fixed here: code whose only honest test needs the filesystem
  is still invisible to the ratchet, and that deserves its own entry rather than a
  workaround inside this one.
- **`VendorSdkConfinementTest`'s allowlist is unchanged.** The three Ktor seams and the
  new `HostEnvironmentPort` are in `core`, not in `feature/ai`.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/CommonMainJvmApiTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/datastore/DataStoreErrorHandling.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/platform/HostEnvironmentPort.kt`
- `docs/decisions/2026-10-04-commonmain-jvm-api-gate.md` — the entry this supersedes
- `docs/decisions/2026-09-26-detekt-baseline-established.md` — the stale-entry pattern reused
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/PlaintextTokenIsolationTest.kt`
  — the other gate in this package with a positive control
