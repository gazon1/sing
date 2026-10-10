---
title: Google OAuth: KMPAuth for consent, our own token exchange for background sync
date: 2026-10-05
status: accepted
slug: google-oauth-hybrid-kmpauth-plus-own-token-exchange
---

# Google OAuth: KMPAuth for consent, our own token exchange for background sync

## Context

The project needs Google Calendar OAuth, and `cal.txt` recommends KMPAuth rather than
hand-rolled `expect`/`actual`. KMPAuth 3.0 (`kmpauth-google:3.0.0-alpha03`) was chosen.

Before building on it, the artifact was inspected rather than trusted, which changed the
design. Four findings, two of them decisive.

**1. No refresh token, anywhere.** `GoogleUser` exposes `idToken`, `accessToken`,
`email`, `displayName`, `profilePicUrl`, `serverAuthCode` — no refresh token. A scan of
every class in `kmpauth-core` for a member mentioning "refresh" returns zero hits.

**2. No offline access requested.** The JVM OAuth flow
(`GoogleAuthUiProviderImpl.kt`) builds its authorization request from
`GoogleAuthCredentials` and requests neither `access_type=offline` nor `prompt=consent`.
Google's authorization-code flow only returns a refresh token when `access_type=offline`
is explicit. So even the raw credential is not there to be persisted.

**3. `signOut()` is a no-op on desktop.**
`GoogleAuthProviderImpl.kt` is `currentLogger.log("Not implemented")`. Disconnect would
report success while leaving credentials stored.

**4. It is untested on real devices.** Upstream: *"Verified by compilation, API checks and
unit tests. Not yet exercised on real devices."*

**One thing the concern was wrong about:** the project does **not** need a Java 17 bump.
KMPAuth's JVM artifact is bytecode 61, but its Gradle metadata declares no
`org.gradle.jvm.version` floor, and `:shared:compileKotlinJvm` succeeds on the project's
`JvmTarget.JVM_11`. Bytecode 61 as *input* is fine on a Java 11 *target*; the setting
controls what we emit, not what we may read.

## Decision

**Hybrid.** KMPAuth performs interactive sign-in. This application performs the token
exchange that yields a durable refresh credential, and stores it in `SecureStoragePort`.

**Sign-out is implemented here, not delegated** — clear `SecureStoragePort`, cancel the
loopback listener, drop cursors.

## Rationale

**A background sync has to authenticate with nobody present.** An access token lives about
an hour. With no refresh token and no offline access, KMPAuth alone would expire roughly an
hour after consent, and a calendar sync that asks for re-authorisation daily is not a
sync. Findings 1 and 2 together make this unfixable from the outside: there is no
configuration that makes KMPAuth supply a refresh token.

**Split by risk, not by effort.** What KMPAuth does is the part that is genuinely painful
and genuinely platform-specific — the system credential UI on Android, a loopback HTTP
server on Desktop. That is also where a home-grown implementation would go wrong in ways
that are hard to test. What it does not do is a token refresh, which is a small, ordinary
HTTP call we can test against canned bodies.

**This also repairs a deviation rather than accepting one.** The plan had recorded that
KMPAuth bypasses `SecureStoragePort` for token material, as an accepted downside. Under
the hybrid design the long-lived secret lands in our own hardware-backed store
(EncryptedSharedPreferences on Android, libsecret on Desktop), profile-namespaced. The
deviation disappears.

**Sign-out cannot be delegated** given finding 3. The plan's own privacy requirement is
that disconnect and profile-switch clear credentials; a library that logs and returns
would make the app report a success it did not achieve.

**Desktop packaging is a separate verification item.** The loopback listener uses
`com.sun.net.httpserver`, so jpackage/jlink builds need the `jdk.httpserver` module, and a
fixed redirect port cannot be registered as a real installed-app custom scheme.

## Consequences

- Two moving parts where one was planned: KMPAuth for consent, ~200 lines of ours for
  refresh. Accepted, because the alternative is a feature that cannot run unattended.
- One more Gradle dependency, at an alpha, compiled against a **newer Kotlin (2.4.0) than
  this project uses (2.3.21)**. It compiles, but it can break on a Kotlin upgrade sooner
  than a normal dependency would. Contained by confining it to the sign-in surface.
- Real-device sign-in is unverified upstream and unverifiable in CI, so it needs an
  explicit manual gate before the merge engine is built on top of it.
- If the library's release settles, the "our half" may shrink to a token-refresh call.

## Links

- `shared/build.gradle.kts` — dependency wiring.
- `core/security/SecureStoragePort.kt` — where the refresh token belongs.
- The calendar merge ADR, which depends on this one being right.
