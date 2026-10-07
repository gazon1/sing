# auth-outcome-is-reported-not-thrown

**Status:** proposed · **Source:** `/home/max/Downloads/Тест-план_ авторизация и синхронизация.md` §3.1 (SU-04, SU-06, SU-13), §3.2 (SI-10), §4 (SS-04) · **Spec:** `user-authentication` (modified by this change)

## What

The outcome of an authentication attempt SHALL be reported, and SHALL be reported
correctly, in three directions:

- A failure to write the session to the device SHALL be reported as a **failed attempt**,
  not as a thrown exception that escapes the caller.
- A sign-up that produced **no usable session** SHALL NOT be presented to the user as a
  completed sign-in.
- A sign-in address SHALL be **normalised** before it is validated, and both address and
  password SHALL be **bounded**.

Alongside these, the credential store SHALL NOT restore a stale plain-text copy over a
session it has already superseded.

## Why

These are the P0 rows of the test plan that the current code fails, and each is a way
for the interface to say something untrue.

The credential write is the sharpest. Writing the session is the last step of a
successful sign-in, it is the one step that can fail for reasons that have nothing to do
with the user's password, and a failure there currently escapes as an exception rather
than arriving as a result. The consequence is not cosmetic: the account *is* created and
*does* hold a live session on the server, the device never learns about it, and the user
sees no error at all. A sign-in that silently discards the credentials it was just
issued is the one outcome the authentication requirements were written to prevent.

The false success is the second. A sign-up whose address is awaiting confirmation is
deliberately reported as a success with a signed-out session — that pairing is
documented, and it is correct at the layer that produces it. The layer above it has no
way to express it: every success navigates the user into the application as though they
were signed in. So a correct decision is discarded one level up, and the user lands
inside the app with no session and no explanation.

The normalisation gap is the third, and the smallest. `" user@mail.com "` is rejected
as malformed rather than accepted, and a 300-character address is accepted as long as it
matches the pattern. Both are wrong in the direction that costs the user least, which is
why they have survived, and both are one line.

The stale restore is the fourth. The plain-text migration is guarded by a flag that
records *this instance* having run it, so a process that is killed between writing the
session securely and erasing the plain-text copy re-runs the migration on the next
launch — and copies the stale token over the fresh one. Combined with refresh-token
rotation this can lock a user out of their own account.

## Scope

**In scope:** the reported outcome of an attempt that fails to persist its session; the
absence of a partial credential set; the representation of a session-less sign-up
success; address normalisation and length bounds; the plain-text migration's
already-superseded case.

**Out of scope, deliberately:**

- **HTTP status classification.** The test plan asks for per-status messages and
  `Retry-After` handling; the transport collapses every provider failure into one
  unclassified error. This is a real gap but a separate change, because the wording for
  each status is a product decision and an enumeration is not a spec.
- **A double-submit guard.** The plan's SU-09 expects a second concurrent attempt to be
  refused or coalesced. The behaviour is a product decision (refuse, or queue behind the
  first) and is not taken here.
- **Clearing the password field after submit** (SU-15). The field currently lives in the
  screen rather than in view-model state, so honouring the requirement means moving
  where the secret is held — a design change with its own justification, not a patch.
- **Anonymous sessions.** Two findings in this area are contradictory rather than
  merely unimplemented, and reconciling them is a product decision. Tracked as an issue.
- **Credentials in platform backup** (CF-06). Real, and independent of authentication
  behaviour; it belongs to the platform's storage policy.

## What this change does not do to the test plan

The test plan is written against an earlier baseline. Its headline defect claims were
checked against the current code, and six of the eight are already fixed with regression
tests in place. This change covers the rows that are genuinely still open; the rest are
recorded, with evidence, in the accompanying issues.
