# crash-reporting Specification

## Purpose

The app has somewhere to send failures. That is only half of it: the other half is deciding
**where a failure goes when nothing has asked it to**, and the answer used to be "wherever a
process-wide target installed at startup happens to point".

This capability covers the two decisions that were open after the reporting backend landed:

- **Where an unhandled failure in background work is reported.** The bottom of the error funnel —
  a `launch` in a scope that exists precisely so background work does not need a screen — had no
  policy of its own, and a throw there reached the platform's uncaught-exception handler, which on
  Android kills the process.
- **Whether that policy is inherited from a process-wide default, or chosen by the component that
  starts the work.** A scope factory that cannot take a dependency, running inside graph
  construction, is a real constraint — and it was resolved, for a while, with a mutable global and
  an argument in a KDoc explaining why the global was acceptable.

REQ-1 to REQ-4 live in `core/ui-mvi`; they describe the funnel every call site routes into. These
two describe the floor underneath it.

## Requirements

### Requirement: REQ-5 Background work inherits its failure policy from the component that owns it

A scope used for background work SHALL receive its failure policy from the same source as the
rest of its dependencies, chosen by the component that starts the work. It SHALL NOT inherit one
from a default established elsewhere in the process.

A component that starts background work SHALL therefore be answerable for what happens
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

## What the two requirements cost, and what they bought

REQ-5 was satisfied by making the scope factory require its failure handler as an argument with
no default, and by deriving a ViewModel's default scope from the reporting port that ViewModel is
already required to hold. The four components that hold no reporting port name their handler at
their own binding, which is the case where a required argument is the only honest answer.

REQ-6 is met by two rules — one asking whether a ViewModel class has somewhere to report, one
asking whether its Koin binding lets it — and by the observation that the second was necessary:
the first passed, happily, for two production bindings that shipped a no-op reporter.

**One item is deliberately still open and is not in these requirements.** Whether the
`background.coroutine_failed` grouping key can be retired needs the reporting dashboard, which
cannot be read from the repository, and it is blocked behind verifying that reports are being sent
at all. Tracked as an issue, not as a requirement, because a requirement nobody can check is the
kind of thing this repository keeps finding.
