# Text Input — Observable Behavior

**capability:** `text-input` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-TI-001

A text field **SHALL** keep the text the user has entered across recompositions and across
changes to the rest of the screen's state.

**Rationale:** a field that loses its text when something else on the screen changes is a field
the user has to re-type into. Recomposition is not an event the user can see, so the text
surviving it is the baseline expectation for any field.

#### Scenario: Unrelated state changes do not clear the field
- The user types a value into a text field
- Something else on the screen changes state, causing the screen to be laid out again
- The field still shows the text the user typed

#### Scenario: The field is restored after the screen is recreated
- The user types a value into a text field
- The screen is recreated while the field is still on it
- The field still shows the text the user typed

---

### Requirement: REQ-TI-002

A field whose value is held by the screen's view model **SHALL** show the rewritten value
once the view model rewrites it, and **MUST NOT** continue to show the previous value.

**Rationale:** the reason a bound field exists is that something outside the field can change
the value — a saved draft is reloaded, a title is rewritten, the model clears the field because
the item was deleted. A field that keeps showing the old value after such a change tells the
user their input was accepted when it was replaced.

#### Scenario: The view model rewrites the value
- A field shows a value that the view model holds
- The view model replaces that value with a different one
- The field shows the new value

#### Scenario: The view model clears the value
- A field shows a value that the view model holds
- The view model clears the value
- The field is empty

---

### Requirement: REQ-TI-003

Characters the user has just typed **MUST NOT** be reverted by a view model that has not yet
caught up with them.

**Rationale:** a view model that processes a change on a delay — while the user is still
typing — holds the *previous* value for a moment. A field that takes the upstream value
whenever it differs from what it shows will overwrite the newest character with the previous
one, and will keep doing so for as long as the user types faster than the view model
processes. The field has to be able to tell the difference between "the view model has not
caught up yet" and "the view model has replaced the value".

#### Scenario: A delayed view model does not undo typing
- The user types into a field whose changes are processed with a delay
- The view model still holds the value from before the most recent character
- The character the user typed is still in the field

#### Scenario: A view model that echoes an older value does not undo the newest edit
- The user types a value into a field, and the view model receives it
- The user changes the value, and the view model receives that too
- The view model hands back the first value — it is behind, not rewriting
- The field keeps the newer value the user typed

#### Scenario: A deliberate replacement still applies
- The user types into a field whose changes are processed with a delay
- The view model replaces the value with one the user did not type
- The field shows the replacement, not the user's pending text

#### Scenario: A replacement to a value the field used to hold is not applied
- The user types a value into a field, and the view model receives it
- The user changes the value, so the field no longer shows the first one
- The view model replaces the value with the first one again
- The field keeps the newer value

**This last scenario is a deliberate trade-off.** A view model that hands back a value
the field produced is either behind or rewriting, and the field cannot tell the two
apart. The case that destroys work — the view model that is behind — wins. A replacement
to a value the field never produced always applies.

---

### Requirement: REQ-TI-004

A field that accepts only certain characters **SHALL** reject the others at the point of
entry, and **MUST NOT** hold them afterwards.

**Rationale:** the rule about which characters a field accepts belongs to the field, not to
each screen that uses it. A field that filters as the user types also never shows a value the
view model does not hold, so the two cannot disagree about what was typed.

#### Scenario: A non-numeric character is not accepted
- The user types a letter into a field that accepts only digits
- The letter does not appear in the field
- The value the view model holds contains only the digits

#### Scenario: A valid character is accepted
- The user types a digit into a field that accepts only digits
- The digit appears in the field
- The value the view model holds contains the digit

---

### Requirement: REQ-TI-005

A field with a numeric range or a maximum length **SHALL** reject input outside that range at
the point of entry, and **MUST NOT** silently shorten it.

**Rationale:** a field that truncates at the limit looks like a field that lost the end of
what was typed, and the user cannot tell the difference between a limit and a bug. Rejecting
the edit leaves the field at its last valid value, which is the same behaviour the field had
before the migration, and is the one the user can interpret.

#### Scenario: A value below the range is rejected
- The user types a value that is below the minimum a field accepts
- The field keeps the value it had before that keystroke
- The value the view model holds is unchanged

#### Scenario: Text beyond the maximum length is rejected
- The user types into a field that accepts a fixed maximum number of characters
- The user types one character more than the maximum
- The field does not grow, and the value the view model holds is unchanged

---

### Requirement: REQ-TI-006

A field that holds a secret **SHALL** be masked by default, and **SHALL** reveal the secret
only in response to an explicit request by the user.

**Rationale:** a secret is displayed to someone other than its owner whenever the screen is
not in front of them. Masking by default is the only state that is safe without asking, and
revealing is a deliberate act by someone who is present. The field must be able to go back to
being masked, so that revealing it once does not leave it revealed for the rest of the
session.

#### Scenario: A secret is masked by default
- A screen opens with a field that holds a secret
- The field shows a masked placeholder rather than the secret's characters

#### Scenario: The secret is revealed on request
- The user invokes the control that reveals the secret
- The field shows the secret's characters

#### Scenario: The secret is masked again
- The secret has been revealed
- The user invokes the control that hides it
- The field shows the masked placeholder again

---

### Requirement: REQ-TI-007

A field **MUST NOT** be able to keep a value that the user has cleared.

**Rationale:** the field and the view model each hold a copy of the text. If clearing the field
does not reach the view model, the user sees an empty field and a view model that still
believes there is text — the next save writes the old text back, and the user is told their
clear was ignored.

#### Scenario: Clearing the field clears the value the view model holds
- A field shows a value that the view model holds
- The user deletes the contents of the field
- The value the view model holds is empty as well
