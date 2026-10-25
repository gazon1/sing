# text-input Specification

## Purpose
Every text field's source and the contract of who may rewrite it: the
source is static or owned, the count of the replacements that came from
outside travels with the value, and the shape of a field is derived from
the code rather than read at runtime.

## Requirements

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

### Requirement: REQ-TI-REWRITE-1

A bridged text field SHALL be handed a **source**, and the source's type SHALL
say who may replace the field's value.

The bridge SHALL offer exactly two shapes of source, and they SHALL be
unrelated types:

- a **static** source, declaring that the upstream replaces the value only with
  one the field never produced — it loads the value once, or clears it;
- an **owned** source, declaring that the upstream owns the value and may
  replace it with anything, including a value the field produced earlier, and
  carrying the count of the replacements.

A field SHALL NOT be able to express a third answer by accident: adding a shape
of source SHALL require the bridge to say what that shape means.

#### Scenario: A static upstream

- **Given** a field whose upstream only loads or clears the value
- **When** the upstream replaces the value
- **Then** the field takes the value, and no count is involved

#### Scenario: An owned upstream

- **Given** a field whose upstream owns the value
- **When** the upstream replaces the value with one the field produced earlier
- **And** the count of the replacements has moved
- **Then** the field takes the value

#### Scenario: An owned upstream that has not moved its count

- **Given** a field whose upstream owns the value
- **When** the upstream hands back a value the field produced earlier
- **And** the count of the replacements has not moved
- **Then** the field keeps its own text — the lagging upstream is not applied

### Requirement: REQ-TI-REWRITE-2

The bridge SHALL NOT take a count of replacements as an argument.

A call site SHALL NOT be able to freeze the count, to omit it, or to leave it
to a default. The count SHALL be a property of the source, and the source
SHALL be required.

#### Scenario: A call site cannot write a count down

- **Given** the bridge's public declarations
- **When** their parameters are read
- **Then** none of them is a count of replacements

#### Scenario: A call site cannot omit the answer

- **Given** a bridged field
- **When** the field is wired
- **Then** it is handed a source, whose type says who may rewrite it

### Requirement: REQ-TI-REWRITE-3

The **owner** of a value SHALL build the source, and a field SHALL pass it
through.

The owner is the component that holds the value and knows who else writes it. A
field that built its own source would be answering a question it cannot know the
answer to, and it would answer it wrongly in the direction that loses the
user's work.

#### Scenario: A field that builds its own source

- **Given** a bridged field site
- **When** the source it hands the bridge is inspected
- **Then** it is a source it received, not one it constructed

### Requirement: REQ-TI-REWRITE-4

The rewrite contract of every bridged field site SHALL be published, and the
publication SHALL be checked against the code.

The publication SHALL name, per site: the fields it owns, the upstream that may
write them, the kind of source the site is handed, and — for an owned source —
the file that declares the count and the file that advances it.

The check SHALL fail when:

- a site that owns a bridged field is missing from the publication;
- the owner does not declare a source of the claimed kind;
- an owned source's count is not declared, or is never advanced;
- a field site builds its own source;
- the publication and the code disagree.

#### Scenario: A site missing from the publication

- **Given** a file that calls the bridged helper
- **When** it is missing from the publication
- **Then** the check fails

#### Scenario: An owner that declares the wrong kind

- **Given** a site whose publication claims an owned source
- **When** the owner declares a static source
- **Then** the check fails

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** the file that advances the count never advances it
- **Then** the check fails

#### Scenario: The publication and the code agree

- **Given** a site whose publication matches the code
- **When** the check runs
- **Then** it passes

### Requirement: REQ-TI-SHAPE-1

The shape of a text field SHALL be derived from the code that owns it, and the
derivation SHALL NOT read the code's text.

A file's shape SHALL be determined by the symbols it **imports**:

- an import of the bridged helper means the file is *bridged*;
- an import of the anchored helper means the file is *anchored*;
- an import of the local state holder means the file is *local*.

A mention of a helper in a comment or in documentation SHALL NOT classify a file, and
a file that owns a field SHALL NOT be able to hide behind such a mention.

#### Scenario: A file that owns a bridged field

- **Given** a file that uses the bridged helper
- **When** the shape is derived
- **Then** the file is *bridged*, because it imports the helper

#### Scenario: A file that only mentions a helper

- **Given** a file whose documentation names the bridged helper
- **And** which does not import it
- **When** the shape is derived
- **Then** the file has no shape — it owns no field

### Requirement: REQ-TI-SHAPE-2

The helpers that decide a field's shape SHALL live in a package that holds no fields.

