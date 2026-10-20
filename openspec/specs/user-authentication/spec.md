# user-authentication Specification

## Purpose
Decide who the app is acting as, and keep that answer honest: an account-less session
is local and survives restarts, signing in offers a choice rather than silently
overwriting local data, signing out clears credentials without requiring the network,
and switching accounts delivers the departing account's queued work before erasing it.

## Requirements

### Requirement: REQ-UA-001

The system SHALL authenticate with email and password on both supported platforms, and
SHALL validate the input locally before any network call.

#### Scenario: Successful sign-up
- User enters a valid email address and a password meeting the minimum length
- An account is created and the session becomes signed in without a confirmation step

#### Scenario: Invalid input is rejected locally
- User enters a malformed email address
- No network call is made and the user sees a validation message

#### Scenario: Wrong password
- User enters a registered email with an incorrect password
- The session is not signed in, and the failure reason is reported without revealing
  whether the address exists

---

### Requirement: REQ-UA-002

Access and refresh tokens SHALL be stored in the platform's secure credential store and
SHALL NOT be written to plain-text preferences.

#### Scenario: Tokens are hardware-backed on Android
- The user signs in on Android
- Tokens are written to the keystore-backed store
- A plaintext preferences store contains no token values

#### Scenario: Migration of an existing plaintext token
- The app previously stored a token in plain-text preferences
- On the next successful sign-in the token is written to the secure store
- The plaintext copy is removed

---

### Requirement: REQ-UA-003

The system SHALL refresh an expired access token automatically, and SHALL move to a
signed-out state when the session can no longer be recovered.

#### Scenario: Expired token is refreshed
- The stored access token has expired but the refresh token is valid
- The next authenticated request succeeds without user interaction

#### Scenario: Unrecoverable session
- Both tokens are rejected
- The session becomes signed out and no further sync is attempted
- Locally queued changes remain on the device and are delivered after the next sign-in

---

### Requirement: REQ-UA-004

The system **SHALL** provide an offline session that requires neither an account nor
network access, and **SHALL** persist that session across application launches.

