---
title: "The session lives in the client: the cache must remember what it built"
date: 2026-10-24
status: accepted
slug: the-session-lives-in-the-client
supersedes: null
---

# The session lives in the client: the cache must remember what it built

## Context

Pushing a backup to the cloud failed on the device with

```
new row violates row-level security policy
```

while the app was signed in with a real account, and the same upload through the Storage REST API
with the same user's access token returned `200`. The rules were right, the bucket was right, the
credentials were right. What was wrong was the identity on the wire: the request carried

```
Authorization: Bearer <the anon key, 169 characters>
```

instead of the user's access token, so the server saw a stranger asking to write into a bucket that
only signed-in users may write.

The stack trace pointed at the seam. Every failing request went through the SDK's
`authenticatedSupabaseApi`, which asks the `Auth` plugin for the session token and falls back to the
client's key when the plugin has no session. The plugin had no session — because it was a
different plugin instance than the one the sign-in had filled.

## Decision

**The client and the configuration it was built for are cached together.**

```kotlin
open suspend fun client(): SupabaseClient? = lock.withLock {
    val config = resolver.resolve(store) ?: return@withLock null
    cachedClient?.takeIf { cachedConfig == config }
        ?: build(config).also {
            cachedConfig = config
            cachedClient = it
        }
}
```

The cache was keyed on the configuration but stored only the client. The key stayed empty, the
comparison never matched, and **every call built a new client** — which is both a leak and the
actual bug: the session lives inside the client's `Auth` plugin, so a sign-in filled the plugin of
one instance while the next request asked another.

## Why this is a rule and not a patch

The lesson is not "remember the config". It is that **the session is a property of the client
instance**, and any code that hands out a client has to hand out *the* client. A cache that cannot
answer "is this still the right client?" answers "no" every time, and the failure it produces is
not slow — it is a request that arrives as the wrong person.

Two consequences follow:

- Anything that signs in and anything that talks to the server must go through the same provider
  instance. `SupabaseClientProvider` is a `single` for that reason.
- The identity on the wire is now worth asserting directly: `SupabaseClientProviderTest` checks
  that two calls hand out the same client and the same auth plugin, which is the property that
  makes the token reach the request.

## Consequences

- Every request the app makes as a signed-in user now carries that user's token, including the
  backup upload and the PostgREST sync calls that were failing the same way.
- A client is built once per configuration instead of once per call, so the connection pool and
  the coroutine scope stop leaking.
- A configuration change still replaces the client — the new project needs a new client — and the
  outgoing one is not closed mid-flight, for the reason `configure` already documents.
