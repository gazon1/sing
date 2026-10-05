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

Signing in without an account SHALL create a real provider-side identity, not a locally
generated placeholder.

#### Scenario: Anonymous session
- User chooses to continue without an account
- The session reports an anonymous identity that the backend recognises
- Data created in this session is scoped to that identity

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
