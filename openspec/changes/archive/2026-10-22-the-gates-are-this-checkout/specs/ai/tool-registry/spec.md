# The write path is measured, not asserted — Observable Behavior

**capability:** `ai/tool-registry` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TOOLREG-021

A tool that writes a user's data directly SHALL be listed with the reason it
does, and the list SHALL shrink as tools are converted.

A tool that writes a user's data and is not listed SHALL fail the build. A listed
tool that no longer writes directly SHALL fail the build as well. A tool that both
proposes a change and writes it directly SHALL fail the build.

**Rationale:** the write path is half a write path. The delete tools and the
proposal tool propose a change and let the user decide; the other tools write
straight to the user's data. The same act gets two treatments, decided by which
tool the model happened to pick — and a user who agreed to one thing cannot tell
which of the two they are looking at.

A rule that is written down and not checked is a rule that is true in the document
and false in the product. The list is the measure of what is left, so it is
held in both directions: a new writer has to say why it writes, and a tool that
was converted cannot leave a stale entry behind that hides the work.

#### Scenario: A new tool writes the user's data directly

- **Given** a tool writes the user's data and is not listed
- **When** the build runs
- **Then** it fails
- **And** it names the tool and says the reason has to be written down

#### Scenario: A tool was converted to the proposal path

- **Given** a listed tool no longer writes the user's data directly
- **And** its entry is still listed
- **When** the build runs
- **Then** it fails
- **And** it says the entry is stale

#### Scenario: A tool proposes and writes at the same time

- **Given** a tool both proposes a change and writes the user's data directly
- **When** the build runs
- **Then** it fails
- **And** it says one tool must not have two treatments

#### Scenario: The listed writers are the actual writers

- **Given** every tool that writes the user's data directly is listed with a
      reason
- **And** every listed tool writes directly
- **When** the build runs
- **Then** it passes
- **And** it reports how many writers are listed
