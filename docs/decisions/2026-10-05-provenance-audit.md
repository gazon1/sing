---
title: "Provenance audit before publication: one GPL package, one proprietary SDK"
date: 2026-10-05
tags: [legal, licensing, provenance, open-core, publication]
status: accepted
---

## Context

The plan to publish this repository — core under Apache-2.0, a `pro/` catalogue under
FSL-1.1-Apache, variant B of the open-core options — rests on one assumption that had
never been checked: **that the Apache-2.0 half contains no derivative work from a
copyleft source, and no dependency whose licence forbids redistribution under
Apache-2.0.**

The assumption was not safe to hold. Two markers in the tree said it was false. A note
in a KDoc is not a control, and the project had no control: no `LICENSE` file, no
`NOTICE`, and no registry of what came from where.

This ADR records what the audit found. It does not authorise publication; it records
the two blockers that have to be cleared first, and the one that is already cleared.

## Decision

### 1. Four classes, not a yes/no

Every non-original file is classified as `ORIGINAL`, `SPEC-COMPATIBLE`, `ADAPTED` or
`PORTED`, in [`docs/legal/PROVENANCE.md`](../../legal/PROVENANCE.md) with a
machine-readable form in `config/legal/provenance-registry.tsv`.

`ADAPTED` is separated from `ORIGINAL` for a reason that is not legal necessity:
a layer of someone else's API over your own logic is worth *naming* even where
nothing is owed. The project had already marked some of these itself, in KDoc, before
this audit existed — `core/billing` carries Tasks.org field names, and ADR
`2026-09-23-billing-abstractions` says the names were chosen to match. The audit found
the rest.

### 2. One package is `PORTED` and must be rewritten

`feature/search/query/` — 785 lines across 11 files, all from Orgzly (GPL-3.0), all
marked *lifted from Orgzly* in the project's own KDoc, and ADR
`2026-09-23-search-query-language` calls the work a **port**. The package is
load-bearing: `SearchUseCase` and `SearchViewModel` both construct
`SingularityQueryParser(QueryTokenizer(input).tokens())`. It is rewritten, not removed.

**The rewrite is not a clean room, and this ADR does not claim it is.** Clean room in
the strict sense requires the implementer not to have read the original; the author
read it. Rewriting the same behaviour from the same memory does not terminate a
derivative work — it removes verbatim correspondence, which is a weaker property.

Adopted: **A1** — reimplement from the *documented grammar* and a *behavioural test
suite*, with the grammar written out first and the tests written from the grammar
rather than from the Orgzly source. **A2** (an implementer with no access to Orgzly)
is stronger and remains available. **C** (accept GPL-3.0 over the core) is rejected: it
forecloses selling a subscription against a proprietary `pro/` layer.

When A1 is done, this file and `docs/legal/PROVENANCE.md` are updated to say so,
including the fact that the author had read the original. Concealing that is the one
unacceptable outcome — a reader is entitled to know the implementation was not written
by someone who had not seen the source.

#### Executed (2026-10-05)

1. **The specification was written first** —
   [`docs/specs/search-query-grammar.md`](../specs/search-query-grammar.md), a normative
   grammar in six sections, with every rule that is easy to get subtly wrong named and
   justified (R1, R3, G1, G2, G3, C1, D1). The honesty clause is the first thing in the
   document, not a footnote.
2. **The implementation was rewritten from it** — `QueryTokenizer`, `QueryParser`,
   `ConditionExprParser`, `QueryInterval` — and the seven vocabulary types re-declared as
   this project's own domain model. `DaoAdapters` was additionally reclassified: the audit
   had called it `PORTED`, which was wrong. It wraps this project's own Room DAOs and had
   no counterpart in Orgzly.
3. **The behavioural suite was the contract, and it earned its keep.** 134 tests, seven
   classes, unchanged — and they caught two defects in the rewrite that reading the
   specification had not:
   - a conjunction loop whose guard excluded the `AND` token, so `a AND b` parsed as `a`;
   - a flatten that compared callable references with `===`, which never matched, so
     nested nodes were never flattened and `parse("AND")` produced `And([And([]), And([])])`.

   Both would have shipped as silently wrong search results — a filter that returns a
   superset, which is the worst failure mode a filter has.

