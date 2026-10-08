# user-authentication — Observable Behavior

**capability:** `user-authentication` | **status:** proposed

> These are **new** requirements, not modifications. `user-authentication` does not
> exist in `openspec/specs/` yet — it is still a delta inside the unarchived
> `supabase-auth-and-sync` change — so a `MODIFIED` operation would be refused at
> archive time. They extend REQ-UA-001 (local validation), REQ-UA-002 (secure storage)
> and REQ-UA-007 (the pending indicator) as those are written today; when that change
> is archived these can be folded into them.
>
> The credential store is not split into a second capability. The unarchived change
> already gave token storage a home in REQ-UA-002, and opening `core/auth-token-storage`
> alongside it would give one behaviour two homes.

---

## ADDED Requirements

### Requirement: REQ-UA-009

A sign-in address SHALL be normalised before it is validated: surrounding whitespace
removed and letter case folded, so that two spellings of one mailbox are treated as one
mailbox.

#### Scenario: An address that differs only in padding and case is accepted
- The user enters an address with surrounding whitespace, or with uppercase letters
- The attempt proceeds against the same mailbox, as if the address had been entered plainly

#### Scenario: Case folding does not invent a mailbox
- The user enters an address whose local part is case-sensitive at the provider
- The normalisation does not change which mailbox is addressed, or the attempt is refused
  with a message that says so

---

### Requirement: REQ-UA-010

The length of an address and the length of a password SHALL both be bounded, and an
input outside those bounds SHALL be refused locally without a network call.

#### Scenario: An over-long address is refused locally
- The user enters an address longer than the accepted bound
- No network call is made, and the user is told the address was refused

#### Scenario: An over-long password is refused locally
- The user enters a password longer than the accepted bound
- No network call is made, and the user is told the password was refused

#### Scenario: A password within the bound is accepted
- The user enters a password longer than the minimum and within the bound
- The attempt reaches the provider

---

### Requirement: REQ-UA-011

A failure to write the issued session to the device SHALL be reported as a failed
authentication attempt, and SHALL NOT escape to the user as an uncaught error.

#### Scenario: A store failure is a reported failure
- The provider accepts the credentials and returns a session
- The device then fails to write that session to secure storage
- The attempt reports failure with a message naming the local failure, and no uncaught
  error reaches the user

#### Scenario: The pending indicator is cleared on a store failure
- The attempt failed for the local reason above
- The interface stops showing a pending state, as it does for any other failure

#### Scenario: A store failure is not reported as bad credentials
- The attempt failed while writing the session locally
- The message does not suggest the address or the password was wrong

#### Scenario: The server side of a failed store is not left holding a live session
- The provider issued a session and the device failed to store it
- The attempt does not leave a session alive at the provider that the device has
  forgotten, and if it cannot be ended the user is told the account may still exist

---

### Requirement: REQ-UA-012

A credential store SHALL NOT hold an incomplete session. If writing a session fails part
way, the store SHALL NOT be left holding some of its fields, so that a later launch
cannot find a half-written identity and treat it as signed in.

#### Scenario: A partial write leaves nothing behind
- Writing the session fails part way through
- The credential store afterwards holds no incomplete session

#### Scenario: A launch after a partial write is signed out
- A previous attempt left a partial write behind
- The next launch reports a signed-out state rather than a half-restored session

---

### Requirement: REQ-UA-013

A plain-text token found in preferences SHALL be moved into the secure store and erased,
and SHALL NOT be able to displace a session the secure store already holds.

#### Scenario: Migration of an existing plaintext token
- The app previously stored a token in plain-text preferences
- On the next successful sign-in the token is written to the secure store
- The plaintext copy is removed

#### Scenario: An interrupted migration cannot undo a newer session
- A process ends after a session is written securely but before the plain-text copy is
  erased
- On the next launch the leftover plain-text token does not replace the newer session
  already held, and it is erased

#### Scenario: An interrupted migration does not force a new sign-in
- The situation above leaves a valid session in the secure store
- The next launch reports a signed-in session, so the user is not asked to sign in again

---

### Requirement: REQ-UA-014

An authentication attempt that produced no usable session SHALL NOT be presented to the
user as a completed sign-in. The interface SHALL say what the outcome requires of the
user, and SHALL NOT enter the application as though a session existed.

#### Scenario: A sign-up awaiting email confirmation is not a completed sign-in
- The account is created but its address must be confirmed before it can be used
- The user is told to check their mail
- The user is not taken into the application as a signed-in user

#### Scenario: The message and the session state agree
- An attempt ends with no session
- The reported outcome names the reason, and the session state visible to the rest of the
  application agrees with it

#### Scenario: A failed attempt is distinguishable from a session-less one
- One attempt fails outright and another succeeds with no session
- The two are reported differently, so the user is not sent to check their mail for an
  attempt that never created an account
