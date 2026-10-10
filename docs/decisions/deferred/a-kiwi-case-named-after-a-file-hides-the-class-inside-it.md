---
title: "A Kiwi Case Named After A File Hides The Class Inside It"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-05, while fixing the tag gate above.

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** #147

**Symptom.** `sync.py` derives a Kiwi case id from the *file path*:
`NoopSubscriptionProviderTest.kt` becomes the case `NoopSubscriptionProviderTest`, but the
class the file declares is `PurchaseStateTest`. Three files are affected:

| File | Class(es) actually declared |
|---|---|
| `core/billing/NoopSubscriptionProviderTest.kt` | `PurchaseStateTest` |
| `core/sync/TaskToJsonProbeTest.kt` | `TaskSyncSerializationTest` |
| `core/observability/CrashReportingTest.kt` | 7 test classes |

JUnit reports results by class name, so these cases can never be matched to a run: the
binding is by FQN, and the FQN Kiwi recorded does not exist as a class. They sit in
"never run" forever, indistinguishable from a real gap.

**Partial fix applied 2026-10-05.** The file is no longer dropped entirely — the previous
filter looked for a test member *under the class named like the file*, found none, and
excluded the file, losing a genuine test. The scan now accepts a file when **any**
concrete test class is declared in it (260 cases instead of 259), and reads the tag from
the class that matches. The consequence is a visible `junit_tag=untagged` on a case whose
name does not match any class — an honest signal rather than a silent miss.

**Try next.** Make the case id follow the *class*, not the file. That is the correct
direction, and it is deliberately not done in the same change: `source_path` is the
idempotency key for `sync.py --plan`, so re-keying orphans the 259 cases already in a
local stand and needs a migration. Options, cheapest first:

1. Re-key to `source_path#ClassName` and let `gaps.py` report the old cases as orphans
   (that report already exists and deliberately does not delete).
2. Keep the file-based id, and add a `declared_class` property so a case records which
   class it stands for — no migration, and the mismatch becomes queryable.

Option 2 is the smaller change and loses nothing today; option 1 is the destination.
A third possibility — renaming the three files to match their classes — was rejected: it
touches unrelated source to make a database field line up, and Kotlin does not require
the two to agree.
