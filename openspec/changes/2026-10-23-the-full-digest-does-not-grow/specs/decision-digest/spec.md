# decision-digest — Observable Behavior

**capability:** `decision-digest` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-1 The complete index is a folded index

The complete decision index SHALL carry every decision of the corpus exactly once,
as a record holding the decision's slug, its title and its tags, and the index SHALL
NOT grow by a line per decision.

The consequences of a decision are the text of the ADR body. The index SHALL NOT hold
a copy of them, and it SHALL tell the reader how to reach the text.

**Rationale:** an index of N decisions has N entries, so a fixed number of lines is
reached by the corpus alone. The cost of a decision has to be a record rather than a
row, or the file that maps the corpus grows with the corpus it maps.

#### Scenario: The corpus doubles

- **Given** a corpus of decisions and the index rendered from it
- **When** the corpus doubles
- **Then** the index grows in characters, not in lines
- **And** every decision of the larger corpus is still in the index

#### Scenario: A decision carries a tag of its own

- **Given** a decision whose frontmatter names a tag
- **When** the index is rendered
- **Then** the decision's record carries that tag
- **And** the consequence text of the decision is not copied into the index

### Requirement: REQ-2 The budget of the index is a rate, not a wall

The budget of the complete index SHALL be the fixed part of the file plus the
per-decision rate times the number of decisions, SHALL be monotone in the number of
decisions, and SHALL have neither a floor under it nor a fixed ceiling over it.

The budget SHALL fit the file at every prefix of the corpus — the file as it stood at
every earlier date — and not only at the size of the corpus of today.

The fixed part and the rate SHALL be measurements, and the measurements SHALL be
recorded in the decision that states them. The file the budget governs SHALL fit the
budget, and the generator SHALL report the numbers when it does not.

**Rationale:** a floor under the budget holds the budget still while the corpus
grows, so the allowance the fixed part had shrinks with every decision until the file
runs into it — a wall wearing the clothes of a ceiling. A rate is the statement that
survives the corpus growing. And a budget fitted to one point of the history is a
wall that happens to be far away: the file of half a year ago is smaller than the file
of today, and a budget that only fits today was never measured against the history.

#### Scenario: The corpus grows

- **Given** the budget computed for a corpus of N decisions
- **When** the corpus grows to N + 1 decisions
- **Then** the budget grows as well
- **And** it is not pinned by any number that does not depend on the corpus

#### Scenario: The corpus of an earlier date

- **Given** the corpus with its newest decisions left out
- **When** the index is rendered from it
- **Then** the file fits the budget of that corpus as well

#### Scenario: The file does not fit the budget

- **Given** a rendered index that is over the budget
- **When** the generator runs
- **Then** it exits non-zero
- **And** the message names the size and the budget

### Requirement: REQ-3 A decision is findable by slug, by title and by tag

The complete index SHALL name every decision of the corpus by its slug, and SHALL
carry the title of every decision and every tag of every decision, so that a reader
can find a decision by any of the three.

A decision whose frontmatter carries no title SHALL be carried under the heading of
its body, and a decision with neither SHALL be marked as untitled rather than left
blank.

**Rationale:** the index promises three ways to find a decision. Twenty-eight
decisions of the corpus carry no title in their frontmatter, and without the fallback
the promise was false for them.

#### Scenario: A decision has no title in its frontmatter

- **Given** a decision whose body starts with a heading
- **When** the index is rendered
- **Then** the decision's record carries that heading as its title
- **And** the decision is found by that title

### Requirement: REQ-4 The orientation layer does not grow with the corpus

The always-loaded orientation layer SHALL be bounded by kind — a fixed number of
recent decisions, one generated tree, one bounded list of tags — and its size SHALL
NOT depend on the number of decisions in the corpus.

The renderer of the layer SHALL take the decisions it draws from as an argument, so
that the property can be measured on an artificial corpus.

**Rationale:** the layer is read in every session, so its size is a tax paid on every
task. A layer that grows with the corpus taxes every task more each day.

#### Scenario: Twenty decisions and two hundred decisions

- **Given** the layer rendered from twenty decisions and from two hundred decisions
- **When** the two are compared
- **Then** they have the same number of lines
