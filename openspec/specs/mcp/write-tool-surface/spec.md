# mcp/write-tool-surface Specification

## Purpose

The tool server's profile argument and its refusals: which profile it works with,
what an unknown one earns, and what the client is told when it cannot start — and
that the server works on the profile it was told and never opens the stored one.

## Requirements

### Requirement: REQ-MCPSRV-010

The tool server **SHALL** accept a profile id that identifies the profile to work with,
**SHALL NOT** open the application's stored active-profile state while doing so, and
**SHALL NOT** change which profile the application is using.

**Rationale:** the stored active profile is the application's business. A second process that
opens the same preferences store contends for its lock, and one that changes the stored value
changes who the user is without their knowledge.

#### Scenario: A profile id is given
- The tool server is started with a profile id
- It serves that profile's data
- The application's stored active profile is unchanged when it exits

#### Scenario: The stored profile state is never opened
- The tool server is started with a profile id
- The stored active-profile state is not opened
- No contention with the running application occurs

---

### Requirement: REQ-MCPSRV-011

An unknown or missing profile id **SHALL** cause a clear diagnostic and a non-zero exit, and
**SHALL NOT** fall back to a different profile.

**Rationale:** a silent fallback means a process that asked for one set of data reads another.
The user never sees it, and neither does the process.

#### Scenario: An unknown profile id is refused
- The tool server is started with an id that does not exist
- It stops with a diagnostic naming the id
- It exits non-zero
- No data is served

---

### Requirement: REQ-MCPSRV-012

The legacy profile label **SHALL** keep its existing meaning, **SHALL NOT** be silently
reinterpreted, and **SHALL** be marked as deprecated where it is documented and where it is
used.

**Rationale:** client manifests in the wild already pass the legacy label. Changing what it
means breaks every one of them, silently, at the point where an agent starts reading the
wrong profile.

#### Scenario: The legacy label still works
- The tool server is started with the legacy label
- It behaves exactly as it did before this change
- The deprecation is recorded in the log

#### Scenario: The two selectors together are refused
- The tool server is started with both a profile id and the legacy label
- It stops with a diagnostic naming the ambiguity
- It exits non-zero

---

### Requirement: REQ-MCPSRV-013

Every error response the tool server sends **SHALL** be a valid protocol message, and
**SHALL NOT** contain internal diagnostics such as file paths, database messages, or internal
exception text.

**Rationale:** an error text with a quote in it used to produce a message a client could not
parse, and the same text carried the internals of the failure. A caller that cannot parse the
error cannot report it, and a caller that receives internal paths learns things it was not
meant to.

#### Scenario: An error text with a quote is still a valid message
- The tool server fails and the error text contains a quote
- The response parses as a protocol message
- The response contains no file path and no internal message

#### Scenario: The detail goes to the log
- The tool server fails
- The caller receives a neutral message
- The internal detail is recorded in the log instead
