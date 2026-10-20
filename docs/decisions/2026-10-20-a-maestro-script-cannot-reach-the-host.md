---
title: "A Maestro script cannot reach the host, so the host offers a bridge"
date: 2026-10-20
status: accepted
tags: [architecture, e2e, maestro, tooling]
---

## Context

Four Maestro scripts were supposed to talk to the device's shell. None of them
could, and each failed in its own way.

| Script | What it called | What Maestro 2.10.0 has |
|---|---|---|
| `setprop.js` | `java.lang.Runtime.getRuntime().exec([…])` | no `java` |
| `setprop.js` | `env.PROP` | no `env` object |
| `damage-store.js`, `verify-store.js`, `verify-session.js` | `execSync("run-as …")` | no `execSync` |

The engine is **GraalJS**, and it runs with no host access. The globals that
actually exist are `json`, `relativePoint`, `http`, `faker`, `output`,
`maestro.platform`, `console`, `print`, `printErr`, `MAESTRO_DEVICE_UDID`,
`MAESTRO_FILENAME`, `MAESTRO_SHARD_ID`, `MAESTRO_SHARD_INDEX` — and each `env:`
entry as a **bare global**, so `env: {PROP: "x"}` gives you `PROP`.

There was a second, quieter break beside it: `YamlRunScript` in
`maestro-orchestra` has the fields `file`, `env`, `when`, `label`, `optional` —
there is no `name`. The two flows that used the map form
(`auth/02-signin-wrong-password`, `auth/06-signup-unconfirmed`) therefore did not
run at all; Maestro refused to parse them with

    Config Field Required: file at …/02-signin-wrong-password.yaml:37:1

Both breaks were invisible from the repository. The scripts had comments
describing what they would do, the flows had comments describing the `adb shell
run-as` they expected, and the failure of either was indistinguishable from the
behaviour the flow was asserting.

The measurement that made it visible: the nightly run of 2026-10-20 failed 49
flows, and the three causes that were measured named
`auth/12`'s "third, independent problem" — the scripts shell out to `run-as` as
if they ran on the device — without saying that *nothing* in that tier had ever
reached a shell.

## Idea

- **A.** Rewrite the scripts against an API that exists. There is none: the
  sandbox has no shell, no host access, and no filesystem.
- **B.** Give the scripts a channel that does exist. Maestro's sandbox has `http`,
  and the host can serve it.
- **C.** Move the work into the app. The E2E build damages its own store and
  reads its own properties, so no flow needs a shell at all.
- **D.** Drop the flows that need a shell.

## Decision

**B** — a host-side bridge, reached over HTTP.

- `scripts/src/emulator/maestro/_bridge.py` starts a small HTTP server on the
  loopback interface, on an ephemeral port, for the duration of one test run.
- The runner passes its address to every flow as `--env E2E_BRIDGE_URL=…`, which
  Maestro exposes to the script as a bare global.
- A script calls `GET <E2E_BRIDGE_URL>/shell?cmd=<url-encoded>` and parses
  `res.body` as JSON: `{"stdout": …, "stderr": …, "code": …}`.
- The bridge accepts **three command shapes and nothing else**:
  `run-as com.singularity.todo sh -c '…'`, `setprop <name> <value>`,
  `getprop <name>`. Every other command is refused before it runs.
- The command is executed as `adb -s <serial> shell "<command>"` — a single
  argument, so the device's own shell parses the quoting and `sh -c '…'` arrives
  with its words intact.

**C is left on the table on purpose.** It is cheaper, and it would work: the
debug build could damage its own store. It is not chosen because it puts the
thing under test inside the thing doing the damaging. The claim `auth/12` makes
is about the app's recovery from a store it cannot read; an app that corrupted
its own store and then recovered from it is a weaker claim, and the flows that
use `setprop` need the same channel anyway.

**D is not on the table.** The flows assert behaviour that has no other
observable trigger.

## Rationale

- **A sandbox with no exit is a sandbox, and the work these flows do is on the
  device.** `run-as` reaches a file inside the app's own data directory and
  `setprop` tells the debug build to behave differently. Neither is host work,
  and neither is something the app should do on its own initiative.
- **The whitelist is the security argument, and it has to match whole
  commands.** A prefix check accepts `run-as com.singularity.todo ; rm -rf /`:
  the device shell runs the second command as the *shell* user rather than the
  app's. So the shape is matched whole — one `run-as` with a single-quoted
  script, or a property set or get — and anything else is refused before it
  runs. The bridge never runs a command on the host, and it never runs a
  command the device's shell would read as more than one.
- **The bridge binds to 127.0.0.1, on an ephemeral port, for one run.** It is
  reachable by anything on the machine for the length of the run, so the
  whitelist is the whole of its exposure. It is stopped when the run ends.
- **The scripts now fail loudly when the bridge is absent.** A flow run directly
  with `maestro test`, without the runner, has no `E2E_BRIDGE_URL` — and the
  flow says so instead of damaging nothing and passing on its next assertion.
- **`http` is the channel Maestro offers, so it is the channel worth building.**
  `GraalJsHttp` is part of the engine; nothing else in the sandbox leaves the
  device.

## Consequences

- **The four scripts work, and the two flows that could not be parsed now
  parse.** The `name:` key became `file:`.
- **The E2E tier has a host dependency it did not have.** A flow that needs the
  shell needs the runner. `maestro test <flow>` on its own cannot run them, and
  says so.
- **The bridge is a piece of test infrastructure with a security boundary.** Its
  whitelist is exercised by `scripts/tests/test_maestro_bridge.py`, including
  the injection attempt that a prefix check would have accepted.
- **The properties a flow sets are device state that outlives the flow.**
  `setprop` in `onFlowStart` and reset in `onFlowComplete` is a pair the flow
  has to keep; a flow that dies between them leaves the property set, and the
  next flow inherits it.

## Links

- `scripts/src/emulator/maestro/_bridge.py` — the bridge and its whitelist
- `scripts/src/emulator/maestro/_runner.py` — the `--env E2E_BRIDGE_URL=…` the
  flow is given
- `scripts/tests/test_maestro_bridge.py` — the whitelist, including the
  injection attempt
- `Maestro/helpers/scripts/` — `setprop.js`, `damage-store.js`,
  `verify-store.js`, `verify-session.js`
- `2026-10-20-an-e2e-flow-asserts-on-structure-not-on-the-apps-copy.md` — the ADR
  that recorded `auth/12`'s third problem without knowing its cause
