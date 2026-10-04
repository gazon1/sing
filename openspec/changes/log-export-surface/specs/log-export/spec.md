# log-export

**capability:** `log-export` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-LE-001

The system **SHALL** offer a user-initiated action that hands the captured log file
to the platform share mechanism.

**Rationale:** file logging already writes a readable log on both platforms. A
developer can retrieve it; a user cannot. Without a share path the log exists only
to serve whoever built the app.

**Test coverage:** `LogBundleExporterTest` (to be extended — the exporter's current
tests cover writing, not sharing).

#### Scenario: User shares the log
- The log file exists for the current session
- The user activates the share action
- The platform share sheet receives the log file
- The action is available without a crash having occurred

---

### Requirement: REQ-LE-002

The system **SHALL** offer a share action from a context that does not require
remembering that the feature exists.

**Rationale:** a settings-only surface depends on the user knowing to look. The
trigger that matters is the one that fires when the user is already frustrated.

**Test coverage:** to be added with the implementation.

#### Scenario: Offer is offered where the user already is
- The user reaches the surface that presents a failure
- A share action for the log is offered in place
- The action may be declined without losing any other state

---

### Requirement: REQ-LE-003

A shared log **SHALL NOT** contain credentials, tokens or secrets, and **SHALL**
redact user-authored content to the level a log file can be handed to a third
party.

**Rationale:** the log is the most likely artifact to leave the device. Redaction
that only covers credentials leaves task titles, note bodies and search queries in
a file a user attaches to a public bug report.

**Test coverage:** redaction tests to be added alongside the inventory in
#log-messages-user-content-sweep.

#### Scenario: Shared log carries no user content
- A task and a note with recognisable titles are created
- The log is captured and prepared for sharing
- No user-authored text is present in the shared form
- Credentials that were redacted before are still absent

---

### Requirement: REQ-LE-004

Declining to share **SHALL** leave the log file and the developer's failure
diagnostics intact.

**Rationale:** the developer failure bundle and the user share path read the same
file. Cancelling a share must not delete the evidence a developer needs.

**Test coverage:** to be added with the implementation.

#### Scenario: Cancelled share keeps the diagnostics
- The user starts a share and cancels it
- The log file still exists
- A developer failure bundle can still be produced from the session
