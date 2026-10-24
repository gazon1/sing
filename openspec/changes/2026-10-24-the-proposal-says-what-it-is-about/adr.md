# The proposal says what it is about, and reads strictly — ADR

The decisions this change is built on live in `docs/decisions/`:

- `docs/decisions/2026-10-24-the-change-says-what-the-proposal-is-addressed-to.md`
  — the kind of target is derived from the change, a creation says what it
  creates, and a change offered the wrong id is refused.
- `docs/decisions/2026-10-24-a-status-this-build-does-not-know-is-not-a-waiting-one.md`
  — the reader does not fill the gap: a status this build cannot name is neither
  waiting nor decided, and the row is written back unchanged.
- `docs/decisions/2026-10-24-the-status-of-a-proposal-is-what-its-changes-say.md`
  — the aggregate status is derived from the changes, and a surface that names a
  status fails the build.

## The one sentence

The previous round said it as **a change the user cannot see is a change nobody
decided about.** This round says the same thing from the other side:

**a change the user sees as something else is a change nobody proposed.**

A status this build cannot read was read as "waiting", and a change that had
already been applied was applied a second time. A proposal addressed by a string
that did not match its change was a proposal the confirmation screen does not
show — a change that was proposed and cannot be found.

## What the three decisions have in common

They are all about *one fact said twice*:

- The kind of target — the change says it, and a string beside it said it again.
- The status of a change — the row says it, and the reader's fallback said it
  again.
- The status of a proposal — the changes say it, and a summary column said it
  again.

In each case the second copy is the one that goes stale, and in each case the
second copy is the one that is asked first. So in each case the second copy is
gone: it is derived, and the places that used to write it by hand now have
nothing to write.

## The one that is not about the user's screen

The derivation of the kind of target changes nothing the user sees — every one
of the thirteen hand-written strings was the one the derivation produces. It is
here because the invisible ones are the ones that rot: a caller that writes the
wrong string produces a proposal that is simply absent, and nothing anywhere
says so.

## The cost, stated once

A change written by a newer build is refused by an older one — not applied, and
not read as something else. The user sees it on the card with the reason, and
can turn it down or wait for an update.

That is the price of a reader that is honest about what it does not know, and it
is paid only between two releases.
