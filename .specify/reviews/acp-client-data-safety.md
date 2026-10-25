---
change: acp-client-data-safety
verdict: pass
date: 2026-10-25
pr: https://github.com/gazon1/sing/pull/1028
reviewed: 063343b4f84626f3158db42bda01784bf54f6cec
documents: sha256:e35f288d483c6e92e8d62dfc102016e983a65a06d8e6357a2756b2527001ed6c
---

## What was reviewed

The data-safety half of the feature: the startup probe, the profile
isolation, and the write classification.

- `shared/src/jvmMain/kotlin/com/singularity/todo/core/database/contract/StaleDbCleaner.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/di/Modules.kt` and the
  per-domain DI modules
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerMain.kt`
- `scripts/check-ai-write-tools.py` and `config/docs/ai-direct-writes.txt`

The review was a second agent's, given the diff and the decision record rather
than the session that wrote it.

## What was found, and what was done about it

**The claim about the profile isolation was false.** A tool server told which
profile to serve is supposed never to open the store that holds the active
profile — the application is holding that file. The profile repository was
built without a store, so that part held. But two of the tools took the whole
settings repository, and the settings repository's store is the *same file* the
active profile id lives in. So the tool server opened it after all, and the
guarantee the design rested on was a comment rather than a fact.

The tools now read their one setting — the staleness threshold — through the
narrow `TasksSettingsRepository` port, which is what keeps the store out of the
graph: a tool that asked for the whole settings repository would pull the store
in with it. A fixed-profile process answers with the setting's default. The
price is that an agent's notion of staleness in a fixed-profile server is the
default and not the user's, and the price is stated in the decision record
rather than left to be discovered.

**The isolation was still only half true, and the second review said so.** The
narrow port kept the *tools* from pulling the settings store in — but the
server opens that store anyway, for the AI configuration the LLM-backed tools
run on, and the active profile id lived in the same file. So the guarantee
("a process told which profile to serve never opens the application's stored
active-profile state") was a fact about a *key* — the server never read it and
never wrote it — and not a fact about a *file*, which is what the rule says.
A rule satisfied by an intention rather than by a fact is not a rule.

The active profile id has a file of its own now:
`profile_state.preferences_pb`, holding nothing else. A process told which
profile to serve never resolves that store, so the file that says which
profile the user is working in stays closed to it; and the settings file it
does open no longer says anything about the profile at all. A user who ran the
previous version has the key in the old file, so the first read after the move
looks in the new file and, if the key is not there, looks in the old one once,
moves it, and takes it out of the old file.

**The probe deleted the write-ahead log even when it could not delete the
database.** The rule is that the companion files go only when the database has
gone — a `-wal` holds the transactions that were committed but not yet
checkpointed, so removing it while the database survives loses committed data.
The delete can fail while the file remains (a file held open by a running
application, a read-only file), and the companions went regardless. The
database is now deleted first, and the companions follow only if it is gone;
the function returns the real outcome instead of claiming a deletion that did
not happen.

**One start-up step escaped the "fail in one place" discipline.** Resolving the
tool list builds every collaborator the server has, and any of them can fail. An
exception there escaped `main`: a stack trace on standard error and an exit,
with nothing on the protocol channel at all — so a client waiting for an answer
saw the connection close and no reason. It now says so on the protocol channel
like every other start-up failure.

## The finding that was open, and what closed it

The verdict was a `fail` for one reason, and this is what closed it.

**The "all writes ask first" promise was verified for two of the twelve
proposal-gated tools.** The catalogue declares twelve tools as proposal-gated,
and the behavioural contract test drives two of them — a creation and a
deletion — through the whole round trip, asserting that declining leaves the
user's data unchanged. The other ten are declarations with no test. The
declarations are truthful today; nothing keeps them that way. A tool that
declares itself proposal-gated and writes on its own would break the promise
the whole surface makes, and the only thing standing between that and the user
is a list.

**`agent-surface-follow-ups` closed it.** The classification is published in the
exported tool surface, the check reads the declaration against the tools'
behaviour in both directions, and every proposal-gated tool is driven through
the round trip — the aggregate check is what stops a fourteenth tool from
shipping with no contract. That change's own review passed, and this verdict
names it rather than re-deciding what it decided.

**And four of this change's own scenarios had no test.** A scenario in a spec
delta that nothing drives is a promise nobody kept, and four of this change's
scenarios had that shape:

- `REQ-DBSAFE-002` — *a locked file is kept*. The database is held by another
  connection while the probe runs; the file survives byte for byte, and the
  control at the end of the test says the very same file is deleted once the
  lock is gone. The lock was the only difference, and the difference has to be
  enough;
- `REQ-DBSAFE-002` — *a file the probe cannot read is kept*. And the record it
  leaves says it could not read the file, rather than that the file was not a
  database — which is what it used to say about a file it had never managed to
  read;
- `REQ-MCPSRV-010` — *the stored profile state is never opened*. A
  fixed-profile graph with a store that cannot be built: the repository is
  built, so the store was never asked for. The control is the same graph
  without a fixed profile, which asks for the store and is refused;
- `REQ-MCPSRV-011` and `REQ-MCPSRV-012` — *an unknown profile id is refused*,
  and *the legacy label still works*. Both decisions are values now, and the
  lookup the first one asks is a parameter, so the rules can be checked without
  a database.

## The second round

The closing work was reviewed by a second agent, and the round returned a `fail`
with two blocking findings. Both were about the *test*, not about the behaviour,
and both are closed:

- **The record the probe leaves had no test that could fail.** The probe's
  outcome was a `Boolean` and the reason it left was a line in a log, so
  reverting the tri-state read to a boolean would have left every test green:
  the file would still be kept either way. The probe's outcome is internal now,
  and the test asserts the *reason* — the two records apart, so the change is
  visible;
- **The unreadable case could not detect the breakage it named.** It drove a
  non-empty directory, and a file delete cannot remove one, so a probe that had
  decided to delete the file would have left the directory standing and the
  test green. It drives a symlink to a directory now — a path that cannot be
  read as a file and that *can* be deleted — so its survival is the probe's
  decision and not the file system's refusal.

The second round's other observations are taken in the change: the locked case
now asserts the record as well as the survival, the checklist's verification
command names the desktop app's test task, and the archive ADR says what it
means to say.

## How the fixes were checked

- The probe's new test makes the delete fail for real, and does it in a way that
  is about the rule rather than about the machine: the "database" is a
  directory that is not empty, which a file delete cannot remove whatever
  rights the process has — so the test does not quietly stop testing anything
  on a machine that happens to run as root. It asserts that the database and
  both companions survive. The rule itself is now internal rather than private,
  because the public entry point cannot reach the branch the rule is about:
  the probe has already decided the file is a database by then.
- The active profile's own file has a test of its own: a profile stored in the
  old file is moved into the new one and taken out of the old one, a profile
  already in the new file is read from there, nothing stored anywhere means
  the default profile, and writing a profile leaves the old file alone. The
  key's *name* is spelled out in the test rather than imported, because the
  name is the contract — the old file of a user who ran the previous version
  holds that key, and the move only finds it if the name is the same one.
- The decision record and the architecture notes were extended to match, and
  the digest regenerated.
- The closing work was checked by a second agent, and its two blocking findings
  were closed and checked again: the second round returned a `pass`.
- The full shared suite: 4378 tests, 3 failures — and the three are the same
  three `main` fails at the same commit (`CapabilityFlagTest`,
  `TextFieldShapeConventionTest`, `TextFieldRewriteContractTest`), so none of
  them is this change's.
