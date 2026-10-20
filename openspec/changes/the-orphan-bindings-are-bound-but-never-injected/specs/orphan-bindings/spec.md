# orphan-bindings — Observable Behavior

**capability:** `orphan-bindings` | **status:** deferred

---

## ADDED Requirements

### Requirement: REQ-ORPHAN-1

A binding that nothing injects SHALL be reported, and the report SHALL name
the binding, the module that declares it, and the fact that it is a no-op at
runtime.

The detector's finding is the record. It SHALL survive the change that
prompted it: an orphan binding is a question about a product decision, and
the question does not disappear because the line stopped being typed.

#### Scenario: A binding nobody injects

- **Given** a module declares `single<SomeInterface>`
- **And** no `get`, `inject` or `koinViewModel` call resolves that interface
- **When** the unwired-surfaces detector runs
- **Then** it reports the binding
- **And** it names the module it is declared in
- **And** it says the binding is a no-op at runtime

#### Scenario: A binding with a consumer

- **Given** a module declares `single<SomeInterface>`
- **And** some other binding or ViewModel resolves that interface
- **When** the unwired-surfaces detector runs
- **Then** it does not report the binding

### Requirement: REQ-ORPHAN-2

An orphan binding SHALL NOT be closed by inventing a consumer.

A consumer added only to quiet a detector is a feature that exists to satisfy
a gate, and it is a claim the product did not make. Each orphan binding
therefore requires a product answer: the interface is either wanted — and
then something wants it — or it is not, and the interface and its binding both
go.

#### Scenario: The decision to keep the seam

- **Given** a binding nobody injects
- **When** the change that closes it is written
- **Then** the change states what consumes the interface
- **And** the consumer is not the detector

#### Scenario: The decision to drop the seam

- **Given** a binding nobody injects
- **When** the change that closes it is written
- **Then** the interface is removed with the binding
- **And** no dangling interface is left to be bound again

### Requirement: REQ-ORPHAN-3

The graph test SHALL NOT claim that every declaration has a consumer.

The Koin compiler plugin verifies that what is injected can be constructed.
It does not verify that every declaration is injected, and a rule that
demanded it would ban the deliberate seams — a binding declared on purpose so
that a later change has a place to land.

#### Scenario: A declared seam with no traffic yet

- **Given** a module declares a binding for a seam that has no consumer yet
- **When** the graph test runs
- **Then** it does not fail the build on that declaration alone
