---
change: acp-client-data-safety
verdict: fail
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/1028
reviewed: 277e4d17b899cdf7c76c7e92e7f18738135f8a1d
documents: sha256:f176542a71479d4a1cd88e8245ac27e8d35d19c516ec7258a5fecd41bbb1d94f
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

## What is still open — why this verdict is a fail

One finding is not fixed, and it is the reason this verdict is not a pass.

**The "all writes ask first" promise is verified for two of the twelve
proposal-gated tools.** The catalogue declares twelve tools as proposal-gated,
and the behavioural contract test drives two of them — a creation and a
deletion — through the whole round trip, asserting that declining leaves the
user's data unchanged. The other ten are declarations with no test. The
declarations are truthful today; nothing keeps them that way. A tool that
declares itself proposal-gated and writes on its own would break the promise
the whole surface makes, and the only thing standing between that and the user
is a list.

This is the follow-up work already scheduled for the write classification:
publishing the classification in the exported tool surface, making the gate
check the declaration against the tools' behaviour, and covering every
proposal-gated tool with the contract the two representatives have today. The
finding is recorded here so that the next session does not have to rediscover
it, and so that a reader of this verdict knows the guarantee is currently held
by a list and by two tests rather than by the whole set.

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
