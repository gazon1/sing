# mcp/protocol-output Specification

## Purpose

That the protocol channel has one owner — the value that holds the stream — and that
everything which is not the protocol stays off it.

## Requirements

### Requirement: REQ-POUT-001

The protocol's output **SHALL** be a value that owns the stream, and it **SHALL** be passed
to everything that writes the protocol.

**Rationale:** the contract "standard output is the protocol channel" is only checkable if
the protocol's stream is something a caller can hold. A bare `PrintStream` says nothing
about which stream it is, and a caller who is handed one has to know where it came from.

#### Scenario: A writer is given the protocol's output
- A part of the server writes a protocol message
- It is given the protocol's output, not a stream it found
- The message reaches the client

#### Scenario: The protocol's output is the original standard output
- The server starts
- The protocol's output is the stream standard output was before the server started
- A client reads the protocol's messages from it

---

### Requirement: REQ-POUT-002

The application's own log **SHALL** be written to standard error by its own configuration,
and **SHALL NOT** depend on the process's standard output having been redirected.

**Rationale:** the redirect is a defence against a third-party library that prints to
standard output. It should not be the reason the application's log is somewhere safe — a
defence that everything leans on is not a defence.

#### Scenario: The logger writes to standard error
- The logger is configured
- Standard output has not been redirected
- A log line reaches standard error, and nothing reaches standard output

---

### Requirement: REQ-POUT-003

Everything that is not the protocol **SHALL** be kept off the protocol channel, including a
print that no part of this application made.

**Rationale:** a client that parses standard output strictly stops at the first line it
cannot parse. A library's start-up banner is enough to lose the tool access entirely, and
nothing in the test suite would see it — the tests speak to the server in-process.

#### Scenario: A print after the reservation
- The protocol's output has been taken
- Something prints to standard output
- Nothing reaches the protocol channel, and the print reaches standard error
