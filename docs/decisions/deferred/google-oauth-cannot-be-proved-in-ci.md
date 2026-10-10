---
title: "Google Oauth Cannot Be Proved In Ci"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Found in:** MR for Google Calendar 2-way sync, while verifying KMPAuth 3.0 before
building on it.

**Status: OPEN**

**Tracking:** the manual acceptance list lives in
`docs/decisions/2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md`.
Deliberately *not* a GitHub issue: the remaining work is one person with one Google
account running a checklist on real hardware, and an issue only ever closed by that same
person is a tracker entry rather than a commitment to anyone else. It graduates to one
the moment a second person has to coordinate it.

**What it is.** The OAuth half of Google Calendar sync has a real manual gate and no
automated one. Three independent reasons, all in the dependency:

1. KMPAuth `3.0.0-alpha03` states it is *"Verified by compilation, API checks and unit
   tests. **Not yet exercised on real devices**"* — including Google sign-in on Android
   and on Desktop.
2. The library exposes **no refresh token** (a scan of every class in `kmpauth-core` for
   a member mentioning "refresh" returns zero hits) and its OAuth flow requests **no
   `access_type=offline`**. So the durable credential has to be obtained by this project's
   own code, and that code's first real exercise is against a live Google account.
3. Its Desktop `signOut()` is `currentLogger.log("Not implemented")`, so disconnect has to
   be implemented locally too — which is exactly the kind of path that compiles and then
   does nothing.

**Ruled out.** A "release build only" workaround does not apply: the upstream R8/ProGuard
consumer rules are present in the alpha, so the known silent-stripping failure is already
addressed, and the remaining gap is real-device sign-in rather than minification.

**Checks already performed.** Confirmed the artifact resolves and that
`:shared:compileKotlinJvm` succeeds on the project's `JVM_11` target — so there is **no**
Java 17 requirement, contrary to what the 3.0 migration guide's wording suggests (bytecode
61 as *input* is fine on a Java 11 *target*; the setting controls what we emit). Read the
JVM OAuth implementation and confirmed the absent offline-access parameters. Enumerated
the JVM classes for any refresh-token API and found none.

**Try next:** run the manual acceptance list in the change's spec on a real device and on a
packaged desktop build, with the grant actually revoked at the end. Specifically: sign in,
disconnect, and confirm `SecureStoragePort` is empty afterwards; then revoke access in
Google account settings and confirm the app says "reconnect" rather than retrying forever.
Only then build the pull engine on top of it.

**Do not** treat "it compiles" or a green unit test as evidence for this item. The unit
tests cover the token-refresh *request*, not Google's response to a real grant.
