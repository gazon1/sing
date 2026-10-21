---
title: "The bridge policy is a file, and the cleanup scope is a wipe: a boundary is a whole thing, not a list of names"
date: 2026-10-28
status: accepted
tags: ["e2e", "maestro", "tooling", "security"]
---

## Context

Two boundaries in the Maestro runner were lists of names, and both of them had
to be kept in step by hand.

**The bridge's whitelist.** The host bridge is the only channel a Maestro
script has to the device's shell (`2026-10-20-a-maestro-script-cannot-reach-the-host.md`).
It runs a device command through `adb -s <serial> shell` and refuses anything
that is not one of three shapes hardcoded in `scripts/src/emulator/maestro/_bridge.py`:

```python
_ALLOWED_SHAPES = (
    re.compile(rf"^run-as {re.escape(APP_ID)} sh -c '[^']*'$"),
    re.compile(r"^setprop [A-Za-z0-9._-]+ [A-Za-z0-9._-]*$"),
    re.compile(r"^getprop [A-Za-z0-9._-]+$"),
)
```

Every new need a flow acquires — `settings put`, `pm clear`, `am start` — is a
code change in a module whose whole job is to be the narrow waist of the
bridge. The set of commands a *test* runner may execute is a policy question,
and the policy was expressed as a Python literal.

**The narrow cleanup scope.** The runner used to sweep between flows with a
list of title prefixes:

```python
encoded = urllib.parse.quote(f"{run_id},Buy milk")
cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"
```

The list had one fixed title in it. The suite creates rows under at least five
more — `pokupki`, `renameme`, `savedalpha2026`, `seed_task`, `write_spec` —
and those rows were never swept (`2026-10-21-each-flow-gets-a-clean-device.md`).
The scope was therefore wrong by construction, and wrong *silently*: a flow
that asserts an empty list fails on the previous flow's data, and nothing in
the report says so.

The audit of who used the narrow scope: **nobody but its own tests**. The CI
driver deliberately does not pass a scope (`2026-10-20-the-local-e2e-run-measures-the-binary-ci-measures.md`),
no `just` recipe passes one, and `scripts/run-maestro-e2e.py` leaves the
default in place.

Both boundaries have the same shape of defect: a boundary written as a list of
names has to be maintained by a human, and the maintenance fails silently.

## Idea

A boundary that cannot be enumerated by hand should be expressed as a whole
thing. The bridge may run a command if the **whole** command is one it knows;
the runner may clean the device if it wipes **all** of it. Neither boundary
needs a list of names to be maintained, and neither can be widened by
appending one line.

## Decision

**1. The bridge's whitelist is a file.**
`config/maestro/bridge-policy.yaml` lists the shapes the bridge may run. Each
entry carries an `id`, the `pattern` that describes the shape, and the `why` it
is allowed — the reason is part of the entry, because a shape nobody can
justify is a shape nobody checked. Adding a shape is an edit to that file and
nothing else: no Python change, no new branch in the matcher.

**2. The rule stays in the code: the whole command, not the prefix.**
A command is allowed only when the **whole** command matches one of the
policy's patterns. The matcher is `re.fullmatch` against the normalized
command — never `re.match` (a prefix) and never `re.search` (anywhere). The
policy declares `match: whole`, and the loader refuses any other value, so
the rule cannot be relaxed by editing the file.

The reason is the shape of the attack a prefix check opens: `run-as
com.singularity.todo ; id` starts with an allowed command, and the device's
shell would run the second half as the **shell** user rather than the app's.
Prefix matching is "allowed almost everything", which is exactly what a
whitelist exists to exclude. The positive controls are in the tests:
`run-as … sh -c 'ls' ; id`, `run-as … sh -c 'ls' extra`,
`setprop … true extra` and `am start …` are all refused.

The rule has a second half, for the same reason: a pattern may not contain
`.*` or `.+`. `run-as com.singularity.todo .*` is a prefix match in disguise —
it names no end, and the whole-command rule cannot save a pattern that does
not say where the command stops. A variable part of a shape is written as a
negated class (`[^']*`), which cannot run past what it names. The loader
refuses such a pattern.

