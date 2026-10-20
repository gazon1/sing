# offline-local-session

## What

Make "Continue Offline" actually work offline: press it, and you are in the app —
no network, no configured Supabase project, and no auth screen on the next launch.

When a user without an account later signs in, the app asks what should happen to
the data they created offline, and does exactly that.

## Why

The button is a network operation wearing an offline label. `AuthIntent.SignInAnonymously`
went to `SupabaseAuthGateway.signInAnonymously()` — an HTTP request to Supabase that
needs a configured project, a working network, and anonymous sign-ins enabled in the
project's settings. Press it on a plane and it fails; the user stays on the auth
screen, which is the one outcome the button exists to avoid.

Three defects, one symptom:

1. **Not offline.** The provider call above.
2. **Unreachable without a server.** With no project configured the screen shows
   "Connect your server" and returns early, so the offline button is not rendered at
   all — the users who have nothing to configure are the ones who cannot get in.
3. **Not remembered.** An anonymous session was written nowhere, and the restore path
   required a refresh token. The auth screen therefore reappeared on every cold start,
   even after a successful press.

The decision that all three violate is not ours to make: ADR
`2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice` (accepted) already
ruled that an anonymous session is local, that the provider is not contacted, and that
the session persists. The code never caught up. This change closes that gap.

## Scope

### In scope

- A local session that reaches no provider, and is persisted so the auth flow stays
  closed across launches.
- The offline button on the screen shown when no server is configured.
- The choice at attach time — move the local rows to the account, or delete them —
  with neither outcome defaulted.

### Out of scope

- Syncing local data. A local session still does not sync (ADR), so a claimed
  account's data behaves like any other account's from the next cycle onward.
- Provider-side anonymous identities. Removed rather than deprecated; see design.md.
- Backup and export of local data.
- A separate "switch back to offline" affordance after signing in.

## What changes for the user

Nothing to do. The same button, on one more screen, doing what it says.
