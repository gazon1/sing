# crash-reporting

## ADDED Requirements

### Requirement: REQ-5 Background work inherits its failure policy from the component that owns it

A scope used for background work SHALL receive its failure policy from the same source as the
rest of its dependencies, chosen by the component that starts the work. It SHALL NOT inherit one
from a default established elsewhere in the process.

A component that starts background work on its own SHALL therefore be answerable for what happens
when that work fails, in the same way it is answerable for the threading and the lifetime of the
work.

The guarantee does not weaken. A failure in background work SHALL still be recorded, under its
existing grouping key, and SHALL still leave the process running. Only the choice of policy moves,
from a process-wide default to the owner of the work.

#### Scenario: A component starts background work

- **Given** a component that starts work which can fail
- **When** it is constructed
- **Then** the scope it starts work on carries a failure policy that component chose, and no
  process-wide default is involved

#### Scenario: Two components disagree about failures

- **Given** two components that start background work under different failure policies
- **When** work fails in one of them
- **Then** only that component's policy applies, and the other component's behaviour is unchanged

### Requirement: REQ-6 The absence of a process-wide default is enforced rather than documented

A build-time check SHALL fail when a component obtains background work without supplying its own
failure policy, and SHALL fail when a process-wide default for it is reintroduced.

The check SHALL be capable of failing. A check that silently matches nothing is worse than no
check at all, and a documented convention is not a guarantee that the next change respects it.

#### Scenario: A new default for background failures is introduced

- **Given** a change introduces a process-wide default for how background failures are handled
- **When** the check runs
- **Then** the check fails and names the introduction site

#### Scenario: A component starts background work without a policy

- **Given** a component begins background work using a scope whose failure policy it did not
  choose
- **When** the check runs
- **Then** the check fails and names the component
