# GenUI — Observable Behavior

**capability:** `genui` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-GENUI-REVISION-1

The count of the times a value was replaced from outside SHALL travel inside that value.

A value replaced from outside SHALL carry the count of those replacements. A value the
user's own field wrote SHALL NOT move that count. The count of one value SHALL NOT move
when a different value is replaced.

A caller that reads a value and its count SHALL receive them together.

#### Scenario: The server replaces a value

- **Given** a value in a surface
- **When** the server replaces it
- **Then** the value changes and its count moves

#### Scenario: The field writes its own value

- **Given** a value in a surface
- **When** the field writes to it
- **Then** the value changes and its count does not

#### Scenario: The server replaces a different value

- **Given** two values in a surface
- **When** the server replaces one of them
- **Then** the other one's count does not move

#### Scenario: A surface that comes back

- **Given** a surface that was closed
- **When** a surface with the same identity is opened
- **Then** its values carry no count

#### Scenario: A value and its count read apart

- **Given** a value and the count of its replacements
- **When** a caller reads them
- **Then** they are read together, and a value never comes with a count that belongs to
  another write
