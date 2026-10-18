# Offline Local Session — Observable Behavior

**capability:** `user-authentication` | **status:** proposed

---

## MODIFIED Requirements

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

## ADDED Requirements

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
