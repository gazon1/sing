# One search, two callers — Observable Behavior

**capability:** `search` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-SEARCH-011

A tool that searches SHALL answer the same question the search screen answers,
through the same search.

The tool SHALL understand the query language the application understands, and
SHALL find a task by its description, its tag and its project, and not only by
its title. A query the search cannot read SHALL be reported as such, and not as
an empty result.

**Rationale:** the same question must have the same answer. The person at the
keyboard and the model in the chat are asking the same thing about the same
data, and a second implementation of "what does this word match" is how the two
answers drift apart.

An empty answer and a malformed query are different facts. A tool that answers
"nothing found" to a query it could not read teaches the model that the thing
does not exist.

#### Scenario: The word is in the description

- **Given** a task whose description holds the word
- **When** the model searches for it
- **Then** the task is in the answer

#### Scenario: The query narrows the result

- **Given** tasks in different projects
- **When** the model searches with a project term
- **Then** only the tasks of that project are in the answer

#### Scenario: The query cannot be read

- **Given** a query the search cannot parse
- **When** the model searches for it
- **Then** the answer says the query is malformed
- **And** it does not say that nothing was found
