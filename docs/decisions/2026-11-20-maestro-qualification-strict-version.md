---
title: "Maestro flow qualification — one strict recognition for both engines"
date: 2026-11-20
status: accepted
tags: [maestro, qualification, runner, evidence]
issue: 3052
supersedes: 2026-11-20-maestro-qualification-version-recognition
---

## Context

The previous decision made the engine version a *recognised* value: a version is
read from the engine's banner, or from a line that is a version number and
nothing else, and everything else names no version.

The implementation did not hold to that decision on two counts.

The `maestro-runner` engine did not go through the recognition at all. The
qualification runner asked the engine helper for its version, and the helper
searched any output for the first `\d+\.\d+\.\d+` it found, without checking
the exit code. A build that printed a JVM's version, or that failed with a
message carrying a number, produced a version — for the one engine whose
version was the hardest to establish.

The recognition itself matched prefixes, not lines. `1.39.0 is not an engine
version`, `Maestro 1.39.0 version unavailable` and `maestro-fake-engine 9.9.9`
were all read as versions. The decision said "a version and nothing else"; the
regex said "a version at the start of a line".

## Idea

1. **Keep the helper, loosen nothing.** The runner engine keeps its own
   version helper, and the qualification runner accepts whatever it says.
2. **Recognise more shapes.** Accept any line that starts with a version, and
   any engine name that starts with "maestro".
3. **One recognition, both engines, whole lines.** The engine build is probed
   the same way for both engines, and the answer is recognised as *that
   engine's* version by whole-line rules.

## Decision

**Option 3.**

The recognition lives in one place — `emulator.maestro._engine` — and both
consumers use it:

- `parse_engine_version(output, engine)` reads a version from the engine's own
  banner (`Maestro 1.39.0`, `Maestro version 1.39.0`, `maestro-runner 1.2.3`,
  with optional trailing details in brackets), or from an output that is a
  version number and nothing else (`2.10.0`). Both are whole-line matches, and
  the banner has to name the engine that was probed: `maestro-runner 1.2.3` is
  not the CLI's version, and `maestro 1.2.3` is not the runner's.
- `engine_version(engine, cwd)` asks the build for its version and returns it
  only when the build exited successfully *and* the answer is recognised. A
  build that fails has named no version.
- The qualification runner probes the selected engine through that same
  function — no engine bypasses it, and there is no second, looser way in.
- `runner_version()` — the helper the CLI uses to log which build produced a
  run — is now `engine_version()` for the runner, so the diagnostic path and
  the evidence path cannot drift apart.

A version is recognised or it is not: there is no fallback to "the first number
in the output".

## Rationale

- **The evidence has to name the build that ran the attempt.** A version read
  from a JVM warning, from a build number in an error message, or from a
  different engine's banner names a build nobody ran — the exact failure the
  prerequisite exists to prevent.
- **Whole-line rules are the shape of a version.** A version that shares its
  line with other words is part of a sentence, and a sentence is not a
  version. The engine's name is part of the sentence that makes it the
  engine's version.
- **One recognition cannot drift.** The loose helper and the strict reader
  were two implementations of one rule, and they already disagreed.
- **Failing closed is cheap here.** An unrecognised build refuses the run
  before the first attempt; it costs a refused run, not a wrong record.

## Consequences

- A build that prints its version in a shape the rules do not know qualifies
  nothing, and says so. The CLI's bare version (`2.10.0`) and the runner's
  named banner (`maestro-runner 1.1.31`) are both known shapes.
- The runner's helper now checks the exit code, so a failing `maestro-runner`
  logs "unknown version" instead of a number from its error output.

## Links

- Issue: https://github.com/gazon1/sing/issues/3052
- Policy: `docs/testing/maestro-flow-qualification.md`
- Recognition: `scripts/src/emulator/maestro/_engine.py`
- Runner: `scripts/maestro-qualify-flows.py`
- Related: `docs/decisions/2026-11-20-maestro-qualification-version-prerequisite.md`,
  `docs/decisions/2026-11-20-maestro-qualification-version-recognition.md`