**Rationale:** REQ-UA-004 previously required a real provider-side anonymous identity
so the data could be "claimed later". ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`
supersedes that: a provider-side anonymous identity cannot be obtained without a
network and a configured project, which is exactly the situation the offline path
exists for. Persistence is a consequence of the same decision — a session that is
re-established by asking again on every launch has not left the auth flow.

Supersedes the provider-side identity required by the previous REQ-UA-004. The
claim path it existed to protect is REQ-UA-005, which stands.

#### Scenario: Anonymous session
- User chooses to continue without an account
- The session reports an anonymous identity that is local to the device, not one
  the backend recognises
- Data created in this session is scoped to that identity

#### Scenario: Offline entry with no network and no server
- No Supabase project is configured and the device has no connectivity
- The user activates "Continue Offline"
- The application opens the task list
- No request is made to any identity provider

#### Scenario: The offline session survives a restart
- The user activated "Continue Offline" and the application opened
- The application is closed and relaunched
- The task list opens directly, without an authentication screen

#### Scenario: The offline session is reachable without a server
- No Supabase project is configured
- The authentication flow shows the server-configuration screen
- A way to continue without an account is offered there, and it works

---

### Requirement: REQ-UA-005

When an anonymous user signs up or signs in, ownership of their existing data SHALL
transfer to the new account atomically.

#### Scenario: Anonymous data is claimed
- An anonymous user has created tasks
- The user signs up with an email and password
- All previously created tasks belong to the new account
- The transfer either completes fully or not at all

#### Scenario: Another user's data is not claimable
- The transfer request names an identity that is not the caller's own
- The transfer is rejected and no data moves

---

### Requirement: REQ-UA-006

Signing out SHALL clear the stored credentials, and SHALL NOT discard locally queued
changes that have not yet been delivered.

#### Scenario: Sign out with pending changes
- The device has queued changes that were never delivered
- User signs out
- The credentials are removed
- The queued changes remain on the device and are delivered after the next sign-in

---

### Requirement: REQ-UA-007

The loading indicator SHALL be cleared whether the authentication attempt succeeded or
failed.

#### Scenario: Failure clears the indicator
- A sign-in attempt fails
- The interface stops showing a pending state

#### Scenario: Success clears the indicator
- A sign-in attempt succeeds
- The interface stops showing a pending state

---

### Requirement: REQ-UA-008

The application SHALL treat the identity provider as replaceable: the sync engine and
all domain layers SHALL operate without knowing which provider issues sessions.

#### Scenario: Architecture guard
- A static analysis rule asserts that no production file outside the transport and
  authentication seams imports the vendor SDK

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

---

### Requirement: REQ-UA-015

Restoring a session on launch SHALL complete within a bounded time and SHALL NOT leave the
application in a loading state, whatever state the session store is in. A store that does
not answer, and a store that cannot be read, SHALL both end at a signed-out session the user
can recover from with a single action.

#### Scenario: A store that never answers does not hold the launch open
- The stored session cannot be read because the store does not respond
- The application stops waiting and shows the login screen

#### Scenario: A store that cannot be read does not hold the launch open
- The stored session cannot be read because the store raises a failure — for instance an
  encrypted store whose decryption key no longer exists on this device
- The application stops waiting and shows the login screen

#### Scenario: A failure to restore is not reported as a session
- The restore could not be completed
- The session state visible to the rest of the application is signed out, and no part of
  the application treats the user as signed in

#### Scenario: The user can get back in after a failed restore
- The login screen is shown following a restore that could not be completed
- Signing in, or choosing to work offline, succeeds on a store that has recovered

#### Scenario: An encrypted store is not restored without its key
- A reinstall restores application data
- The session store is excluded from that restore, because the key that decrypts it cannot
  be restored and a store whose ciphertext and key disagree is unreadable by construction

### Requirement: REQ-UA-020

When a user without an account holds local data and signs in to an account, the
system **SHALL** require the user to choose whether that data moves to the account or
is deleted, and **SHALL** apply only the chosen outcome.

**Rationale:** REQ-UA-005 already requires that both outcomes be supported and that
neither be a default. Nothing made the user say which, so the question was being
answered by whichever code path ran first. The two outcomes are not
interchangeable: one hands the user their work on a second device, the other discards
it irreversibly.

#### Scenario: The user keeps their offline data
- The local session holds tasks and notes
- The user reaches the attach screen, is shown what they hold, and chooses to move it
- The account is signed in
- Every previously local row belongs to the account, and synchronises from the next cycle

#### Scenario: The user discards their offline data
- The local session holds tasks and notes
- The user reaches the attach screen, is shown what they hold, and chooses to delete it
- The account is signed in
- The account starts with no data from this device

#### Scenario: There is no default choice
- The attach screen shows the offline data the user holds
- The submit action is unavailable until one of the two options is chosen
- No outcome is applied without an explicit choice

---

### Requirement: REQ-UA-021

Signing out of an account **SHALL** end the offline-session marker but **SHALL NOT**
discard the identity under which locally created data is filed.

**Rationale:** the two values answer different questions — "is this install running
without an account" and "whose rows are these". Erasing the identity would orphan every
task created offline with no error and no way back, because nothing would regenerate
the same id. Keeping the marker would mean signing out drops the user straight back
into offline mode, which removes the place where a different account can be chosen.

#### Scenario: Signing out and continuing offline again
- The user works offline, signs in to an account, then signs out
- The user activates "Continue Offline"
- The same local data is present under the same owner

---

### Requirement: REQ-UA-022

If the offline data cannot be handed to the account, the system **SHALL** report the
failure and return the device to the offline session it started from.

**Rationale:** handing ownership is not atomic — there is no transaction seam in
`commonMain` yet. A half-finished transfer leaves the user signed in to an account
whose data is silently missing rows, which is a state they can neither detect nor
recover from. Staying local keeps everything they had and closes the account they
could not get into.

#### Scenario: A transfer fails part-way
- The user chose to move their offline data to an account
- The transfer fails
- An error is reported
- The device is on the offline session again, with its data intact
