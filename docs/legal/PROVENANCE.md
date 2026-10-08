# Provenance registry

Every file in this repository that is not wholly original, and every dependency that
is not permissively licensed. The machine-readable form is
[`config/legal/provenance-registry.tsv`](../../config/legal/provenance-registry.tsv);
this document is the argument behind it.

`scripts/check-provenance.py` fails the build when the two disagree in either
direction: a file with a derivation marker that is not registered, and a registered
file whose annotation contradicts the registry.

## Why this document exists

The project intends to publish its core under Apache-2.0 and a `pro/` catalogue under
FSL-1.1-Apache (ADR `2026-10-05-provenance-audit`). That plan is only sound if the
Apache-2.0 half contains no derivative work from a copyleft source. GPL contamination
does not wash off in proportion to how little code it touches: the claim attaches to
the whole work, not to the 785 lines that provoked it.

The audit was run on 2026-10-05 against `git rev-list --count --all` = 1 270 commits
spanning 2026-09-30 → 2026-10-05.

## Classes

| Class | Meaning | Obligation |
|---|---|---|
| `ORIGINAL` | Written for this project. No external source consulted. | none |
| `SPEC-COMPATIBLE` | Implements a **documented format or DSL** (Orgzly / Tasks.org query and recurrence syntax). The syntax is the interface; the implementation is ours. | record the spec, no code obligation |
| `ADAPTED` | The **shape** of an abstraction — names, layering, naming conventions — follows another project. No expression copied. | file annotation |
| `PORTED` | An implementation was brought across from a copyleft source and then edited. | file annotation **and** resolution (below) |
| `REWRITTEN` | **Was** `PORTED`; reimplemented from a written specification and the derivative code deleted. | file annotation, permanently — the lineage stays visible |

`ADAPTED` is separated from `ORIGINAL` because a layer of someone else's API over your
own logic is legally and ethically worth naming even where it is not a legal
obligation. The project marked some of these itself, in KDoc, before this audit
existed; the audit found the rest.

## Registry

### `REWRITTEN` — was a port, no longer is (resolved 2026-10-05)

| File | LOC | Source | Source licence | Resolution |
|---|---|---|---|---|
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/QueryTokenizer.kt` | 134 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/QueryParser.kt` | 203 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/ConditionExprParser.kt` | 160 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/Condition.kt` | 92 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/QueryInterval.kt` | 71 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/Query.kt` | 31 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/Relation.kt` | 27 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/QueryParseException.kt` | 10 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/Options.kt` | 10 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/SortOrder.kt` | 23 | Orgzly | GPL-3.0 | **Done — A1** |
| `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/query/DaoAdapters.kt` | 24 | Orgzly | GPL-3.0 | **Done — A1** |

**785 lines, one package, one copyleft source.** The package is load-bearing:
`SearchUseCase` and `SearchViewModel` both construct
`SingularityQueryParser(input).parse()`. It was rewritten, not removed.

#### Resolved: option A1, executed 2026-10-05

1. **The specification was written first** —
   [`docs/specs/search-query-grammar.md`](../specs/search-query-grammar.md), a normative
   grammar in six sections with every easy-to-get-wrong rule named (R1, R3, G1, G2, G3,
   C1, D1). It carries the honesty clause at the top: *writing a spec does not make this a
   clean room and this document does not claim it is one.*
2. **The implementation was rewritten from that specification** — `QueryTokenizer`,
   `QueryParser`, `ConditionExprParser`, `QueryInterval` — plus the seven vocabulary types,
   re-declared as this project's own domain model.
3. **The behavioural suite was the contract.** 134 tests across seven classes, unchanged,
   and they earned their place: they caught two real defects in the rewrite that reading the
   specification had not — a conjunction loop whose condition excluded `AND`, so `a AND b`
   parsed as `a`; and a flatten that compared callable references with `===`, so it never
   flattened. Both would have shipped as silently wrong search results.

**What was also removed:** `QueryParser` carried a second, private implementation of the
same grammar — 105 lines, `parseAtom`/`parseGroup`, reachable only from each other. It
*disagreed* with the live parser: it threw on an unclosed parenthesis, required two
operands for an `OR`, and made `NOT` bind only the following atom. A second
implementation of a grammar that contradicts the first is not dead code; it is a trap with
a comment above it.

**Class changed from `PORTED` to `REWRITTEN`,** not to `ORIGINAL`. The new class exists so
this lineage stays visible in the registry instead of being quietly promoted once the
rewrite landed. A reader must be able to see that Orgzly was in the history even though
no line of it remains.

