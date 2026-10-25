# agent-toolbox-surface Specification

## Purpose
The surface the agent is given: which tools exist, which are switched
off, which build; the knowledge of the project, searchable through the
API the agent already writes to; and a record of what it actually called.
The catalogue is derived from the same place the agent is told from, so
the two cannot drift.

## Requirements

### Requirement: REQ-ATB-001

The MCP server SHALL expose a tool named `describe_toolbox` that reports the toolbox the
server is exposing, without requiring the caller to read a file.

The response SHALL carry, as separate values: the application build version; the checkout
commit and dirty state when the process runs inside a checkout, and `unknown` otherwise; a
fingerprint of the *declared* catalogue; a fingerprint of the *actually registered* tools;
and the status of the configuration that was applied.

#### Scenario: The agent asks what it is connected to
- The agent calls `describe_toolbox` with no arguments
- The response lists every registered tool, grouped by domain, with its description
- The response carries the build version, the two fingerprints and the configuration status
- The response contains no absolute path, no secret and no remote-config content

#### Scenario: The agent asks about one domain
- The agent calls `describe_toolbox` with `domain = "tasks"`
- The response lists only the tools of that domain

#### Scenario: The agent asks which tools are switched off
- The agent calls `describe_toolbox` with `includeDisabled = true`
- The response names the disabled tools, bounded in number
- When the list is bounded, the response says that it was truncated and how many there are

#### Scenario: There is no checkout
- The server runs outside a repository
- The response reports the checkout as unknown
- The response is still produced — the checkout is optional, and its absence is not a failure

---

### Requirement: REQ-ATB-002

A tool whose remote-config flag is `false` SHALL NOT be registered with the MCP server, and
therefore SHALL NOT appear in `tools/list` and SHALL NOT be callable.

#### Scenario: A tool is switched off
- The remote configuration holds `mcpToolFlags = { "summarize_note": false }`
- The server starts
- `tools/list` contains no `summarize_note`
- A call to `summarize_note` fails as an unknown tool

#### Scenario: The configuration cannot be read
- The cached configuration is unavailable
- The server starts and every tool is registered
- `describe_toolbox` reports the configuration status as unavailable, so the operator can
  see that the switch was **not** applied

#### Scenario: No flag is set for a tool
- The remote configuration holds no flag for that tool
- The tool is registered, because a missing flag means enabled

#### Scenario: A flag names a tool that does not exist
- The remote configuration holds a flag for a tool that is not registered
- The server starts, and every registered tool stays registered — a flag that matches no
  tool changes nothing, and it is not reported as a disabled tool
- The flag is reported as unmatched, and the server says so at startup: the operator who
  set it believes a tool is off, and it is not

#### Scenario: The flag is global
- The remote configuration is switched off for one profile
- The tool is switched off for every profile and every consumer of that database

---

### Requirement: REQ-ATB-003

The declared toolbox and the wired toolbox SHALL agree, and a disagreement SHALL fail the
build.

#### Scenario: A tool is in the catalogue and not in the DI list
- The catalogue declares a tool the platform's DI module does not register
- The runtime wiring test fails on that platform

#### Scenario: A tool is wired and not in the catalogue
- The platform's DI module registers a tool the catalogue does not declare
- The runtime wiring test fails

#### Scenario: Two tools answer to the same name
- Two registered tools declare the same name
- The runtime wiring test fails

#### Scenario: The catalogue points at the wrong class
- The catalogue names a class that does not declare that tool's name
- The drift gate fails

#### Scenario: An ADR tool has no command-line face
- The catalogue declares a tool in the `adr` domain
- The CLI surface maps no command to it
- The drift gate fails

---

### Requirement: REQ-ATB-004

The system SHALL provide a `search_decisions` tool that answers "what did we decide, and
is it still open?" from the decision corpus, and a `search_docs` tool that answers "where
is the rule about X?" from the documents, the skills and the specifications.

#### Scenario: A decision matches
- The agent calls `search_decisions` with a query that matches a decision's title
- The response carries the decision's slug, title, date, tags, status and a snippet
- The hits are ordered newest first

#### Scenario: The backlog matches
- The agent calls `search_decisions` and a deferred backlog entry matches
- The hit is marked as a backlog entry, not as a decision

#### Scenario: An archived decision matches
- The query matches a file in the archive
- It is not in the results, because an archived file is not a decision

#### Scenario: Nothing matches
- The corpus is searched and nothing matches
- The response reports that the corpus was searched, with no hits and no error

#### Scenario: The corpus is not there
- The process runs without a checkout, or the decisions directory does not exist
- The response reports that the corpus was not searched, and says why

#### Scenario: The query is blank
- The agent calls the tool with a blank query
- The response carries an error, and no hits — a wrong call is not an empty corpus

#### Scenario: The caller asks for more than the ceiling
- The agent calls the tool with a limit of 10000
- The number of hits returned is bounded by the ceiling

---

### Requirement: REQ-ATB-005

The system SHALL record every completed tool call, on the MCP path and in the in-app agent,
in one event format.

The record SHALL NOT contain the arguments, the result, or the text of an exception. The
error SHALL be recorded as a stable code.

#### Scenario: A call succeeds
- An agent calls a tool and it succeeds
- One record is written, naming the tool, the outcome and the duration

#### Scenario: A call fails in a way the caller can act on
- A tool rejects the call with a validation error
- One record is written, with the outcome `business_error` and the code `validation`

#### Scenario: A call fails in a way nobody can act on
- A tool throws
- One record is written, with the outcome `internal_error`

#### Scenario: The record cannot be written
- The data directory cannot be created or written
- The call still returns its result
- The number of lost records is counted, and the count is reported rather than logged
  through the same broken writer

#### Scenario: A second process writes to the same directory
- Two processes record into the same application data directory
- Neither truncates the other's file, and the report reads both

#### Scenario: A process dies mid-write
- A process is killed while writing a record
- The report skips the truncated line, counts it, and carries on

---

### Requirement: REQ-ATB-007

The system SHALL report a deferred backlog entry as a backlog entry and never as a
decision, in every listing of the corpus.

The corpus rules own that distinction. A rule that is written down and not applied at
the read boundary is the same defect as a rule that is written down twice.

#### Scenario: The corpus is listed
- The corpus holds decisions and a `deferred/` backlog
- The listing of decisions contains the decisions and none of the backlog
- The listing of the backlog contains the backlog

#### Scenario: The corpus is searched
- A query matches a decision and a backlog entry
- The decision is reported as a decision
- The backlog entry is reported as a backlog entry, and is not reported twice

#### Scenario: An archived record matches
- The query matches a record under the archive
- It is not in the results, because an archived record is not a decision

#### Scenario: The two readers of the corpus agree
- The corpus is read by the Kotlin storage and by the repository's Python gates
- Both report the same decisions, the same backlog, and the same archive

---

### Requirement: REQ-ATB-006

The system SHALL provide a local report over the tool-call records that distinguishes the
tools that were called from the tools that were registered, and that states the window it
looked at.

#### Scenario: A tool was registered and never called
- The registry snapshot lists a tool
- No record in the window names it
- The report lists it as not observed in this window — not as unused

#### Scenario: A tool was called and is no longer registered
- A record names a tool
- The registry snapshot does not list it
- The report lists it, because a client is still working from a stale toolbox

#### Scenario: There is no registry snapshot
- No `registry.json` was written
- The report says so, because without it "never called" cannot be told from "never
  registered"

#### Scenario: The report is asked for machine-readable output
- The report is invoked with `--json`
- The output is a single JSON document with the counts, the malformed-line count, the
  files read and the registry snapshot