**A second implementation of the grammar was also removed.** `QueryParser` carried a
private `parseAtom`/`parseGroup` pair — 105 lines, reachable only from each other, because
`parse()` threw and every real caller overrode it. It *disagreed* with the live parser: it
threw on an unclosed parenthesis (against G9), required two operands for an `OR`, and made
`NOT` bind only the following atom (against G3). That is not dead code, it is a trap with
a comment above it.

**The registry class is `REWRITTEN`, not `ORIGINAL`.** A fifth class was added to
`check-provenance.py` for files that were derived from a copyleft source and no longer
are. The point is that the lineage stays *visible*: promoting these rows to `ORIGINAL`
would have been accurate about the code and misleading about the history, and the whole
reason this audit exists is that nobody had recorded that Orgzly was in the tree at all.
`PORTED` is now empty.

The search **DSL** is a specification, not code. Compatibility with Orgzly and
Tasks.org syntax survives every option.

### 3. `ru.ok.tracer` is a proprietary dependency in the Apache-2.0 core

`tracer-crash-report:1.4.0` is a direct `implementation` dependency of both `:shared`
(androidMain) and `:androidApp`, wired through the `ru.ok.tracer` Gradle plugin. It
declares **"Tracer's License Agreement"** — <https://apptracer.ru/license/> — a
proprietary, service-bound agreement, © VK. It is not OSI-approved and was not
anticipated by the plan.

**Remediation: move the Tracer implementation into `pro/`.** The SDK is already
confined to **two** production files —
`shared/src/androidMain/.../observability/AndroidCrashReportingPort.kt` and
`androidApp/.../SingularityApp.kt` — behind the existing `CrashReportingPort`
interface, with a JVM no-op on the other platform. `VendorSdkConfinementTest` is
already the pattern for holding a vendor SDK to a file allowlist. The fix is a file
move and a file-log-backed free-core implementation, not a redesign. The alternative —
dropping crash reporting — is a product decision and is not taken here.

#### Executed (2026-10-05)

| | Free build | Pro build |
|---|---|---|
| `CrashReportingPort` | `FileCrashReportingPort` (rolling Kermit log) | `TracerCrashReportingPort` (AppTracer) |
| `Application` | `com.singularity.todo.SingularityApp` | `com.singularity.todo.pro.ProSingularityApp` |
| `ru.ok.tracer` in the dex | **0 occurrences** | 347 string references |

`:pro` is a Gradle module under FSL-1.1-ALv2 (`LICENSE.pro`), included by
`settings.gradle.kts` **only** behind `-PwithPro=true` — an unconditional include would
make the free configuration unbuildable, which would make the open-core claim true only
on paper. A fresh clone therefore resolves no proprietary artefact and needs no
credentials.

Two details that were not obvious and are recorded because each cost a build:

- The vendor requires the `Application` to implement `HasTracerConfiguration`, and the
  SDK type-checks for it. An `if (withPro)` branch inside the Apache-2.0 class would have
  put a vendor type in an Apache-2.0 *file* even though the branch never executes, so the
  class is split into a base class and a pro subclass selected through an `appClass`
  manifest placeholder. `SingularityApp` is now `open` for that one reason.
- AGP 9 ships built-in Kotlin support and **fails the build** if
  `org.jetbrains.kotlin.android` is applied as well, so `:pro` applies no Kotlin plugin.
  It also applies `id("com.android.library")` unversioned — AGP is already on the
  buildscript classpath from `:androidApp`, and an alias carrying `version.ref = "agp"`
  fails with *"the plugin is already on the classpath with an unknown version"*.

**The cost, stated plainly:** the free build loses the network, not the record. A crash
still reaches the log file the user already owns and can export, but nothing is collected
on their behalf. For a local-first app that is a defensible default rather than a
placeholder, and it is the price of the Apache-2.0 core — not a reduction that should be
called cosmetic.

`scripts/check-pro-licence-boundary.py` keeps the split real, and its `--verify-apk` mode
reads the built dex. That mode is the point of the gate: every other rule is static
analysis, and all of them could be true while a transitive dependency put the vendor
classes in the free APK anyway.

