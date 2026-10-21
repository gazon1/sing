---
title: "A name nobody imports is not an API: the stale alias is a patch target that does nothing"
date: 2026-10-21
status: accepted
tags: [tooling, emulator, tests]
---

# A name nobody imports is not an API: the stale alias is a patch target that does nothing

## Context

The script-tooling audit (F8, F12) found dead exports in the `emulator` package and
one script with no caller. The deletions were mechanical; one of them was not, and
the reason is worth writing down.

`scripts/src/emulator/__init__.py` exported, among 29 names, five UPPER_CASE
aliases of the configuration singleton:

```python
AVD = cfg.AVD
LOCK_FILE = cfg.LOCK_FILE
EXTRA_FLAGS = cfg.EXTRA_FLAGS
BOOT_TIMEOUT = cfg.BOOT_TIMEOUT
LOCK_TIMEOUT = cfg.LOCK_TIMEOUT
```

A comment said why: *"Tests patch these directly: `emulator.AVD = 'Pixel_7'`."*

**They did not.** The production code reads the configuration through the
singleton — `_emu.cfg.LOCK_FILE`, `_emu.cfg.AVD` — and the aliases are
*copies*, taken once at import time. So `emulator.LOCK_FILE = lock` changed a
module-level name that nothing read, and the code went on using
`cfg.lock_file`: the shared `/tmp/ensure-emulator.lock`.

Three tests patched it that way (`scripts/tests/test_ensure_emulator.py`):

- `test_acquires_and_releases_cleanly` — took the lock in a temp directory that
  was never used;
- `test_second_caller_blocks_then_times_out` — the interesting one: it started a
  holder thread and waited on a barrier for it. The holder never reached the
  barrier, because the lock it waited for was the *real* one, held by whatever
  else was running. The test failed with `BrokenBarrierError` — or passed, when
  it got the shared lock first;
- `test_device_boots_within_timeout` — read the defaults from the stale copy
  and restored them into the live config.

So the alias was not an unused name. **It was a patch target that silently did
nothing**, and the tests that used it exercised the real, shared lock file
instead of the isolated one they asked for. That is where the cross-checkout
flake came from: a lock at `/tmp/ensure-emulator.lock` is shared by every
checkout of this repository on the machine, so a maestro run in one checkout
could make a test in another fail with `EXIT_LOCK(4)` where it meant
`EXIT_CRASHED(2)`.

The same round found the other half of the story: `scripts/maestro-flow-tags.sh`
— a shell implementation of the Maestro tag reader — had no caller left. The
runner moved to Python and took the file's job with it, and two Python readers
kept a copy of the rule each. Three copies of one question, one of them in a
language nothing else in the chain used.

## Idea

**A name nobody imports is not an API.** It is a second copy of the thing it
re-exports, and a copy is the one that drifts.

**A patch target that nothing reads is worse than a missing name.** A missing
name fails loudly at the call site; a stale one accepts the patch and goes on
doing the old thing. The tests above were green, and green was a lie about what
they measured.

So the fix is not "delete the unused names". It is: **delete the copies, and
point every reader at the one that is read.**

## Decision

**One configuration, one reader of a flow's tags, and no name that exists only
to be patched.**

- **The configuration lives in `cfg` and only in `cfg`.** The five UPPER_CASE
  aliases are gone from `emulator/__init__.py`. The `Config` class keeps its
  own `AVD` / `LOCK_FILE` properties — those are read by the production code
  and are a spelling of the same field, not a copy of it.
- **The tests patch what the code reads.** `ee.cfg.lock_file = lock` instead of
  `ee.LOCK_FILE = lock`, and the defaults they restore are read from `ee.cfg`.
  The lock tests now use the temporary file they always meant to use, and the
  suite got faster: the emulator test module went from 5.0 s to 1.2 s, because
  it stopped contending for a lock nobody could see it waiting for.
- **The dead exports are gone**: `adb`, `adb_on`, `list_avds`,
  `probe_device_ready`, `Acquisition`, `acquire_device`, `ensure_device`,
  `Config` — no module imported them from the package. The functions stay where
  they live (`emulator._device`, `emulator._startup`); only the re-export is
  gone.
- **The Maestro tag reader is one** — `scanners/maestro_flow.py`, reached by
  the runner, the traceability scanner and the suggester. The shell copy is
  deleted, and the corpus properties its test asserted — every flow declares a
  tag, every declared tag selects its own flow, the smoke suite CI asks for is
  not empty — are asserted against the reader the runner uses.
- **`scripts/measure-baseline-composition.py` has a caller.** It is `just
  baseline`, and the consumer is named: a human reading a detekt baseline
  change, who needs to know which of the suppressions belong to a rule that can
  report at all.

## Rationale

- **The deletion is what found the bug.** Counting importers would have said
  "the aliases are used — the tests use them". Following the name to the code
  that reads it is what showed that nothing did.
- **A test that patches a name nothing reads is a test of the wrong thing.**
  The lock tests did not test the lock: they tested the shared lock, by
  accident, and their green said nothing about isolation.
- **One reader per question, in one language.** Three copies of the Maestro
  tag rule — two in Python, one in shell — meant three places to fix the next
  tag bug, and the shell one was already unreachable from the runner.
- **A measurement tool with no caller is a guess about its own value.** Either
  the question is asked often enough to deserve a command, or the command is
  noise. The baseline composition question is asked every time a baseline
  changes, and it was answered by hand once and got the wrong answer.

## Consequences

- `from emulator import adb` and friends no longer work. Nothing imported them,
  and the functions are still one import away in their own modules.
- `emulator.AVD` is gone. `emulator.cfg.AVD` is the same value, and it is the
  one the code reads.
- The lock tests are now isolated: they take a lock in a temporary directory
  instead of the shared `/tmp/ensure-emulator.lock`. A maestro run in another
  checkout can no longer make them fail.
- `scripts/maestro-flow-tags.sh` is gone; `scripts/tests/test_maestro_flow_tags.py`
  is gone, and its properties live in `scripts/tests/test_maestro_flow_reader.py`,
  asserted against the reader the runner uses.
- `just baseline` prints the composition of the detekt baselines. It is a
  report, not a gate: nothing fails when the numbers change, because the
  question it answers is "where are we", not "are we allowed to".

## Links

- `docs/engineering/script-tooling-audit.md` — the audit; F8 (dead exports) and
  F12 (the script with no caller).
- `2026-10-21-one-implementation-per-question` — the round that made the
  Maestro tag reader one; this round removes the last copy of it.
- `scripts/src/scanners/maestro_flow.py` — the reader that stayed.
- `scripts/src/emulator/_types.py` — the configuration, and the only copy of it.
