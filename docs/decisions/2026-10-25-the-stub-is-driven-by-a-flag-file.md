---
title: "The stub is driven by a flag file, and the flow writes it through run-as"
date: 2026-10-25
status: accepted
tags: [testing, maestro, auth]
---

# The stub is driven by a flag file, and the flow writes it through `run-as`

## Context

The E2E build replaces the auth repository with a stub, so a flow can exercise the auth
screen without a server. Some auth outcomes cannot be produced by the app itself — a
rejected password, a sign-up whose address still needs confirmation — so the stub has to
be *told* to produce them. That instruction used to be a system property:
`debug.stub_auth.signin_failure`, `debug.stub_auth.unconfirmed_signup`, and two more,
read by `StubAuthRepository` through
`Runtime.getRuntime().exec(arrayOf("getprop", ...))` and set by a Maestro flow through
the bridge's `setprop` command.

**The property path never had a green run behind it.** The three flows that drive the
stub through a flag (`02-signin-wrong-password`, `03-signup-toggle`,
`06-signup-unconfirmed`) had been failing since the calibration of 2026-10-22, and the
failure was filed as debt against the app (`config/docs/maestro-debt.yaml`, the causes
`auth_error_text is visible` and `auth_confirmation_headline is visible`).

The failure turned out to be somewhere else entirely: Maestro 2.10 drops the selector
from an `inputText` command, so the flows typed the password into the *email* field, the
form stayed invalid, the sign-in never fired, and the error text never appeared
(ADR `2026-10-25-maestro-drops-the-selector-from-inputtext`). The flag was never read
on a path that reached a sign-in — so **nothing has ever shown that the property read
works**, and nothing has shown that it does not. The read swallowed its failures
(`catch (_: Exception) { false }`), which is why the question could not be answered from
the outside: a flag that reads "off" and a flag that is not set look the same.

So the property path is *unproven*, not *proven broken*. That is enough to replace it:
a channel that has never been shown to work is not a channel to build on, and the
replacement has to be proven anyway.

## Idea

The stub and the flow need one channel, and the repository already has one that works:
the app's own data directory. `run-as` reaches it from the host, the damage-the-store
flow already uses it, and the bridge policy already allows it. A flag file is readable by
the app by path and writable by a flow through `run-as`.

## Decision

**A flag is a file in the app's own data directory.** `TestAuthStubs.flagFile(context,
flag)` names it — `files/e2e-stub-auth-<flag>` — the stub reads it lazily on every call,
and the Maestro helper `Maestro/helpers/scripts/set-stub-flag.js` writes it through
`run-as`.

Three properties of the read are deliberate:

- **A missing file means "off"**, and is not an error. It is the state every flow starts
  in, and the common case must not produce noise.
- **A file that is there but cannot be read is logged.** A flow asked for something and
  the build did not get it; a silent "off" would send the flow looking for a defect in
  the app.
- **The flag is read on every call, not cached**, so a flow that writes it takes effect
  without restarting the app — the property's one working property, kept.

The system-property mechanism is gone from the stub. The bridge policy keeps its
`setprop` shape: the policy lists what the bridge *may* run, not what the flows happen
to run, and a shape is a privilege a flow author should not have to remove to find a
better channel.

## Rationale

The alternative — making the property work — means reading a system property from an
app process, and there is no supported way to do that: `Runtime.exec("getprop")` is not
guaranteed to work from an app process, and the supported route
(`android.os.SystemProperties`) is a hidden API the platform restricts. A file needs no
hidden API, no permission and no platform cooperation.

The stub's flags are not a secret and do not need to be hidden: they say "fail the next
sign-in", not "this password is wrong". The file lives in the app's private data
directory, where only the app and `run-as` can reach it.

## Consequences

- Flows 02, 03 and 06 drive the stub through a flag file, and all three pass on a
  device (RMX3938, API 35) — the channel has a green run behind it, which the property
  path never had. Flow 03 did not drive the stub at all before: it asserted the
  confirmation screen while the stub established a session, so it could not have passed
  however the property was set.
- `Maestro/helpers/scripts/setprop.js` is gone. The script it replaces is
  `set-stub-flag.js`, and the name says what it does.
- The three auth causes in `config/docs/maestro-debt.yaml` that these flows carried are
  marked fixed — they are the debt this change pays back, and the registry is where a
  cause is acknowledged as gone.

## Links

- `docs/decisions/2026-10-25-maestro-drops-the-selector-from-inputtext.md` — why the
  flows that drive the stub were failing before, and why the property read was never
  exercised.
- `docs/decisions/2026-10-14-stubauthrepository-jvmmain-boundary.md` — the stub stays in
  `androidMain`, and the property mechanism was one of the reasons.
- `docs/decisions/2026-10-20-a-maestro-script-cannot-reach-the-host.md` — the bridge is
  the only way out of the Maestro sandbox.
- `docs/decisions/2026-10-28-the-bridge-policy-is-a-file.md` — what the bridge may run
  is a policy question, answered in `config/maestro/bridge-policy.yaml`.
