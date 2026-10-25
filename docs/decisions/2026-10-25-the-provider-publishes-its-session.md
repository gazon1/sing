---
title: "The provider publishes its session, and the application republishes the tokens"
date: 2026-10-25
status: accepted
slug: the-provider-publishes-its-session
---

# The provider publishes its session, and the application republishes the tokens

## Context

The session lives in the provider's plugin (ADR `2026-10-25-auth-adapter-replaced-by-kmpauth`).
The application holds a copy of it: the repository publishes a `Session.SignedIn` with the
tokens, and the plugin keeps the session it actually makes its requests with.

**Two copies, and the provider's is the one that counts.** The plugin can replace its
session — a refresh it ran on its own, a session it imported from the store, a token the
provider rotated behind the application's back — and the application's copy does not notice.
It keeps publishing tokens the provider has already retired. Every request made afterwards
is made as a stranger: the exact failure the "session lives in the client" ADR described
(`2026-10-24-the-session-lives-in-the-client`), where a backup upload was refused by a
row-level security policy because the request carried the anon key instead of the user's
token.

The change that moved the session into the plugin declared the transition
`SignedIn --providerSessionChanged(session)--> SignedIn` in its model and said, in so many
words, that the change introduces it. It did not. The model's "not yet implemented" was
still true when the code landed, and the change's own second reading recorded that as a
divergence: **D-02** in
`openspec/changes/replace-auth-with-kmpauth/results.md` — "the transition that `model.md`
says this change introduces is not in the code".

## Idea

The provider already publishes. The plugin's status is a flow of what it holds, and it is
the one thing that knows. The application needs a way to hear it, and it needs to hear it
in its own vocabulary — through the port, not through the plugin.

## Decision

**The port gains one method.**

```kotlin
fun sessionChanges(): Flow<RemoteSession?>
```

A flow of the session the provider holds, or null when it holds none. A null is an answer
and not a failure: "the provider holds no session" is what a signed-out device says.

**The seam reads the plugin's status.** `SupabaseAuthGateway.sessionChanges()` maps the
plugin's `sessionStatus` onto the port's type: an authenticated status becomes a
`RemoteSession`, every other status becomes null. The plugin is resolved when the flow is
collected, and the observation stays with the plugin it started on — a session belongs to
the client that holds it, and a session observed across a project change would be a
session of the project the user left.

**The repository republishes, under the guard its own model names.** The transition exists
only while the application is signed in, and only for a session with the same owner. Three
answers are therefore not transitions, and the repository leaves all three alone:

- a provider that holds **no session** — the machine has no transition for a session that
  ended on its own, and the application's own sign-out is the only thing that may publish
  `SignedOut`;
- a session with a **different owner** — that is a different account, and the machine has
  no transition for it either;
- a provider that is **not signed in yet** — the restore owns that answer.

**The observation never signs the user out.** It can only replace the tokens of a session
the application has already published. It is a second reader of the provider's state, not
a second source of truth.

**A failure to observe is not fatal.** The observation is an addition to what the
application already knows, not the way it learns who the user is. A provider that cannot
be observed leaves the restore and the attempts in charge, exactly as before.

## Rationale

**Why a flow and not a callback.** The port is already a set of suspending calls, and the
application's own state is a flow. A callback would have to invent a lifetime for itself —
who cancels it, on which scope — and the answer would be the scope the repository already
has.

**Why the guard is the model's and not a new one.** The model is the document that says
what the repository's states mean. An observation that invented its own transition would
be a second machine, and the two would disagree the first time the provider did something
unusual. The model already says what a replaced session does: the identity is unchanged and
the tokens are republished. That is exactly what the guard implements, and it is why no
transition had to be added to it.

**Why a provider that holds no session does not sign the user out.** Because the machine
does not say so. `SignedOut` is published by the restore, by the attempts, and by the
sign-out — the three places where the application knows the session is over. A provider
reporting "no session" is a fact about the provider's own state, and the repository's
machine has no transition from `SignedIn` for it. Inventing one would mean that any
transient state inside the SDK — a plugin that is still initialising, a refresh that
failed — could sign the user out of the application.

**Why the observation cannot loop.** The seam's own `currentSession()` hands the session
to the plugin, which republishes it; the repository then republishes the same session to
the application. The second publication writes the same tokens the first one did, and
nothing writes back to the provider from there. The guard is what makes the loop
harmless: the second round finds the same owner and publishes the same value.

**Why the failure is not fatal.** The alternative — a repository that cannot publish a
session because the provider's stream broke — is worse than the one it fixes. The
application still restores, still signs in, and still signs out; all it loses is the
ability to hear about a replacement the provider made on its own.

## Consequences

- **The port grows by one method.** `AuthGateway` gains `sessionChanges()`. The seam, the
  test doubles and the stubs all grow with it, and the port's KDoc says what a null
  means.
- **The application has two readers of the provider's session**: the restore, which asks
  for it once, and the observation, which watches for it changing. The second is guarded
  by the first's answer: nothing is republished until the application has published a
  session of its own.
- **The transition the model promised now exists.** The model's line — "not yet
  implemented… this change introduces it" — is true, and the divergence D-02 is closed by
  the code rather than by an amendment to the document.
- **A project change replaces the client, and the observation stays with the plugin it
  started on.** A session observed across a project change would be a session of the
  project the user left, so the observation does not follow it. The restore does not
  survive a project change either — the repository is a process-lifetime singleton, and
  changing the project asks for a restart.
- **The seams are still seams.** The observation is the plugin's status mapped into the
  project's own type; no vendor type crosses the port, and the vendor-confinement gate
  still holds.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthGateway.kt` — the port.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt` — the seam.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt` — the guard.
- `docs/decisions/2026-10-25-auth-adapter-replaced-by-kmpauth.md` — the change that moved
  the session into the plugin.
- `docs/decisions/2026-10-24-the-session-lives-in-the-client.md` — why the session belongs
  to the client instance.
- `openspec/changes/replace-auth-with-kmpauth/model.md` — the transition, and the line
  that promised it.
- `openspec/changes/replace-auth-with-kmpauth/results.md` — D-02, the divergence this
  decision closes.
- `openspec/changes/observe-the-provider-session/` — the change that carries it out.
