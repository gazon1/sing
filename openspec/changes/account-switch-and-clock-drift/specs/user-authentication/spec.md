# What an account-less session, a user switch and a wrong clock each do

**capability:** `user-authentication` | **status:** proposed

> **Three superseded requirements.** This change answers the three questions the
> auth/sync test plan left open, and each answer contradicts something already written:
>
> - **REQ-UA-004** required a real provider-side identity for a session with no account.
>   Withdrawn — an account-less session does not contact the provider at all. Stated
>   here as REQ-UA-015, which is its replacement.
> - **REQ-UA-006** required queued changes to remain on the device and be delivered
>   after the next sign-in. That still holds for *signing out*, restated as REQ-UA-016;
>   for *switching users* it is replaced by REQ-UA-017, which delivers at the switch and
>   then erases. REQ-UA-018 is new and covers the two of them touching: one account's
>   queued work is never settled by another's push.
> - **REQ-OS-005** governs when sync runs and does not carve out an account-less
>   session. It is unchanged, and REQ-UA-015 constrains it rather than contradicting it.
>
> `user-authentication` and `offline-sync` do not exist in `openspec/specs/` — both are
> deltas inside the unarchived `supabase-auth-and-sync` change — so a `MODIFIED`
> requirement would be refused at archive time and the replacements carry new ids. The
> reconciliation above has to be applied by hand when that change is archived. That
> change is marked complete, and the audit found its server half unverified — the
> integration script asserts nothing and the schema is not in version control (#184).
> That is a finding rather than a decision, so it has an issue and not a decision record.

**ADRs:** `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`,
`2026-10-05-switching-users-delivers-then-erases-the-departing-user`,
`2026-10-05-hybrid-clock-is-merged-and-drift-stops-the-write`

---

## ADDED Requirements

### Requirement: REQ-UA-015

A session with no account SHALL NOT contact the identity provider, SHALL store its data
on the device only, and SHALL be restored across restarts. Signing in while such a
session holds data SHALL ask the user whether to keep that data in the account, and
SHALL honour either answer.

The requirement it replaces asked for a real provider-side identity. That described a
mechanism rather than a need, and the need is that the work is not lost.

#### Scenario: Working with no account needs no server
- The user creates tasks, notes and projects with no account
- No request reaches the identity provider, and no failure is reported
- The work is on the device and is available after the app is restarted

#### Scenario: The account-less session survives a restart
- The app is closed and reopened with no account signed in
- The same data is present and belongs to the same owner
- The user is not offered a new, empty session alongside it

#### Scenario: The choice is offered, and both answers work
- A session with no account holds data, and the user signs in
- The user is told how much data there is and asked whether to keep it in the account
- Keeping it attributes the work to the account; discarding it deletes it

#### Scenario: Declining the choice is not the same as a failed sign-in
- The user signs in successfully and then chooses to discard the account-less data
- The sign-in stands, and only the account-less data is gone

#### Scenario: Nothing to choose is not a question
- A session with no account holds no data
- Signing in does not ask the user anything

---

### Requirement: REQ-UA-016

Signing out while keeping the device signed out SHALL leave the local data in place, and
SHALL NOT require the network. Signing back in to the same account SHALL find it.

This is stated separately from the switch because the two are different operations with
different costs, and the difference is easy to lose.

#### Scenario: Signing out with nothing pending
- The user signs out
- The credentials are removed and the local data stays
- Signing in again finds the work

#### Scenario: Signing out with no network
- The device is offline
- Signing out still completes, and the local data stays

#### Scenario: Signing out does not wait for the server
- The provider is unreachable
- The session is still ended, and nothing is reported as lost

---

### Requirement: REQ-UA-017

Signing in as a different account while one is already signed in SHALL deliver what the
departing account had queued, and SHALL NOT erase the departing account's local data
until the server has it.

The user SHALL be told what the device is doing before the switch begins, and a switch
that cannot complete SHALL leave both accounts exactly as they were.

#### Scenario: The switch delivers before it erases
- The departing account has queued changes and the network is up
- Those changes reach the server under the departing account
- Only then is the departing account's local data removed

#### Scenario: A switch that cannot deliver is refused
- The departing account has queued changes and the network is down
- Nothing is erased and the new sign-in does not proceed
- The user is told the switch needs the network, and their work is still there

#### Scenario: A refused switch leaves no half-finished state
- A switch was refused for want of network
- The device still holds the departing account's complete data, and no part of the
  incoming account's

#### Scenario: The whole of the departing account goes, including every profile
- The departing account has more than one profile
- All of its local data is removed, not only the profile that was active

#### Scenario: The switch says what it is waiting for
- A switch is in progress
- The user is shown that the queued work is being delivered, and cannot be mistaken for a
  hang

#### Scenario: A switch with nothing queued still erases
- The departing account has no queued changes
- Its local data is still removed, and the switch completes without waiting

---

### Requirement: REQ-UA-018

A change queued by one account SHALL NOT be applied, or marked delivered, by or on
behalf of another account, and a response that arrives after the account it was
requested under is gone SHALL NOT change local state.

#### Scenario: A response arriving after a sign-out changes nothing
- A push is in flight and the user signs out
- The response is discarded; the queued rows stay queued
- Nothing is recorded as delivered

#### Scenario: A response arriving after a switch changes nothing
- A push is in flight and the user signs in as somebody else
- The response is discarded rather than settled against the new account

#### Scenario: Queued work belongs to the account that created it
- Two accounts have used the device
- Each account's queued changes are delivered only under that account

---
