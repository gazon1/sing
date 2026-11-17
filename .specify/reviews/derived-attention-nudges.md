---
change: derived-attention-nudges
verdict: pass
date: 2026-11-16
pr: 2997
reviewed: 60d79826ae10792ede9ec93940898dbe8cf32839
documents: sha256:5ff35e5d8825e13ff83c688b0da49b8c7292a18f72e924096b38ded14ae01e84
---

## What was reviewed

The change that derives a shortlist of attention items from the rows the app already
holds, and shows it on the Statistics → Health tab. Issue #2980 asks for two things:
the in-app nudges, and the evaluation of OS delivery as a separate thing. This change
carries the first and writes down the second as a decision without code.

The contract under review:

- `openspec/changes/derived-attention-nudges/specs/tasks/spec.md` — REQ-TH-010..018
  (the list, one reason per item, the order, what is named, the due-soon window, the
  project deadline, opening and setting an item aside, the bounded list and its count,
  and the two readings of the Health tab as separate).
- `openspec/changes/derived-attention-nudges/proposal.md`
- `docs/decisions/2026-11-16-derived-attention-nudges-in-app-first.md` — the ADR,
  including the trigger, deduplication, expiry and invalidation contract for the
  Phase-2 OS delivery.

The code under review (`3f39c83eb2..60d79826ae`):

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/AttentionNudges.kt`
  — the derivation.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/statistics/StatisticsViewModel.kt`,
  `StatisticsScreen.kt`, `StatisticsDeps.kt` — where the list is shown, how it is
  bounded, and how an item is set aside.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/TasksDiModule.kt`,
  `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`.
- `shared/src/commonTest/.../AttentionNudgesTest.kt`,
  `shared/src/commonTest/.../AttentionNudgesAgreementTest.kt`,
  `shared/src/jvmTest/.../StatisticsViewModelTest.kt`,
  `desktopApp/src/jvmTest/.../StatisticsHealthTabFlowTest.kt` — the tests.

Four separate review rounds were held against this change: the first found ten
defects, the second six, the third three, and the fourth two. Every finding of the
first three rounds was fixed; the third round's third finding was recorded rather
than changed, and the fourth round changed it. The fourth round's findings are
dispositioned below.

## What was found, and what was done about it

**Round one — the list did not say what the contract says it says.**

- The order was wrong in the way Kotlin makes easy to get wrong: `compareBy` on a
  nullable date puts the nulls first, so an item with no date was declared the most
  urgent thing on the tab. Null dates now go last *within* their kind.
- The dismissal lived in the ViewModel, and a ViewModel outlives the screen — the
  navigation keeps the Statistics entry alive while another destination is on top of
  it, so an item the user set aside came back. The dismissal is screen-local state
  now, in plain `remember`, so it ends with the screen's visit.
- The count under the list was taken from what was left after the dismissals, so it
  counted the wrong things. It is taken from the whole list now.
- A project whose deadline is today said "its deadline has passed".
- "Today" was read in the machine's own time zone while the agenda read it through
  the injected provider. The screen reads it the same way now.
- The empty state said "nothing needs attention" for a list the user had emptied.
- The date of a further-off item was written as an ISO string.

**Round two — the fixes were right, and the tests that pinned them were not.**

- The ordering test compared two items of *different* kinds, where the kind alone
  decided the order, so it passed against the ordering it was meant to catch. It now
  compares two items of the same kind.
- The timezone fix was not pinned: the tests injected a zone whose answer the
  machine's own zone would have produced too, so reverting to
  `TimeZone.currentSystemDefault()` kept them green. The ViewModel test and the
  desktop flow test now inject `Pacific/Kiritimati` at an instant where the day
  there is already the next day — an answer the machine's zone cannot produce. Both
  were re-run against the reverted implementation and failed there: the ViewModel
  test reported `expected: <Due today> but was: <Due tomorrow>`, and the desktop test
  waited five seconds for a row that never appeared.
- The comment above the count formula argued against the formula the code used.
- The count was suppressed in the state where the user had set every shown item
  aside, though REQ-TH-017 says the list always says how many items it did not
  show. The count is rendered in every state now, and the sentence it uses there says
  "not shown" rather than "set aside" — an item can be hidden by the bound rather
  than by the user's own hand. A scenario was added to REQ-TH-017 and two desktop
  tests cover both states.
- `buildAttentionNudges` was 87 code lines against detekt's limit of 80.

**Round three — the code holds, the documentation did not.**

The third round found no clause of REQ-TH-010..017 broken, and confirmed the
ordering, the candidate set, the reason texts, the count arithmetic, the dismissal's
lifetime and the determinism of the list by reading them. It found three things:

1. *Low.* The KDoc and the ADR claimed the list "cannot disagree with the counts
   shown beside it". It can: the counts cover deferred work and work in a closed
   project, and the list names neither, so a deferred task with a past due date is
   counted as overdue by the card beside the list and not named by the list. The
   documentation now says what is true — the list asks the same rules the counts ask
   and names a subset of what they count — and the ADR says which work the difference
   covers. Fixed in the commit that follows the reviewed one; the contract did not
   move, so the digest above is the digest of the change as it stands.
2. *Low.* The Health tab's own buckets and the list can therefore be read as
   contradicting each other on the same screen. The contract of this change says the
   list does not name deferred work; what the buckets count belongs to the health
   change's own contract, so the difference is documented rather than removed.
3. *Informational.* A ready attention list is dropped when the *health* query is the
   one that failed, because the tab returns early on an unavailable health summary.
   The contract does not require the list to outlive a health failure, and the failure
   is reported as its own state, so this is recorded rather than changed.

**Round four — the two findings that were recorded, and one more.**

The fourth round was held against the change as it stood after the third round's
fixes, and it found two things worth changing. Both are in the same area: the tab
shows two readings of the same rows, and neither was free-standing.

1. *P1.* The list decided the state of a task with its own `when` over the date
   buckets and the blocking selector — a second copy of the rule the Health tab's
   counts are made with. The list now asks the health classifier the state of a
   task, and keeps only the order of the reasons as its own rule; the one place
   where that order differs from the counts' precedence is written into REQ-TH-011
   and REQ-TH-014 and pinned by a new test. `AttentionNudgesAgreementTest` drives
   the two production entry points over one fixture and asserts that they call one
   task the same thing, and pins the one case where they answer differently.
2. *P2.* The tab returned early when the health query failed, so a ready list was
   thrown off the screen by the failure of the other reading. The tab now shows
   whichever reading it has, each failure is reported in its own words, and a failed
   reading is not shown as an empty one. REQ-TH-018 states this, and the new
   desktop test — a ViewModel whose settings cannot answer the read the counts
   depend on — fails against the old tab.

The third round's third finding is therefore no longer a record of a known
limitation: it is the second finding of this round, and it is fixed.

## What was deliberately not done

- **No Phase-2 code.** The ADR writes down the trigger, the deduplication rule, the
  expiry and the invalidation for OS delivery. No notification, no scheduler, no
  worker: #2980 asks for the OS delivery to be evaluated separately, and a
  notification cannot exist before those four rules do.
- **No change to the Health tab's own buckets.** The counts and the list disagree
  about deferred work and work in a closed project. The contract of this change says
  the list does not name them; what the buckets count belongs to the health change's
  own contract, and it is not this change's business to move it.
- **No stored state.** The list is rebuilt from the rows on every open, and a
  dismissal is a statement about the shortlist for the length of one visit to the
  screen. Nothing is persisted, so there is no migration, no sync conflict and no
  backup question.
- **No new route, tab, table, repository, service or engine.** The list is derived
  where the rows are read, and it is shown on the surface that already means
  "attention".

## Verification

The fourth round was verified with:

```
./gw :shared:jvmTest --quiet -Pcoverage.enabled=false            # 4945 tests, 0 failures
./gw :shared:archTest --quiet -Pcoverage.enabled=false            # 305 tests, 0 failures
./gw :desktopApp:test -Ptest.tags=all --quiet -Pcoverage.enabled=false
                                                               # 684 tests, 1 failure
./gw :desktopApp:test -Ptest.tags=slow --tests "*StatisticsHealthTabFlowTest*"
                                                               # 6 tests, 0 failures
python3 scripts/lint-changed.py                                   # detekt + ktlint: no findings
npx --yes "@fission-ai/openspec@1.14.0" validate --all --json --strict   # 101/101 valid
easement check                                                    # 0 violations
```

The one desktop failure is `AllureRetryAttachmentTest`, a harness test that times
out with "After waiting for 1m, the test body did not run to completion" when the
machine is loaded; it was re-run in isolation and passed, and its file is not touched
by this change.

The two timezone tests were run against the reverted implementation — the one that
reads `TimeZone.currentSystemDefault()` — and failed there before they were kept.