### 4. A gate, with a control

`scripts/check-provenance.py` fails when a production file names an external upstream
(Orgzly, Tasks.org, Astrid, orgmode.org) or carries an SPDX/GPL tag and has no
registry row; when a registered path does not exist; when a `PORTED` or `ADAPTED` file
lacks a `// Provenance:` annotation or contradicts the registry; and when the scan
finds nothing at all.

That last rule is what makes the others meaningful. The first draft of the marker
pattern also matched `derived from`, `taken from` and `mirrors <Thing>'s`, and produced
**30 false positives** on its first run against the real tree — *"a fixed name instead
of one derived from wall-clock time"*, *"lifted from domainTask to avoid reference"*.
It was narrowed to upstream names and licence tags, which is honest about what it can
and cannot see: it detects a file that *names* an upstream, not a file that was
derived from one. A derivation nobody wrote down is not detectable by any scan.

The rule carries a control in three places, because a rule that cannot fail is not a
rule and this project has been bitten by that twice:

- `python3 scripts/check-provenance.py --self-test` plants a marked file in a temp
  directory and asserts it is caught; it also asserts an ordinary file is *not*
  reported, and that `pro/` and `commonTest` are out of scope. Both invocations run in
  `just docs-audit`.
- `scripts/tests/test_check_provenance.py` — 24 tests, including the seven prose
  false positives that made the broad pattern unusable, so it cannot drift back.
- `check-gate-wiring.py` registers the gate with a sabotage control: remove the
  `QueryTokenizer` row from the registry and the gate must fail.

`SPEC-COMPATIBLE` and `ORIGINAL` files do not need an annotation. Marking every file
would train the annotation to mean nothing.

### 5. History

`git rev-list --count --all` = 1 270 commits, all between 2026-09-30 and 2026-10-05.
Three author addresses, one of them a real personal address
(`newbox2517@inbox.ru`). No secrets: the only hits for "secret" are file *names* in
`SKILL.md` and ADRs, and `infra/kiwi/secrets.env` is gitignored and never committed.

The whole `feature/search/query/` package arrived in **one** commit, `b6394207`, and
that commit's version of `QueryTokenizer.kt` already carries the `lifted from Orgzly`
header. So there are no early revisions to strip: either the path is removed from
history and re-added clean, or history is squashed to a single start commit. **Editing
the file's contents does not remove it from history** — that is the whole of W2, and it
is not optional.

Six days of history is why this is cheap. ADRs are files; they survive a rewrite. What
is lost is the commit-by-commit sequence, and that sequence is exactly the trace that
cannot be left behind.

### 6. `.mailmap`

All three author addresses and all three display names — including the real personal
name — are mapped to a single published identity, `gazon1 <gazon1@users.noreply.github.com>`.
`git shortlog --all -sne` now reports one author, 1 273 commits.

Two details cost a measurement each, and both are recorded because the failure is
silent:

- **`.mailmap` has no comment syntax.** The first version opened with `#` explanation
  lines; git parsed them as malformed entries and the mapping applied to nothing, with
  no error. A `.mailmap` therefore cannot explain itself — the reasoning lives here.
- **The alias in the plan, `gazon1 <noreply@github.com>`, is not a valid address.** The
  nested angle brackets are parsed as an email and fail to match. GitHub's
  `gazon1@users.noreply.github.com` form is used instead.

A `.mailmap` rewrites the *display* of history only. `git log --all --format='%ae'`
still returns `newbox2517@inbox.ru` across 20 commits, and `%an` still returns
`Drobin Max` — because the address and the name live in the commit objects. **Shipping
the mailmap and assuming the address is gone is the mistake this section exists to
prevent.** W2 must rewrite the objects.

## Consequences

**Blocked until cleared:** nothing outstanding. Both blockers found by this audit —
the `PORTED` package and `ru.ok.tracer` — were cleared on 2026-10-05 (see §2 and §3).
What remains before publication is W2 (history) and W3 (licence files and README), which
are not this ADR's business.

**Cleared by this audit:**

