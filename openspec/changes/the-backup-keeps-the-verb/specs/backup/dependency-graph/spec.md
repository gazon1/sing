# Backup Dependency Graph — Observable Behavior

**capability:** `backup/dependency-graph` | **status:** proposed

---

## ADDED Requirements

### Requirement: A backup keeps what a dependency edge means

A backup SHALL record the meaning of every dependency edge it contains, and a
restore SHALL put back the relation that was there. An edge that follows up on
another SHALL NOT come back as an edge that blocks it.

A backup written before the meaning was recorded SHALL restore as a graph of
blocks: an edge without a recorded meaning can only have been a block.

#### Scenario: A relation that follows up comes back as a relation that follows up

- **Given** two tasks where one follows up on the other
- **When** the user backs the data up and restores it
- **Then** the restored edge still says that the task follows up
- **And** the restored task does not wait for the other one

#### Scenario: A relation that blocks comes back as a relation that blocks

- **Given** two tasks where one is blocked by the other
- **When** the user backs the data up and restores it
- **Then** the restored edge still says that the task is blocked

#### Scenario: A backup written before the meaning was recorded restores as blocks

- **Given** a backup whose edges carry no meaning
- **When** the user restores it
- **Then** every restored edge is a block
