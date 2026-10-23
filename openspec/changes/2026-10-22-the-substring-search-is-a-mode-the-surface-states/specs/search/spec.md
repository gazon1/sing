# The substring search is a mode the surface states — Observable Behavior

**capability:** `search` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-SEARCH-013

A search surface **SHALL** state the mode the free text is matched in, and
**MUST NOT** let a person search by occurrence without having been told that
this is what they are doing.

> The word mode finds a word by its beginning — "arch" finds "architecture" —
> and the contains mode finds the term wherever it occurs. The two are
> different questions, and a search that asked one of them without saying so
> would answer differently from one screen to another.

#### Scenario: The mode is visible where the query is written
- A person opens the search screen
- The mode is shown next to the field the query is written in
- The mode shown is the word mode, which is the default

#### Scenario: The contains mode says what it costs
- The contains mode is chosen
- The surface says that this mode reads every record, and that it is slower
  than the word search

---

### Requirement: REQ-SEARCH-014

In the contains mode, a term **SHALL** match a record whose searchable text
carries the term anywhere, and the same rule **SHALL** apply to a task, a
note, a project and a tag.

> The rule of the mode is one rule for every kind of record. A search that
> found a task by occurrence and missed a note carrying the same text would
> answer differently depending on what it was looking for — and the person
> would not know which answer they got.

#### Scenario: The term is found in the middle of a word
- A record's title holds the word "airport"
- A search for "port" is made in the contains mode
- The record is returned

#### Scenario: Every kind of record is matched the same way
- A task, a note, a project and a tag all carry the word "airport"
- A search for "port" is made in the contains mode
- All four records are returned

#### Scenario: The negated term follows the mode's rule
- A record's title holds the word "airport"
- A search for "NOT port" is made in the contains mode
- The record is not returned

#### Scenario: The word mode is not changed by the escape-hatch
- A record's title holds the word "airport"
- A search for "port" is made in the word mode
- The record is not returned

---

### Requirement: REQ-SEARCH-015

The mode **SHALL** change the rule for every term of the query at once, and a
query **SHALL NOT** carry a marker that asks one term to be matched one way
and another term another way.

> One query asks one question. A marker in the query would let a query mix
> the two rules, and the `*` is the index's own prefix syntax — a marker that
> looks like the index's syntax invites the reader to believe the query means
> what it looks like.

#### Scenario: A query means the same thing wherever it is written down
- A query of "port" is written in the search screen, and the same query is
  saved
- The saved query is read later
- The saved query carries no marker, and the mode that answered it is the
  mode the surface stated at the time
