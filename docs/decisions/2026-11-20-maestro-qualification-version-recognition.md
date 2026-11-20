---
title: "Maestro flow qualification — a version counts as detected only when it is recognised"
date: 2026-11-20
status: superseded
superseded-by: 2026-11-20-maestro-qualification-strict-version
tags: [maestro, qualification, runner, evidence]
issue: 3052
---

> **Superseded (2026-11-20) by
> `2026-11-20-maestro-qualification-strict-version`.** The decision stands — a
> version is recognised, not merely present — but the recognition it describes
> was implemented with prefix matches, applied to only one of the two engines,
> and lived in the qualification runner. It now lives in one place and matches
> whole lines.

## Context

The engine version is a prerequisite of admissible evidence: the gate rejects a
record without it, and the runner refuses the run before the first attempt when
it cannot be detected.

The version was read by a function that ended with `return lines[0] if lines
else None`. Any output at all became a version: "Maestro version unavailable"
was recorded as the version the attempt ran on. A prerequisite that any output
satisfies is not a prerequisite — and the version exists precisely because the
evidence has to name the build that ran the attempt.

The same function was loose in the other direction: it searched any line
containing "maestro" for a number, so a build number in a message about a
missing version, or the JVM's version in a warning printed above the banner,
could be read as the engine's version.

## Idea

1. **Accept any non-empty output.** A version is a version; the field just has
   to be filled.
2. **Recognise the version, or find none.** A version is read from the engine's
   own banner, or from a line that is a version number and nothing else.
3. **Require the whole output to look like a version.** Strict, but it would
   refuse a banner with a warning above it.

## Decision

**Option 2.**

`scripts/maestro-qualify-flows.py` reads the version from:

- the engine's own banner — `Maestro 1.39.0`, `Maestro version 1.39.0`,
  `maestro-runner 1.2.3` — and takes the number that follows the engine name;
- or a line that is a version number and nothing else (`1.39.0`), for builds
  that print the bare version.

Anything else yields no version, and the run refuses: the version stays a
prerequisite instead of a field filled with whatever the tool printed. A number
that belongs to something else is not the engine's version — the JVM's version
above the banner, or a build number inside a message about a missing version,
is left alone.

## Rationale

- **The prerequisite has to be worth something.** A version that any output can
  satisfy is a string in a record; the gate then admits evidence that names a
  build nobody ran. Failing closed costs one refused run; failing open costs a
  qualification that means nothing.
- **Recognition beats presence.** The engine's banner is a known shape, and the
  bare-version line is a known shape. Anything else is a message, and a message
  is not a version.
- **Fail-closed is already the runner's habit.** The runner refuses an
  untracked dependency and a baseline that does not match its pin, and it
  refuses a run with no detectable version at all. Refusing a version it cannot
  read is the same rule applied one level deeper.

## Consequences

- A machine whose `--version` output names no version qualifies nothing. The
  run says so and exits with code 2 before the first attempt, so no device time
  is spent on evidence nothing can accept.
- An engine build that prints its version in an unrecognised shape has to be
  recognised explicitly, or it cannot qualify flows. That is the intended
  reading: the environment the evidence names is the environment the attempts
  ran in.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-version-prerequisite.md`,
  `docs/decisions/2026-11-20-maestro-qualification-attempt-floor.md`
