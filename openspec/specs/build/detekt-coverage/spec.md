# build/detekt-coverage Specification

## Purpose

That every Gradle module holding Kotlin source is judged by the repository's detekt
configuration and by the project's own rules — the rules module and the tool's own
analysis module excepted — and that a class carrying more than one concern is split.

## Requirements

### Requirement: REQ-DCOV-001

Every Gradle module that holds Kotlin source **SHALL** be judged by the repository's detekt
configuration and by the project's own rules. Two modules are the exceptions: the rules
module, which *is* the project's own rules and is judged by the configuration that suits
them, and `:tools:unwritten-properties`, which holds a tool's own analysis code rather
than application source.

**Rationale:** a module on a reduced configuration is a module whose rules are a choice
nobody made. The configuration was a launcher module's; the module is not that any more, and
the project's own rules — the ones written for this repository — do not reach it.

#### Scenario: A module is judged by the same configuration
- The module's detekt task runs
- It reads the repository's configuration and the project's own rules
- A finding in the module is a finding, like a finding anywhere else

#### Scenario: A finding is fixed rather than baselined
- The broader configuration finds something in the module
- The finding is fixed
- The baseline does not grow

---

### Requirement: REQ-DCOV-002

A class that holds more than one concern **SHALL** be split, so that a change to one
concern does not have to be read against the others.

**Rationale:** the two classes named in this change grew because each concern was a small
addition to a file that already had a reason to exist. Reading either of them means holding
two conversations at once, and a change to one is a change to the other's context.

#### Scenario: A class holds one concern
- A reader opens the class
- The class has one reason to change
- The reader does not have to hold another concern in mind to follow it

#### Scenario: The split changes no behaviour
- The classes are split
- The tests that drove them still pass unchanged
- The split moved code, and did not change what the code does
