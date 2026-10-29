# Observe the Provider's Session — Observable Behavior


**capability:** `user-authentication` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-UA-024

When the identity provider replaces the session it holds, the application SHALL publish
the tokens of the replacement for the account it has already published, and SHALL keep
the account it belongs to. The application SHALL NOT publish a session that belongs to a
different account, and a provider that holds no session SHALL NOT end the application's
session.

**Rationale:** the provider holds the session the application's requests are made with.
Two copies of a session drift: the provider rotates a token, and the application keeps
publishing the one it was given, so every request afterwards is made as somebody whose
session has ended. The application has to hear about the replacement, or the drift is
silent until a request is refused.

The guard is not politeness. A session that belongs to a different account is a different
identity, and publishing it would file the user's own rows under an owner that is not
their own. A provider that holds no session is not the application being signed out: the
application's own sign-out is what ends a session, and a provider's transient state — a
plugin still initialising, a refresh that failed — must not be able to end one.

#### Scenario: The provider replaces the session
- The application is signed in
- The provider replaces the session it holds with a new token pair for the same account
- The application publishes the new tokens
- The account the session belongs to does not change

#### Scenario: A session for another account is not published
- The application is signed in
- The provider reports a session that belongs to a different account
- The application keeps publishing the session it had
- The account the session belongs to does not change

#### Scenario: A provider without a session does not end the application's session
- The application is signed in
- The provider reports that it holds no session
- The application stays signed in

#### Scenario: The application is not signed in yet
- The application has not published a session
- The provider reports a session
- The application publishes nothing on the provider's account