The project's own ADR `2026-09-23-search-query-language` describes this work as a
**port** ("Orgzly-revived's query/savedsearch subsystems needed to be ported into the
KMP project"), and the two files above carried `lifted from Orgzly` in their KDoc
before this audit. This registry does not soften that record.

#### The honest position on "clean room"

A rewrite is **not** a clean room. Clean room in the strict sense requires the
implementation to be written by someone who has not read the original. The author of
this code has read Orgzly. Rewriting the same behaviour from the same memory does not
terminate a derivative work; it removes verbatim correspondence, which is a different
and weaker thing.

The plan therefore records the process explicitly rather than claiming a clean room
that did not happen:

- **A1 (adopted, minimum).** Reimplement from the **documented grammar** and a
  **behavioural test suite**. The grammar is written out first and the tests are
  written from the grammar, not from the Orgzly source. Residual risk: low-to-medium —
  no verbatim copying, but the implementer knew the original.
- **A2 (stronger, optional).** A second implementer with no access to Orgzly writes
  it from the same specification and tests. Residual risk: low. Costs more.
- **C (rejected).** Accept GPL-3.0 over the whole core. This forecloses selling a
  subscription against a proprietary `pro/` layer and is incompatible with the goal.

**A1 is the floor. It must be written down in this file when it happens, including the
fact that the author had read the original.** Concealing that would be the one
unacceptable outcome: a reader of this repository is entitled to know that the
implementation was not written by someone who had not seen the source.

The search **DSL** — the syntax users type — is a specification, not code. Compatibility
with Orgzly and Tasks.org syntax survives every option above.

#### History

The entire package arrived in a **single** commit, `b6394207` (2026-09-30, *"docs:
refresh DIGEST after MR-0+MR-1 ADRs"*). `git log --diff-filter=A` returns one commit for
the whole `feature/search/query/` path, and that commit's version of `QueryTokenizer.kt`
already carries the `lifted from Orgzly` header.

Consequence for W2: there are no early revisions to strip. Either the path is removed
from history wholesale and re-added clean, or history is squashed to a single start
commit. Rewriting file contents is not sufficient — see ADR
`2026-10-05-provenance-audit` §History.

### `ADAPTED` — pattern level

| File | LOC | Source | What was taken |
|---|---|---|---|
| `core/billing/SubscriptionProvider.kt` | 53 | Tasks.org (GPL-3.0) | Interface shape. Field names `isTasksSubscription`, `isGitHubSponsor` are carried over verbatim, so the source is nameable. |
| `core/billing/Entitlement.kt` | 33 | Tasks.org (GPL-3.0) | `hasPro` / `hasAccount` / `hasSubscription` triple, plus the `purchaseStateFor` factory. The file was `PurchaseState.kt` until `0a7f8222`, which scoped entitlement by `SyncScope` and moved the `PurchaseState` declaration here. |
| `core/billing/BillingProvider.kt` | 18 | Tasks.org (GPL-3.0) | Abstraction only. |
| `core/billing/NoopSubscriptionProvider.kt` | 14 | Tasks.org (GPL-3.0) | Single no-op implementation. |
| `core/analytics/Analytics.kt` | 83 | Tasks.org (GPL-3.0) | `logEvent` / `identify` / `logEventOncePerDay` shape. ADR `2026-09-23-analytics-port` states the names were chosen to match. |
| `core/analytics/AnalyticsEvents.kt` | 144 | Tasks.org (GPL-3.0) | Naming convention for event names and `PARAM_*` keys. |
| `core/analytics/NoopAnalytics.kt` | 15 | Tasks.org (GPL-3.0) | No-op implementation. |
| `feature/calendar_sync/sync/SyncSource.kt` | 49 | Tasks.org (GPL-3.0) | `SyncSource` enum, narrowed to one-way sync. |
| `feature/calendar_sync/error/CalendarSyncExceptions.kt` | 29 | Tasks.org (GPL-3.0) | Layered `SyncException` hierarchy. |
| `core/settings/SettingsBundle.kt` | — | Orgzly (GPL-3.0) | The persisted / ephemeral settings split, described in its own KDoc. |

**Assessment.** Interface shapes, method names and enum members are the kind of
expression that does not carry copyrightable originality, and the ADR that created each
one wrote down the source at the time. This class is recorded for accuracy, not because
it blocks publication. It would become a blocker only if a file's *body* turned out to
be transcribed; on review none is.

### `SPEC-COMPATIBLE` — no code obligation

| File | LOC | Spec |
|---|---|---|
| `feature/tasks/domain/logic/RecurrenceParser.kt` | 309 | Orgzly + Tasks.org recurrence syntax (`+1w`, `++1w`, `!+1w`, `every Mon/Wed/Fri`, `1st of month`) |
| `feature/tasks/domain/model/RecurrenceSpec.kt` | — | same |
| `feature/search/query/SingularityQueryParser.kt` | 220 | Search DSL grammar (orgmode.org, Tasks.org query reference) |
| `feature/search/query/SimpleFilter*` | 443 | Round-trip DSL over the same grammar |

The **syntax** is the published interface of those projects. Implementing a documented
grammar is not a derivative work of the implementation. This holds for the parts of
`feature/search/query/` outside the `PORTED` table above.

## Dependencies

Resolved from the Gradle module cache on 2026-10-05 (842 modules declare Apache-2.0,
128 `Apache-2.0`, 20 MIT, 9 BSD-3-Clause; the remainder are Eclipse, Public Domain and
Android-specific licences that do not appear in the shipped graph).

### Permissive — no action

Kotlin 2.4, Compose Multiplatform 1.12, Room 3, Koin 4.2.2, Kermit 2.1, Koog 1.1.1,
kotlinx-coroutines / serialization / datetime, Ktor, OkHttp, Okio, Coil 3,
FileKit, multiplatform-markdown-renderer, Robolectric, Konsist, Turbine, Kotest.
Apache-2.0 or MIT. `ai.koog:*` ships no `<licenses>` block in its POMs; Koog is
Apache-2.0 per its repository, and it is the one dependency whose licence is asserted
from outside the artefact metadata. Recorded here rather than treated as verified.

### Resolved — moved to the FSL catalogue

| Dependency | Declared licence | Verdict |
|---|---|---|
| `ru.ok.tracer:*` (7 artefacts) | `Tracer's License Agreement` — <https://apptracer.ru/license/> | **Not OSI-approved. © VK.** A proprietary, service-bound licence. |

`tracer-crash-report:1.4.0` was a direct `implementation` dependency of both `:shared`
(androidMain) and `:androidApp`, wired through the Gradle plugin `ru.ok.tracer`, and it
was the only live consumer of `CrashReportingPort` on Android. Publishing the core under
Apache-2.0 while shipping this in the same build was not consistent. The plan did not
anticipate this dependency.

**Resolved on 2026-10-05 by moving the SDK into `pro/` (FSL-1.1-ALv2).** What it cost
and what it bought:

| | Free build | Pro build |
|---|---|---|
| `CrashReportingPort` | `FileCrashReportingPort` — the rolling Kermit log | `TracerCrashReportingPort` — AppTracer |
| `Application` | `com.singularity.todo.SingularityApp` | `com.singularity.todo.pro.ProSingularityApp` |
| `ru.ok.tracer` in the dex | **0 occurrences** | 347 string references |

**What is actually lost, stated plainly:** the network. A crash still reaches the log file
the user already owns and can export, but nothing is collected on their behalf without
asking. For a local-first app that is a defensible default rather than a placeholder, and
it is the deliberate price of an Apache-2.0 core — not a reduction that should be
described as cosmetic.

The SDK was already confined to **two** production files behind `CrashReportingPort`, with
a JVM no-op on the other platform, so this was a file move rather than a redesign. What
made it slightly less trivial: the vendor requires the `Application` to implement
`HasTracerConfiguration`, and the SDK type-checks for it. A `if (withPro)` branch inside
the Apache-2.0 class would have put a vendor type in an Apache-2.0 *file* even though the
branch never executes, so the `Application` is split into a base class and a pro subclass,
selected through an `appClass` manifest placeholder.

`scripts/check-pro-licence-boundary.py` keeps the split real, and `--verify-apk` reads the
built dex — because every other rule is static analysis and all of them could be true
while a transitive dependency put the vendor classes in the free APK anyway.

## Dead reference found by this audit

`core/database/Migrations.kt:62` cites *"the sync-orgzly-adoption ADR"*. No such ADR
exists in `docs/decisions/` or its archive. The migration is `v15 → v16`, adding
`remote_configs`. The comment is corrected to name what the design actually is — a
single-remote configuration, a plain statement about this project's own schema.
`check-adr-references.py` did not catch it because the reference is a bare slug with
no date, and that check only resolves dated tokens.

## What was checked, and what was not

Checked:

- Every `.kt` file in `shared/`, `androidApp/`, `desktopApp/`, `mcp-server/`,
  `detekt-rules/` scanned for derivation markers, with `file:line` captured.
- Every `.md` under `docs/decisions/` scanned for the same markers.
- `git log --follow` and `git log --diff-filter=A` on the flagged paths.
- Licence metadata for every module in the resolved Gradle cache.
- Author addresses across all refs.

Not checked, and stated as limits rather than implied clean:

- **No plagiarism detection against Orgzly or Tasks.org source was run.** The
  `PORTED` classification rests on the project's own markers and ADRs plus review of
  the flagged files. A tool comparison against both upstreams would be the next
  evidence to gather, and it is the check that would turn "marked as lifted" into
  "measured as overlapping".
- **The ~1 323 unmarked `.kt` files are `ORIGINAL` by absence of evidence.** Absence of
  a marker is not a clean-room result; it is the absence of a note. This registry
  claims only that no derivation was *recorded*, and `check-provenance.py` enforces
  that a new marker can never again be added without a registry row.
