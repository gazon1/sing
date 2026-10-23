# The data survives being unsure about it

Issue: — · Related: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## What

Three ways this app can lose the user's data or their identity, all of them present today,
before any external agent exists.

**The startup probe deletes the database when it is unsure.** On every start, the app opens
its database file with a bare driver and asks one question: does the bookkeeping table exist?
If the answer is anything other than a clean "yes" — a locked file, a permission error, a
corrupted file, an empty file, a busy database — the app concludes the file is not its own and
deletes it, together with its companion files. A corrupted file might be the user's data. The
probe cannot tell, so it deletes.

**A second process can switch who the user is.** The tool server takes a profile argument.
The argument is a *label*, only one label does anything, and any other value is silently
ignored. The active profile lives in a preferences store that takes a file lock, so a second
process touching it conflicts with the application.

**No tool says how it writes.** The app's promise is that writes to the user's data go
through a proposal the user confirms. The promise is true for the tools that were routed
through the proposal pipeline. Nothing records which tools those are, so the promise cannot
be checked, and a new tool added tomorrow is unchecked by default.

This change fixes all three, before any external process exists to trigger them. It is
independent of the external-agent feature and lands on its own.

## The change in behaviour

- The startup probe **deletes** the database only in the one unambiguous case: the file is
  readable, it is a valid database, and it lacks the app's bookkeeping marker. Every
  ambiguous outcome — locked, unreadable, corrupted, empty, busy — leaves the file and its
  contents exactly as they were, and records what it saw instead.
- The tool server takes a **profile id** as well as the legacy label. In the id mode it works
  against that profile without opening the stored active-profile state at all, so the
  application's own profile is untouched and no lock conflict is possible. An unknown id is
  a clear failure, never a silent fallback to somebody else's data.
- The legacy label argument keeps its meaning, because distributed client manifests already
  pass it. It is marked deprecated rather than repurposed. Passing both arguments is a
  startup error, because the request is ambiguous.
- Every tool an agent can call declares **how it writes**: it does not write, it asks first,
  or it writes directly as a named exception with a stated reason.
- Error responses are built by the serialiser, so an error text containing a quote or a
  newline still produces a valid message, and internal diagnostics — file paths, database
  messages — reach the log rather than the caller.

## Out of scope

- Changing the set of tools, or what any tool does.
- Changing the proposal pipeline itself.
- The external agent, the activity cards, and everything else in the feature this change is
  part of. This change stands alone.

## Why now

The external-agent feature puts a second process on the user's database and next to the
user's profile. Landing these three fixes first means the second process arrives on ground
that is already safe, instead of arriving and exposing that it was not.
