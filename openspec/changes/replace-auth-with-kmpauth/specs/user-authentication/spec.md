# Auth Adapter Replacement — Observable Behavior


**capability:** `user-authentication` | **status:** proposed

---

## MODIFIED Requirements

### Requirement: REQ-UA-008

The application SHALL treat the identity provider as replaceable: the sync engine and
all domain layers SHALL operate without knowing which provider issues sessions.

**Rationale:** the requirement has always been about *a* provider, and the guard it
names was written when the application depended on one vendor SDK. A second vendor
arrives with this change, and a guard that names one of them is a guard that measures
nothing for the other — the failure this repository has already paid for twice: a gate
that is green and is checking nothing. Naming every vendor the application depends on
keeps the rule honest as the dependency set grows.

#### Scenario: Architecture guard
- A static analysis rule asserts that no production file outside the transport and
  authentication seams imports any vendor SDK the application depends on

---

## ADDED Requirements

### Requirement: REQ-UA-023

A session already held in the credential store SHALL survive an application update that
changes how the credential store represents a session. The update SHALL move the stored
session into the new representation rather than discard it, and SHALL NOT leave the
session in neither representation.

**Rationale:** how the credential store represents a session is an implementation
detail, but whether a signed-in user is still signed in after an update is not. An
update that changes the representation and forgets to move the session out of the old
one signs every signed-in user out at once — a failure the user experiences as the app
having forgotten them, with no error, no explanation and no way back except signing in
again and finding that whatever the provider still holds for the old session is
orphaned.

#### Scenario: A stored session survives an update
- The user was signed in before the application was updated
- The update changed how the credential store represents a session
- The user launches the application after the update
- The session is signed in, and the user is not asked to sign in again

#### Scenario: An interrupted move leaves the session in one place
- The process ends after the session is written in the new representation and before it
  is erased from the old one
- The next launch finds the session in the new representation
- The leftover in the old representation is erased, and does not replace it