**3. The policy fails closed.** A policy that cannot be read, cannot be parsed,
asks for another match mode, or names another app is refused, and a bridge
whose policy is broken runs *nothing*. The runner reads the policy before it
acquires the device, so a broken policy stops the run instead of failing every
flow that needs the bridge, one by one.

**4. The narrow cleanup scope is deleted.** `--maestro-cleanup` offers `all`
(the default — a content wipe before every flow) and `off`. The narrow scope
is gone from the CLI, from the implementation and from the tests, and the
hand-written list of titles is not carried over anywhere.

This supersedes one statement of
`2026-10-21-each-flow-gets-a-clean-device.md`: that the narrow scope "stays
as an option". Its reasoning — *a list of titles is the wrong boundary* — is
kept and taken to its conclusion: a scope whose boundary is a list of titles
that has to be maintained by hand is not an option worth keeping.

## Rationale

- **Why the file and not a Python constant.** The set of commands grows with
  the flows, and the flows are not the same people as the bridge. A policy
  that can be reviewed, diffed and argued about as data is easier to keep
  honest than a tuple of regexes inside the module that enforces it.
- **Why `config/maestro/bridge-policy.yaml` and not inside the package.**
  `scripts/src/pyproject.toml` declares its packages explicitly and declares
  no package data, so a YAML file inside `emulator/maestro/` would be missing
  from a non-editable install — and the repository already keeps its
  declarative configuration outside the packages (`config/docs/`,
  `config/detekt/`, `config/legal/`, `config/test-fixtures/`). The policy is
  configuration; it lives where the other configuration lives.
- **Why the whole command, and not the prefix.** The prefix is where the
  privilege changes: the allowed command ends where the quoted argument ends,
  and everything after that is the shell's. A rule that only looks at the
  beginning of a string cannot see that boundary, and the boundary is the
  whole point of a whitelist.
- **Why the narrow scope goes rather than gets a derived list.** The fixed
  titles *can* be derived from the seeds (`todo-debug://seed?task=…`), but not
  from the typed ones: `inputText` carries titles, search queries and
  credentials alike, and a derived list would either miss titles — the same
  hole, with more machinery — or sweep rows the flow never created.
  Deriving the list would also keep a scope that has no user: the wide scope
  is the *same* one `adb` call, so "narrower and faster" was never true, and
  the wide scope already isolates the flows from each other.
- **Why the wide scope stays.** It knows every row, including the rows nobody
  remembered to name. It has gaps of its own — archived rows and saved
  agendas are out of its reach (`2026-10-21-each-flow-gets-a-clean-device.md`)
  — but a gap in a wipe is visible as a leak in one place, while a gap in a
  list is a silent hole in the isolation between every pair of flows.

## Consequences

- `--maestro-cleanup run` is no longer accepted: the CLI answers with a usage
  error (exit code 2). Nothing in CI, in the `just` recipes or in
  `scripts/run-maestro-e2e.py` passed it, so no runner changes.
- `cleanup_run_fixtures` keeps its four-argument shape. The `run_id` it
  carries is no longer read — the surviving scopes do not name individual
  rows — but the arity of the seam is asserted by
  `scripts/tests/test_device_contracts.py` and every fake in the tree passes
  four arguments, so changing it would churn every call site for no
  behavioural gain.
- Adding a shape to the bridge is now: one entry in the policy file, plus a
  test. It remains a deliberate decision about what the bridge may do, and it
  is no longer a decision about the Python.
- `Maestro/helpers/launch-clean.yaml` and the maestro skill still describe the
  narrow sweep. Both are outside this change; they need a follow-up that says
  the runner wipes the content before every flow.

## Links

- `2026-10-20-a-maestro-script-cannot-reach-the-host.md` — why the bridge
  exists at all.
- `2026-10-21-each-flow-gets-a-clean-device.md` — the content wipe, and the
  statement this decision takes further.
- `2026-10-20-the-maestro-cleanup-never-matched.md` — the night the broken
  sweep was measured.
- `2026-10-20-the-local-e2e-run-measures-the-binary-ci-measures.md` — why the
  CI driver pins no cleanup scope.
