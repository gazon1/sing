# acp/real-bridge Specification

## Purpose
The application's ACP client driven against a real agent bridge — not a
fake taught to answer what our client expects. The probe's exit code is
the verdict, and the shapes the bridge sends are covered by a test that
runs on every machine.

## Requirements

### Requirement: REQ-REALBRIDGE-001

The client **SHALL** be drivable against a real agent bridge by a script that a person
runs, and the script's exit code **SHALL** be the verdict: zero when the handshake
completed, one when it did not. A build that failed before the probe ran is not a
verdict about the bridge: the script **SHALL** say that the probe did not run and exit
with the build's own code.

**Rationale:** the suite drives a fake agent that this repository wrote, so it proves
the client and the fake agree with each other. The protocol is written by somebody
else, and nothing in the suite said whether the client agrees with it. The check has
to exist, and it has to be runnable by hand, because it needs a real `npx`, a real
network and a real bridge — none of which a test may depend on.

#### Scenario: The bridge answers
- The script starts the bridge and drives the client against it
- The handshake completes
- The script exits zero, and prints what the bridge sent

#### Scenario: The bridge does not answer
- The script starts the bridge and drives the client against it
- The handshake does not complete
- The script exits one, and prints why

#### Scenario: The build fails before the probe runs
- The script starts the build that runs the probe
- The build fails, and no probe ran
- The script says the probe did not run, and exits with the build's own code

---

### Requirement: REQ-REALBRIDGE-002

The probe **SHALL** report what the bridge sent that the fake agent does not send, and
the fake agent **SHALL** send those shapes afterwards.

**Rationale:** a finding that lives only in the transcript of one run is a finding that
decays. What the real bridge sends has to become something the suite covers, or the
next bridge that announces something new will find the client unprepared — which is the
very thing the probe was run to find out.

#### Scenario: The bridge announces something the fake never sent
- The probe records the shape the bridge used
- The fake agent sends that shape too
- A test covers the client's reading of it
