---
title: An atomicity rule and an enqueue rule answer different questions, and neither sees the other
date: 2026-10-07
status: accepted
---

## Context

The unit-of-work ADR (`2026-10-05-who-owns-a-row-and-the-patch-that-describes-it`) makes a
claim with two halves, and for a long time only one half was checkable:

1. The **port** rolls back — proved by `UnitOfWorkIsAtomicTest` against real SQLite.
2. Every **repository write** uses the port — was never proved, and was already false in two
   places, both of which shipped.

Part 2 is now `SyncWriteIsAtomicTest`, and finding it immediately produced a question the
fix did not answer: it checks that the patch is inside the transaction *as* the row. It
cannot check that there *is* a patch. Those are different questions, and for a long time
only one of them had a rule.

The unchecked one is the more expensive bug. A method that writes a row and forgets to
enqueue looks exactly like a method that writes a row and deliberately does not: a DAO call
and no `syncRepository.enqueue`. Nothing in the repository told them apart — which is what
#187 was, time tracking on one platform and not the other, with every suite green.

## Idea

Add the reverse rule: a method that writes a synced table must enqueue. The obvious
obstacle is that legitimate exemptions exist — the **remote apply handlers** write a row
*because* the server said so, and enqueueing would push the same document straight back.
So the rule needs an exemption list.

## Decision

**Derive the exemption set from `SyncDocumentWriter.upsert`, and list the two remaining
cases individually with the reason attached to each.**

## Rationale

The exemptions are not a matter of taste. A repository's `upsert(entity)` is not exempt
because it is named `upsert`; it is exempt because `SyncDocumentWriter` calls it to apply a
document that arrived from the server. That is a fact about one file, and it is already
written down in that file. A second list of method names would be a copy of it that nobody
would update — and this repository has now produced three of those in one session:

| The list | It mirrors | How it broke |
|---|---|---|
| five names in `test_kiwi_sync.py` | the scanner's own bookkeeping | `7582a27f` |
| `desktopPlatformModule()` | `PlatformModule.jvm.kt` | `dc7f1d5d` lost five bindings |
| the fingerprint header | the function bodies beside it | unchecked until today |

The pattern is the point: **a list beside a fact goes stale silently, and the tree it goes
stale on reports green.** Three instances in one session is not a coincidence to be
documented, it is a shape to be designed against. Where a fact has a home, read it there.

Two writes are left over and named individually, because they are genuinely not synced
columns — but both reasons were wrong, and reading the server's allowlist is what showed
it. `setInheritedForProject` was described as "a flag denormalised from `parentId`"; it
writes rows in the `inherited_tag_groups` join table and touches no project row. And
`saveOutgoingLinks` was described as writing links "no `DocType` describes", which is
true of `tasks.outgoing_links` and false of `note.outgoingLinks` — one method name over
two opposite answers. Verified against the live project 2026-10-07: `outgoingLinks` is
absent from the allowlist for `task` and present for `note`.

Each carries its reason on the entry rather than in a comment above the list, because a
reason separated from its entry is a reason nobody reads when the entry is questioned.

**`setInheritedForProject` has since left the list entirely** (#228). It now stamps the
project and pushes it, so the ordinary rule covers it — which is the outcome worth
recording: the exemption was not a legitimate exception, it was a method that had never
been given a patch.

## Consequences

- `SyncedWriteEnqueuesTest` and `SyncWriteIsAtomicTest` are two halves of one invariant and
  neither subsumes the other. Keeping them in separate files is what makes that visible; the
  KDoc of each names the other.
- The reverse rule is lexical: it sees a DAO call whose name starts with a write verb and an
  `enqueue` somewhere in the same method. A write performed by a helper with an
  unrecognisable name, or a field that becomes synced later without the method changing, will
  pass. It is a ratchet against losing an enqueue in a refactor — which is how both real
  instances arrived — and not a proof.
- Adding a `DocType` adds a repository to `SyncDocumentWriter.upsert` and therefore extends
  the exemption set automatically. That is the intended consequence: the new repository's
  `upsert` is exempt because the writer really does use it as an apply handler, and its
  other write methods are checked from the first commit.

## Links

- `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it` — the invariant.
- `2026-10-07-an-invariant-needs-a-fake-that-can-lie-or-a-test-that-reads-the-source` —
  why these are static rules at all.
- `2026-10-07-a-test-mirror-cannot-be-derived-only-its-declarations-can` — the same shape
  one level up, and the case where derivation genuinely does not apply.