A file SHALL NOT be able to use a helper without importing it, so that the import is
always the evidence of the shape.

#### Scenario: A field in another package

- **Given** a file that owns a text field
- **When** it calls the bridged helper
- **Then** it imports the helper, and the import names the shape

#### Scenario: The helpers' own package

- **Given** the package that holds the helpers
- **When** its files are scanned for field sites
- **Then** they are excluded by their path, and the package holds no fields

### Requirement: REQ-TI-SHAPE-3

The rewrite contract of a bridged field site SHALL be checked against the code's

structure.

Where a check cannot be expressed structurally, it SHALL be read from the

**declarations** of the file with the comments stripped, and a preview SHALL be

excluded by its annotation.

The sites SHALL be the files that import the bridged helper, the owner's source SHALL be a property with a type **or** a source the owner's function hands out, the counter SHALL be a property with a name, and the bridge's public parameters SHALL be names.

The counter SHALL be a value that carries its own count, or a plain count of the upstream's writes; and the count SHALL advance through the operation that moves it, or by incrementing the plain count.

A **static** row's owner SHALL declare a value whose writes are all named, and the source it hands the field SHALL be built from that value.

An **owned** row's counter SHALL count the replacements of the value the row is about, and SHALL move only when that value was replaced from outside.

#### Scenario: A static owner that holds a bare string

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds no value with named writes
- **Then** the check fails

#### Scenario: A static owner that holds a value with named writes

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds one
- **Then** the check passes

#### Scenario: A counter that counts another field's replacements

- **Given** a site whose publication claims an owned source
- **When** the count that the field reads moves for a value other than the one the row is about
- **Then** the check fails

#### Scenario: A site that is not in the publication

- **Given** a file that imports the bridged helper
- **When** it is missing from the publication
- **Then** the check fails

#### Scenario: An owner that declares the wrong kind

- **Given** a site whose publication claims an owned source
- **When** the owner's property declares a static source
- **Then** the check fails

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** no declaration of the file that advances the count advances it
- **Then** the check fails

#### Scenario: A comment that talks about a count

- **Given** a file whose documentation mentions a count that moves
- **And** no declaration of it advances the count
- **Then** the check fails

#### Scenario: An owner that hands the source out

- **Given** a site whose publication claims an owned source
- **When** the owner's function returns the owned source
- **Then** the check passes

#### Scenario: A counter that is declared

- **Given** a site whose publication claims an owned source
- **When** the file that owns the counter declares no count
- **Then** the check fails

#### Scenario: A counter that never advances

- **Given** a site whose publication claims an owned source
- **When** nothing in the file that advances the counter moves it
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-1

The text-field contract SHALL be published in exactly one place, and the tests that
check it SHALL read that place.

A test SHALL NOT hold a second copy of the contract.

#### Scenario: A field that is not classified

- **Given** a file that owns a text field
- **When** it is missing from the published table
- **Then** the check fails

#### Scenario: A classification that does not match the code

- **Given** a row that claims *local* for a file
- **When** the file imports the bridged helper
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-2

The published rewrite table SHALL name the file that builds the source, and a row
without an owner SHALL fail the check.

#### Scenario: An owner that declares the wrong kind

- **Given** a row that claims an owned source
- **When** the owner's property declares a static source
- **Then** the check fails

#### Scenario: A row without an owner

- **Given** a row with no owner
- **When** the contract is checked
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-3

A row that names a file which no longer owns a field SHALL fail the check.

A stale row is a contract with nobody: it describes a file that has moved on.

#### Scenario: A row for a file that no longer owns a field

- **Given** a row in the inventory for a file that owns no text field
- **When** the contract is checked
- **Then** the check fails

#### Scenario: A row for a file that no longer owns a bridged field

- **Given** a row in the rewrite table for a file that owns no bridged text field
- **When** the contract is checked
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-4

A published table SHALL be read by its header.

A reader SHALL NOT read a row of another table, and a table whose header changed SHALL
fail loudly rather than yield no rows.

#### Scenario: Two tables in one document

- **Given** a document that holds several tables
- **When** a table is read by its header
- **Then** only the rows of that table are returned

#### Scenario: A header that changed

- **Given** a table whose header is not the one the reader looks for
- **When** the reader looks for it
- **Then** the read fails, naming the header it looked for

### Requirement: REQ-TI-GENUI-SOURCE-1

The data context SHALL hand out the source of a field bound to a data-model path.

The source SHALL carry the value at the path and the count of the server's writes, and
the two SHALL arrive together.

#### Scenario: A field bound to a path

- **Given** a surface with a field bound to a path
- **When** the field is composed
- **Then** it is handed a source, and the source carries the server's count

