# crash-reporting

## ADDED Requirements

### Requirement: REQ-7 A component's two failure paths resolve to one destination

A component that both handles failures explicitly and starts background work SHALL report both
kinds of failure to the same destination.

The two paths are separate calls made in separate places — one at the point a handled failure is
turned into something the user sees, one by the context the background work runs on when nothing
handled the failure at all. Nothing about the act of starting background work requires them to
differ, and a component that supplies its own work context SHALL NOT thereby be able to name a
second destination for one of the two.

The guarantee is about the destination, not about the two paths sharing code. A component MAY
report the two kinds of failure under different grouping keys, since a handled failure and an
escaped one are different events; what it may not do is send them to different places, because
the consequence of that is a report set in which the same symptom appears under two unrelated
destinations with nothing to correlate them.

#### Scenario: A component starts background work and handles failures

- **Given** a component that reports the failures it handles and also starts work which can fail
- **When** both a handled failure and an escaped background failure occur
- **Then** both are recorded at the same destination, distinguishable by their grouping keys

#### Scenario: A component supplies its own work context

- **Given** a component that starts background work using a context it supplied rather than one
  derived from its own dependencies
- **When** a background failure escapes
- **Then** it is recorded at the same destination the component's handled failures reach, and the
  component does not name a second one

### Requirement: REQ-8 The agreement is enforced rather than assumed

A check SHALL fail when a component's handled-failure destination and its background-work
destination are not the same, and the check SHALL be capable of failing.

The check may take either of two forms, and the choice between them is a decision rather than an
implementation detail. A verification MAY compare the two resolved destinations for every
component in the graph. Alternatively the disagreement MAY be made unrepresentable — the work
context derived from the component's own dependencies, with no way to supply a different one — in
which case there is nothing to compare and the guarantee holds by construction.

What is not acceptable is a documented convention with no check behind it. A component that
overrides its work context is the ordinary case rather than the exotic one, and a convention that
the next change is free to ignore does not describe this system.

#### Scenario: A component's two destinations are made to differ

- **Given** a component whose work context resolves to a different destination than the one its
  handled failures reach
- **When** the check runs
- **Then** the check fails and names the component

#### Scenario: The check is itself wrong

- **Given** a component whose two destinations do in fact agree
- **When** the check runs
- **Then** the check passes, having been shown to fail on a deliberately mismatched component
  rather than assumed capable of failing
