# Design: The data survives being unsure about it

Related ADR: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## The probe

The probe asks one question: does the application's bookkeeping marker exist? The current
implementation treats "no" and "could not tell" as the same answer, and answers "delete".

The fix is to separate them. Three outcomes, not two:

| Outcome | Meaning | Action |
|---|---|---|
| The marker is there | The file is the application's | Leave it |
| The file is a valid database and the marker is not | The file provably is not the application's | Delete it and its companion files |
| Anything else | Doubt | Leave it, record what was seen |

"Anything else" covers a locked file, an unreadable file, a corrupted file, an empty file, a
busy database, and a missing permission. Each of those is a state in which the file *might*
be the user's data, and none of them is evidence that it is not.

The distinction that matters for the test suite: the guarantee is about **contents**, not
about the file's existence. A test that checks "the file is still there" passes today for
the wrong reason and fails to catch a probe that empties the file before deleting it. Every
ambiguous case is asserted byte-for-byte.

## The profile selector

Two arguments, two meanings, never both:

- **profile id** — the new mode. The tool server resolves profiles against a fixed
  repository instead of the stored one. Because the dependency bindings are lazy, a
  repository that never resolves the preferences store never causes the preferences store to
  be constructed, so its file lock is never taken. That is the mechanism, and it is asserted
  by a test rather than by argument.
- **profile label** — the legacy mode, unchanged, because client manifests depend on it. It
  is logged as deprecated.

An unknown id is an error, not a fallback. A fallback would mean a process that asked for
one profile's data reads another profile's data, and neither the user nor the process would
know.

### The module shape

The profile bindings are extracted so that the tool server can substitute them. The
extraction must be a **list composition**, not an inclusion: an included module creates a
child scope, and the bindings in a child scope are invisible to sibling modules at the
parent level. That failure has already happened once in this repository, and the bindings
were inlined to escape it. This change re-extracts them the same way — added to the list.

## The classification

The registry entry gains a write behaviour with **no default value**. A tool added without
choosing one does not compile. That is the whole mechanism: the failure mode is a red build
rather than a tool that quietly writes.

The registry already matches the exposed set exactly, and an existing check enforces it.
This change extends that check rather than adding a second one, because a second check
would be a second thing to keep right.

The direct-write exceptions are stated with their reasons in the registry, so the
guarantee can be audited without reading tool code:

| Tool | Reason |
|---|---|
| write decision record | Writes a decision file, not user data. |
| update decision record | Writes a decision file, not user data. |

> **Amended (2026-10-23):** there were three exceptions, and `create tag` was one of
> them — "a tag is not user content, a proposal for one is noise". The tool had been
> proposing for a while and the declaration had not caught up; a tag is user content
> after all, so it is a proposal-gated tool now. See
> `docs/decisions/2026-10-23-the-agent-surface-says-what-it-does.md`.

Both are decisions about *this* application's data model, not about protocols.
If either is later judged differently, it is a one-line change in the registry plus the
reasons — which is why the reason is a field rather than a comment.

## The error responses

Error responses are assembled by the serialiser instead of by string interpolation, and the
message a caller receives is neutral while the detail goes to the log. The two are separate
concerns and were conflated: a malformed response and a leaky one are different defects
with different fixes.

## Rollback risk

**Low, and it is three separable changes.** The probe change is a behavioural change to a
startup path — if it misfires, the app leaves a stale file it would previously have cleaned
up, which is a recoverable annoyance rather than data loss. The profile change is additive:
the legacy argument keeps working, and the new one is opt-in. The classification change is
additive in the same way: a field with a required value, and the registry is a table.

The one thing that is **not** trivially reversible is the classification itself: once tools
declare their behaviour, moving one between categories is a deliberate act with a reviewable
reason attached. That is the intent.