- The risk is bounded and named. One package, 785 lines, one upstream, one licence —
  not a diffuse unknown across 1 323 files.
- `feature/search/query/` is documented honestly, including the fact that the KDoc
  said "lifted" all along. A reader can now see the derivation instead of inferring it.
- The `ADAPTED` layer — interfaces, names, conventions — is assessed as carrying no
  copied expression, so it does not block publication.

**Two defects found and fixed while removing the dead reference**

`Migrations.kt:62` cited a *"sync-orgzly-adoption ADR"* that exists nowhere. Editing
that comment was the whole intended change — and it turned out to be the visible edge
of two more defects, which is the usual shape of a stale reference.

1. **A dangling KDoc, suppressed by the baseline.** The comment block sat in
   `Migrations.kt` with no declaration after it. detekt reported it; the baseline
   contained an entry for it, keyed by the KDoc's *text*. Editing the text therefore
   broke the baseline match and turned a suppressed finding into a build failure. The
   baseline had been holding a real finding for as long as the finding existed.
2. **The orphaned KDoc was right and the class KDoc was wrong.** Room's exported
   schemas are the authority: `16.json` shows v15→v16 adds `remote_configs`, and
   `18.json` shows v17→v18 adds `saved_searches`. The orphan said `remote_configs` —
   correct. `Migration15To16`'s own KDoc said `saved_searches` — a copy of
   `Migration17To18`, which is the migration that genuinely adds that table. The wrong
   note was on the real class; the right note was on an orphan.

Fixed by moving the correct content onto `Migration15To16`, deleting the orphan, and
dropping the now-obsolete baseline entry (`357 → 356`, so the ratchet still holds).
`:shared:detekt` is green.

The generalisable point: **a migration note is not self-checking, and the baseline can
outlive the finding it suppresses.** Any KDoc that changes text silently invalidates
its own baseline entry, which means baseline-keyed KDoc findings are load-bearing in a
way that is easy to mistake for noise.

**New costs:**

- Every `PORTED`/`ADAPTED` file carries a header comment. 21 files, ~4 lines each.
- `docs-audit` runs one more script, twice.

**Stated limits — what this audit did *not* establish:**

- **No plagiarism detection was run** against Orgzly or Tasks.org source. The `PORTED`
  classification rests on the project's own markers and ADRs plus review of the flagged
  files. A tool comparison against both upstreams is the next evidence to gather, and it
  is what would turn *"marked as lifted"* into *"measured as overlapping"*.
- **The ~1 323 unmarked `.kt` files are `ORIGINAL` by absence of evidence.** That is the
  absence of a note, not a clean-room result.
- **Koog (`ai.koog:*`) ships no `<licenses>` block** in its POMs. Apache-2.0 is asserted
  from the project's repository, not from artefact metadata. Recorded rather than
  treated as verified.

## Links

- [`docs/legal/PROVENANCE.md`](../../legal/PROVENANCE.md) — the registry and the argument
- [`docs/specs/search-query-grammar.md`](../specs/search-query-grammar.md) — the normative grammar the rewrite was written from
- [`config/legal/provenance-registry.tsv`](../../config/legal/provenance-registry.tsv)
- [`scripts/check-provenance.py`](../../scripts/check-provenance.py)
- [`scripts/check-pro-licence-boundary.py`](../../scripts/check-pro-licence-boundary.py)
- [`LICENSE.pro`](../../LICENSE.pro) — FSL-1.1-ALv2, the `pro` catalogue's terms
- [`pro/build.gradle.kts`](../../pro/build.gradle.kts) — the catalogue, behind `-PwithPro=true`
- [`scripts/tests/test_check_provenance.py`](../../scripts/tests/test_check_provenance.py)
- ADR `2026-09-23-search-query-language` — describes the search work as a port
- ADR `2026-09-23-billing-abstractions` — "take only the abstraction" from Tasks.org
- ADR `2026-09-23-analytics-port` — Tasks.org naming conventions
- ADR `2026-09-22-calendar-sync-tasks-org-patterns` — Tasks.org CalDAV patterns
- ADR `2026-10-04-apptracer-integration` — the Tracer integration
- Tracer licence: <https://apptracer.ru/license/>
