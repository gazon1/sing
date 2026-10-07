# Inert Control Surfaces — Observable Behavior

**capability:** `inert-surfaces` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-INERT-1

A control that cannot act SHALL NOT be presented as one that can.

#### Scenario: The switch that ignores taps
- A setting exists and is stored, but nothing consumes the value
- A switch renders as live and accepts a tap
- The user sees a preference that changes and no behaviour that follows

#### Scenario: The accepted form
- The parameter carrying the action is nullable
- A row with no handler drops its click target rather than ignoring the callback
- The control is visible, disabled, and explains itself

#### Scenario: The row that is not rendered
- A create screen offers "Attach file" for an entity that has no id yet
- Tapping it could not attach anything
- The control is omitted instead of shown

---

### Requirement: REQ-INERT-2

A gate that checks for empty click handlers SHALL key on the shape of the parameter, not on
a list of names.

#### Scenario: A new handler name appears
- Code introduces `onDismissDialog`
- A name-based allow-list does not know it, and the finding is invisible until someone
  remembers to add the name

#### Scenario: The shape-based rule
- A parameter named `on` followed by a capital letter is treated as a handler
- A near miss such as `onResult` or `onSuccessful` is a handler, because it is one

#### Scenario: A near miss is a handler
- `onResult` carries a handler, not an empty placeholder
- Exemptions must therefore be narrow and justified in the source

---

### Requirement: REQ-INERT-3

An exempt handler SHALL be exempt for a stated reason, and the reason SHALL be testable.

#### Scenario: A `Result.fold` label
- `onSuccess` and `onFailure` name the branch, they do not carry a handler
- They are exempt because an empty branch label is idiomatic, not because they are
  convenient

#### Scenario: A read-only field
- An empty `onValueChange` next to `readOnly = true` in the same call is a field that
  cannot be edited
- It is exempt because the read-only is in the same expression, and the exemption checks
  for it there

---

### Requirement: REQ-INERT-4

A pre-existing suppression SHALL NOT be carried forward when the underlying defect is
fixed.

#### Scenario: Fixing a suppressed finding
- The rule fires on a file and the finding is in the baseline
- The code is corrected and the baseline line is deleted
- Regenerating the baseline is not acceptable: the suppression is the reason the defect
  survived

#### Scenario: A preview that needs a handler
- A preview renders real content with fixed state
- It passes the project's `noopClick` rather than an inline empty lambda
- The finding disappears because the code says what it means, not because a list grew

---

### Requirement: REQ-INERT-5

A binding defined twice SHALL be reported, not silently overridden.

#### Scenario: Two modules bind the same type
- `appearanceSettingsModule()` and `settingsContributorsModule()` both bind the appearance
  contributor
- Both construct the same class from the same dependencies, so resolution succeeds
- The second definition silently replaces the first

#### Scenario: Why nothing caught it
- Graph validation asks "does this resolve?", not "is this defined twice?"
- Both answers were yes

#### Scenario: The maintenance hazard
- Deleting the binding that happens to win looks like a no-op
- Deleting the shadowed one looks like a cleanup until the other goes too
- One definition, in the module that owns the type
