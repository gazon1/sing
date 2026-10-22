# Internal link grammar — Observable Behavior

**capability:** `internal-link-grammar` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-LINK-001

The app **SHALL** have exactly one definition of what an internal link is, and every
part of the app that reads, writes, matches or acts on a link **SHALL** derive its
reading from that definition.

**Rationale:** four spellings of the same grammar exist today: one that reads the
editor's markup, a second that reads the same markup and differs only in the type it
returns, one that reads free text, and one that is written as a database pattern. Each
was written where it was needed, and none was written with the others in view. The
copy that differs in behaviour is the one nobody compares, because comparing it is not
a step in any workflow.

**Test coverage:** a test that the marked-up form and the free-text form of the same
link resolve to the same reference; a rule check that no second copy of the grammar
exists outside the owner.

#### Scenario: One link, one answer
- A link to a task is written in the editor as markup
- The same link is written in free text in a task description
- Both resolve to the same reference

#### Scenario: A new surface does not invent a grammar
- A new screen renders text that may contain links
- It reads links through the shared definition
- It does not add a copy

---

### Requirement: REQ-LINK-002

The set of things a link may point at **SHALL** be the same on every surface that
reads links.

**Rationale:** the marked-up reader only sees links the editor turned into anchors;
the free-text reader also sees a URL typed as plain text. A note and a task store the
same kind of column, so a user who pastes a link into one and links it in the other
gets two different answers about whether the link exists.

**Test coverage:** the same test as REQ-LINK-001, plus a test that a pasted URL is
recognised wherever links are recognised.

#### Scenario: A pasted link is a link
- The user pastes a link into a note's body
- The note lists that link among its outgoing links

---

### Requirement: REQ-LINK-003

A stored link **SHALL** be matched by the identifier alone, not by a substring of the
stored text, and an identifier **SHALL NOT** be interpreted as a pattern while it is
matched.

**Rationale:** the backlink queries find a link by interpolating the identifier into
a match pattern over the whole stored column. The identifier is data, and data used as
a pattern matches more than it should — today the identifiers happen to be UUIDs, so
the wildcards do not bite, and nothing in the contract says they always will be.

**Test coverage:** a test that an identifier which differs from a stored one by a
single wildcard character does not match.

#### Scenario: A near-miss identifier does not match
- A stored link points at task "abc"
- A query asks for task "a_c"
- No row is returned

#### Scenario: The exact identifier matches
- A stored link points at task "abc"
- A query asks for task "abc"
- The row is returned

---

### Requirement: REQ-LINK-004

Tapping an internal link **SHALL** open the thing it points at, and **SHALL** degrade
to doing nothing when that thing no longer exists.

**Rationale:** the tap handler is the only one of the four grammars that acts — the
others read or match. It is also the only one with no test, which is how a handler
that swallows the tap, or opens the wrong thing, survives.

**Test coverage:** a test that a tap on a link opens the referenced note or task; a
test that a tap on a link to a deleted target leaves the user where they were.

#### Scenario: Tapping a link to an existing note
- A note's body links to another note
- The user taps the link
- The other note opens

#### Scenario: Tapping a link to something that was deleted
- A note's body links to a task that has since been deleted
- The user taps the link
- Nothing opens, and the note stays on screen
