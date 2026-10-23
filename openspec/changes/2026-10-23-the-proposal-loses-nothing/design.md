# The proposal loses nothing — Design

## The shape of the problem

The proposal lifecycle made three promises: a change waits for a decision, a
change can be turned down, and a change that is applied is applied once. The
follow-ups are the places where one of the three could still be broken by
something the user never sees.

There are three ways a change disappears from the user's point of view, and
this work closes one of them in each way:

| The change disappears because | Closed by |
|---|---|
| the write committed and the row could not be addressed | the id is named before the write |
| the build could not read the row | `ProposalItemKind.Unknown` |
| the surface decided what the row meant | the domain answers, and a gate says so |

## The write path

The old shape:

```kotlin
writeAllOrNone(items, write = { write(it) }, takeBack = { takeBack(it) })
```

where `write` *returned* the id. The id of a row was therefore known only after
the row was written — and the write that is cut short is the one whose id never
arrives. The compensation existed, and on the path it exists for it had nothing
to compensate.

The new shape:

```kotlin
class ProposedRow<out Id>(
    val id: Id,                                   // known before the write
    val write: suspend () -> Unit,
    val takeBack: (suspend () -> Result<*>)?,     // null when the write cannot be undone
    val isGone: (suspend () -> Boolean)?,         // asked only after a failed take-back
)
```

`rowsOf(plan, userId)` is the table of writers: one place that says what each
plan does, and every row it writes carries the id the row will have. The
dispatch has one application point — `writeAllOrNone` — and every plan goes
through it.

**The take-back tolerates absence.** A write that failed *before* it created
the row leaves nothing to take back, and "the row is not there" is what the
take-back wanted. So the take-back asks the row whether it is gone, and treats
"gone" as success. Without that, every failed write would be reported as a row
that stayed — a row the user would be told about that is not there.

**The update plans have no take-back.** Setting a field again is the same
setting, so a retry of an update needs no undo; and a take-back that wrote the
previous state back would overwrite whatever the user did in the meantime. The
creation plans have one — the id is known — and the collection plans have one
per row.

## The claim

The claim is a transaction, and what it decided was known by a flag in the
caller. A cancellation that arrived between the commit and the return left the
claim standing: the change was `Confirmed`, nothing was written, and the user
could not press "apply" again.

Now the flag is set *inside* the non-cancellable block that holds the claim, so
the fact "the claim is standing" is known at the same moment the transaction
decided it:

```kotlin
val claimedItem = withContext(NonCancellable) {
    val item = proposals.claim(itemId, Confirmed, DecidedActor.User, null, userId)
    if (item != null) claimed = true
    item
}
```

A cancellation that arrives while the claim is in flight no longer interrupts
it: the claim completes, the flag is set, and the `finally` gives the claim
back — which is right, because nothing was written.

## The codec

The `kind_json` column is written by the model's own serializer now:

```kotlin
internal fun ProposalItemKind.toJsonString(): String =
    payloadJson.encodeToString(ProposalItemKind.serializer(), this)
```

The reader tries the sealed format first and the flat format second. A flat blob
cannot be read as a sealed one by accident: every sealed variant carries a
payload the flat format does not have — a nested object for a value, an array
for a list, a nested object for a created thing — and a flat blob has a string
where the sealed one has that structure. The two shapes that coincide (the
deletions and the unknown kind) carry the same value either way.

A blob that neither reader understands becomes `ProposalItemKind.Unknown`, which
carries the raw blob so that it can be written back unchanged.

## The surfaces

The domain answers the questions the surfaces were answering:

| The surface asked | The domain answers |
|---|---|
| `when (item.status)` → "Turned down" | `item.outcomeLabel` |
| `ProposalItem(status = Pending)` | `ProposalItem.pending(...)` |
| `items.any { it.status == Pending }` | `proposal.pendingItems` |

And the reasons live in one store over a flow, which the surfaces mirror into
their own state — the state a surface draws from is rebuilt from the data the
screen was given, and a reason kept only in that state was lost on every
rebuild.

The arch gate says so: no file under a `presentation` package may name
`ProposalItemStatus`, and only `ProposalDecisionStyles` may declare a
`ProposalDecisionStyle`. The third test in that gate asserts that the domain
really does answer, so the gate cannot pass for the wrong reason.

## The harness

The harness used to hand the test two scopes and let the test choose. Now the
test names the *kind* of ViewModel it is testing, and the harness builds the
scope that kind needs:

- `VmScope.Foreground` — the ViewModel's work is the test's work. The job hangs
  off the background scope's job, so the work stops when the test ends and the
  test does not have to remember to cancel anything.
- `VmScope.Background` — the ViewModel's collector never ends *and* keeps
  producing work, so it must not hold the test open.
