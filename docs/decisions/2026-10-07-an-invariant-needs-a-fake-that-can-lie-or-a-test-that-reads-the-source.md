---
title: An invariant needs a fake that can lie, or a test that reads the source
date: 2026-10-07
status: accepted
---

## Context

The unit of work (`2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`) has two
separate claims, and only one of them was ever tested.

1. **The port rolls back.** `UnitOfWorkIsAtomicTest` proves it against a real SQLite
   database: two writes, a throw between them, neither survives. That is the only place
   the property is observable, and it is green.

2. **Every repository write uses the port.** Thirty-seven methods across six repositories
   were wrapped by hand. This was never proved, and it was already false in two places:
   `ProjectsRepositoryImpl.setParent` and `.setSortOrder` wrote the row and enqueued the
   patch with no transaction around them.

The second claim could not be proved by the tests that exist, and no new test of the same
shape would have proved it. Every repository test is constructed with `FakeUnitOfWork`,
which **runs the block and does not roll back** — that is what makes it a useful fast
double, and it is exactly why it cannot testify to atomicity. A method that dropped its
`unitOfWork.write { … }` wrapper would have kept every repository test green, forever, and
would have reintroduced the precise row-without-patch split the ADR exists to prevent.

## Idea

1. **Make `FakeUnitOfWork` transactional.** Give it an undo log; have every DAO fake
   honour rollback.
2. **Assert the wrapper exists per method.** Write one test per repository method that
   asserts `unitOfWork.opens == 1`. The fake already counts opens.
3. **Check the source.** A static rule over `*RepositoryImpl.kt` that reports any patch
   enqueue not lexically inside `unitOfWork.write { … }`.

## Decision

**Option 3, and keep option 2's counter for the diagnostic it already provides.**

Option 1 is the one that sounds right and is wrong here. The DAOs being faked are the
thing the transaction exists to protect: if the fake's writes are not themselves
transactional, the fake cannot roll them back without becoming a second database. Turning
`FakeUnitOfWork` transactional means re-implementing Room's write isolation across
roughly forty DAO fakes, and every one of them would then be a *second* implementation
whose bugs are not caught by Room's. The double would grow faster than the code it
doubles.

Option 2 is real but unbounded. It needs a test method per repository method — thirty-seven
tests that assert a counter, each of which fails to compile-meaningfully if the method is
renamed, and each of which a future refactor must remember to add. The cost is linear in
the code base and the coverage is per-name, so it silently loses methods.

Option 3 is a single test over the whole set. The rule cannot miss a method because it
does not enumerate methods; it walks every file it is pointed at. Its cost is one file.

**The honest limitation is stated in the test's own KDoc:** this is a lexical check, not a
semantic one. It proves an enqueue appears inside `unitOfWork.write { … }` textually, not
that the resulting code is atomic. It is a ratchet against a specific, repeated, easy
regression — hand-applied wrappers being lost in a later refactor — not a proof. Claiming
more would be the same over-claim as the fake.

## Consequences

- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SyncWriteIsAtomicTest.kt` is the
  enforcement point. It reads `*/data/*RepositoryImpl.kt` and reports every enqueue
  outside a write block, with file and line.
- The rule needs to know which enqueues are *writes*. Two shapes exist: inline
  `syncRepository.enqueue(item)`, and calls to the private `enqueueFresh(id)` helper that
  reads the row back before enqueueing. The helper's own body is exempt and its callers
  are not — that asymmetry is why `enqueueFresh` calls are counted separately rather than
  the whole helper file being skipped. Getting that wrong silences five real files.
- **The rule found two real bugs within hours of existing** (`setParent`, `setSortOrder`),
  both of which had shipped. Neither was in the file the wrappers were applied to by hand.
- `SyncEngineTakesNoFeatureTypesTest` applies the same idea to a decision that had no
  enforcement at all: `SyncEngine` must not name a `com.singularity.todo.feature` type,
  because the cycle it closed is re-opened the moment a second one appears (see
  `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`).
  A `() -> SyncDocumentWriter` provider is the sanctioned door and passes; a direct
  `SyncDocumentWriter` parameter does not.
- Each of these tests carries a synthetic control case — a string parsed by the same code
  path — so a broken *rule* is distinguishable from a broken *codebase*. A rule that
  silently stops matching reports green, which is worse than red.

## Links

- `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it` — the invariant this test
  makes checkable.
- `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine` —
  the cycle the second test keeps closed.
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeUnitOfWork.kt` —
  the pass-through fake that makes the gap, by design.