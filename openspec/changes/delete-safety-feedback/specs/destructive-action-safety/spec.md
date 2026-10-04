# Destructive Action Safety — Observable Behavior

**capability:** `destructive-action-safety` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-DA-001

An action that removes a single user-owned item, and whose removal affects
nothing else, **MUST** offer a way to reverse it.

**Rationale:** the application's notification guidance already prescribes this.
It is implemented in one place out of at least ten. This requirement states the
rule so the remaining sites are an implementation task rather than ten separate
decisions.

#### Scenario: A note is removed
- The user removes a note
- The user is offered a way to reverse the removal
- Taking the offer restores the note as it was

#### Scenario: Nothing is offered where reversal is impossible
- The user removes something whose removal cannot be reversed
- The user is asked to confirm first
- Declining leaves the data untouched

---

### Requirement: REQ-DA-002

A removal that cannot be reversed **MUST** require explicit confirmation, and
that confirmation **MUST** state what else is removed along with it.

**Rationale:** confirmation is a smaller promise than reversal, and a
confirmation that does not say what cascades is a confirmation the user cannot
give meaningfully. They are agreeing to something they have not been told.

#### Scenario: A cascading removal is confirmed with its consequences named
- The user requests a removal that also affects related items
- The confirmation names the related items and their count
- Confirming removes them; cancelling removes nothing

---

### Requirement: REQ-DA-003

A removal **MUST** remove immediately, and the reversal offer **MUST** remain
available for a stated interval that the user can see.

**Rationale:** a removal that waits for confirmation on every item trains the
user to confirm without reading, which defeats the confirmation on the items that
need it. But a reversal offer whose expiry is invisible is a recovery path the
user does not know they have.

#### Scenario: The remaining interval is visible
- A removal offers reversal
- The offer shows how long it remains available, and the remaining time decreases
- The offer disappears when the interval ends, and the item is then gone

---

### Requirement: REQ-DA-004

When reversal fails, the system **MUST** tell the user, and **MUST NOT** report
success or leave the offer silently dismissed.

**Rationale:** the current behavior is to clear the reversal offer before the
reversal runs, catch the failure, and show nothing. The user sees the offer
disappear and reasonably concludes the item was restored. It was not, and the
data is gone with no signal — strictly worse than not offering reversal at all,
because it also removes the expectation that anything happened.

#### Scenario: Failed reversal is reported
- The user takes the reversal offer
- The reversal does not succeed
- The user is told the item could not be restored
- The offer is not presented as having succeeded

#### Scenario: The offer survives a failed attempt long enough to be useful
- A reversal attempt fails
- The user is still able to see what happened to the item