#### Scenario: A value and a count from two different moments

- **Given** a server write that lands while the field is composed
- **When** the field is handed its source
- **Then** the value and the count in it belong to the same write

### Requirement: REQ-TI-GENUI-SOURCE-2

A server rewrite of a bound field SHALL replace the field's text, even when the value is
one the field itself sent.

#### Scenario: A rewrite onto a value the field sent

- **Given** a field that sent the value `seedx` and then shows `seedxy`
- **When** the server rewrites the field to `seedx`
- **Then** the field shows `seedx`

#### Scenario: A frozen count

- **Given** a field whose source carries a count that never moves
- **When** the server rewrites the field to a value the field sent
- **Then** the rewrite is an echo, and the field keeps the text the user typed

### Requirement: REQ-TI-REVISIONED-1

The count of a value's replacements SHALL travel inside the value.

A value that carries its count SHALL offer exactly two ways to change: one that leaves
the count where it was, and one that moves it. There SHALL be no way to make a value
whose count does not match it.

#### Scenario: A keystroke

- **Given** a value that carries a count
- **When** the field's own change is applied to it
- **Then** the value changes and the count does not

#### Scenario: A replacement from outside

- **Given** a value that carries a count
- **When** the upstream replaces it
- **Then** the value changes and the count moves

#### Scenario: A count that does not match its value

- **Given** the type that carries a value and its count
- **When** a caller tries to make one with an arbitrary count
- **Then** it cannot — the only ways to make one say what kind of change it is

### Requirement: REQ-TI-REVISIONED-2

An owner of a bridged field SHALL hold the value and its count in one object, and the
source it hands the field SHALL be built from the two together.

#### Scenario: The owner of a task's title

- **Given** a task draft
- **When** the task is rewritten from outside
- **Then** the draft's title is replaced and its count moves

#### Scenario: The owner of a task's title, typed into

- **Given** a task draft
- **When** the user types in the title
- **Then** the draft's title changes and its count does not

#### Scenario: The source the field is handed

- **Given** a draft that holds a title
- **When** the field is handed the source
- **Then** the source carries the title and the count of its replacements

### Requirement: REQ-TI-FIELD-OWNED-1

A value that the field alone writes SHALL offer exactly three ways to change: the
field's own text, a value the upstream loaded, and an emptying.

There SHALL be no way to make such a value other than by one of those three, and there
SHALL be no way to write it without naming the write.

#### Scenario: The field's own text

- **Given** a value that the field alone writes
- **When** the field's text comes back
- **Then** the value holds it, and the write says it is an echo

#### Scenario: A value the upstream loaded

- **Given** a value that the field alone writes
- **When** the upstream loads a value into it
- **Then** the value holds it, and the write says it is a seed

#### Scenario: An emptying

- **Given** a value that the field alone writes
- **When** the upstream empties it
- **Then** the value holds nothing, and the write says it is a clear

#### Scenario: A value with an unnamed count

- **Given** the type whose writes are all named
- **When** a caller tries to make one with a value of its own
- **Then** it cannot — the only ways to make one name the write

### Requirement: REQ-TI-REVISIONED-3

A value the upstream owns SHALL count only the replacements of that value.

The count SHALL move when the value was replaced from outside, and SHALL stay where it
was when the upstream wrote the value the owner already held.

#### Scenario: A replacement of another field

- **Given** a value the upstream owns
- **When** the upstream replaces a different value of the same owner
- **Then** this value's count does not move

#### Scenario: A replacement of this value

- **Given** a value the upstream owns
- **When** the upstream replaces it with a value it did not hold
- **Then** this value's count moves

#### Scenario: A write of the value the owner already holds

- **Given** a value the upstream owns
- **When** the upstream writes the value the owner already holds
- **Then** this value's count does not move

### Requirement: REQ-TI-SOURCE-KIND-1

The kind of a field's source SHALL be a compile-time property of the parameter that carries
it.

A component that serves two kinds of upstream SHALL be split, and each half SHALL take a
source of one kind. The bridge SHALL NOT offer an overload that takes the sealed parent and
decides the kind at runtime.

#### Scenario: A component that serves two kinds

- **Given** a component that serves an upstream that loads the value and an upstream that
  owns it
- **When** its parameter carries the sealed parent of the two kinds
- **Then** the check fails

#### Scenario: A caller with the wrong kind

- **Given** an editor whose upstream owns the value
- **When** a caller hands it a source of the other kind
- **Then** the call does not compile

#### Scenario: A bridge that dispatches at runtime

- **Given** a bridge with an overload that takes the sealed parent
- **When** the overload decides which kind of source it was given
- **Then** the check fails
