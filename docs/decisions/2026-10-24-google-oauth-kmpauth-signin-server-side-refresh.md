---
title: "Google OAuth: KMPAuth for the sign-in, the server holds the refresh token"
date: 2026-10-24
status: accepted
slug: google-oauth-kmpauth-signin-server-side-refresh
supersedes: google-oauth-hybrid-kmpauth-plus-own-token-exchange
---

# Google OAuth: KMPAuth for the sign-in, the server holds the refresh token

## Context

The calendar sync has never worked. The "Connect Google account" button did nothing, and the
token exchange that was supposed to follow it had no way to obtain a code.

`2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md` decided on a **hybrid**:
KMPAuth 3.0 performs the interactive sign-in, this application performs the token exchange that
yields a refresh credential. Two things about that decision were wrong, and both were checkable
before it was written:

**The KMPAuth half was never in the repository.** `git log -S kmpauth` finds the ADR and the
comments in `GoogleTokenClient.kt` — and no dependency. `gradle/libs.versions.toml` never named
`io.github.mirzemehdi`, and no `build.gradle.kts` referenced it. The hybrid was decided, half of
it was written (the token exchange), and the other half was not.

**The two halves do not connect.** KMPAuth hands back a `GoogleUser` with an `idToken`, an
`accessToken` and — on the legacy Android path and on iOS only — a `serverAuthCode`. It never
returns an **authorization code**. `GoogleTokenClient.exchangeCode` needs one: it is the
`access_type=offline` request that produces the refresh token. So the hybrid as written had no
wire between its halves, and the button stayed a no-op.

Reading KMPAuth 3.0.6's sources (`providers/kmpauth-google/`) settled the rest:

| | Android | Desktop (JVM) |
|---|---|---|
| Sign-in mechanism | Credential Manager (`GetGoogleIdOption`) → **id token only**; an access token needs a second request through `Identity.getAuthorizationClient` (`play-services-identity`) | system browser → **loopback on a fixed port** (`http://localhost:8080/callback`) |
| Redirect URI | none | `http://localhost:<port>`, registered in the console in advance |
| Refresh token | **never** — `requestOfflineAccess` is not called | **never** — `access_type=offline` is not requested |
| `serverAuthCode` | legacy fallback path only | never |

And the constraint that decides the shape of everything else: **Google blocked the loopback flow
for Android, iOS and Chrome OAuth client types on 21 October 2022** (the "App blocked by Google"
error). The block is on the *client type*, not the platform — a client of type **Desktop app**
may still use loopback. Google's own
[migration guide](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)
says the loopback flow "will continue to be supported on desktop apps" and points mobile clients
at the Google Identity Services Android library.

## Idea

Split the flow the way the platforms actually allow it, and move the half that needs a secret
off the device:

- **The account and the consent stay in the app.** KMPAuth does the sign-in; the app asks for
  the calendar scope.
- **The durable credential stops being the app's business.** The app obtains a one-time
  **server auth code** and hands it to a **Supabase Edge Function**, which redeems it against
  Google with the client secret the app must not have, and keeps the refresh token.

## Decision

**KMPAuth 3.0.6 is a dependency, and it performs the interactive sign-in.**

**The offline authorization is ours, in `androidMain`.** KMPAuth does not expose
`requestOfflineAccess`, so the app builds the authorization request with
`AuthorizationRequest.requestOfflineAccess(webClientId)` from `play-services-identity` — the
Google-documented offline flow — and receives a `serverAuthCode`.

**A Supabase Edge Function exchanges that code.** It holds the web client id and secret, redeems
the code, stores the refresh token on the server, and hands the app an access token. The refresh
token never reaches the device.

**Desktop keeps the app's own loopback.** A Desktop client is a public client: the exchange needs
no secret, so `access_type=offline` works on the device and the app keeps the refresh token in
its keystore. KMPAuth is not used there — its desktop flow returns an access token and no code.

**The client id is a runtime value.** The app reads it from the secure store, and the Google
Cloud console needs three clients for one project: an **Android** client (package name and the
release SHA-1) for the authorization request, a **Web** client (the `serverId` KMPAuth's
Credential Manager step needs, and the client the Edge Function redeems the code with), and the
calendar scope granted on both.

## Rationale

**A refresh token cannot be had on Android without a server.** Google's offline flow hands the
app a *server auth code*, not a token: redeeming it needs the client secret, and a secret inside
an APK is a secret that has leaked. The Edge Function is the server. The project's ADR was right
about the conclusion — "a background sync has to authenticate with nobody present" — and wrong
about the reason: the gap is not KMPAuth's, it is Google's, and no configuration closes it.

**KMPAuth is kept because it removes the part that is genuinely hard.** The Credential Manager
account chooser, the "no Google account on this device" and "Play services out of date" failures,
the cancellation semantics — that is platform-specific behaviour with a long tail of edge cases,
and it is the part a home-grown implementation gets wrong quietly. What stays ours is the
offline request and the server exchange: ordinary HTTP with a body, which a test can drive.

**The Android consent is one prompt, not two.** KMPAuth is asked for the basic scope only, so it
does not open its own authorization screen; the calendar scope is requested once, by our offline
request. Asking KMPAuth for the calendar scope as well would give a second consent screen for a
token the server would replace anyway.

**The sign-out stays ours.** KMPAuth's `signOut` clears the credential state on Android, which is
not the same thing as revoking the grant at Google and deleting the token the Edge Function
holds. "Disconnect" has to mean both, or the app reports a success it did not achieve.

**The alternative was rejected on its own terms.** Using loopback on Android with a Desktop-type
client would have been less work and would have kept the refresh token on the device. It is not
the flow Google recommends for a mobile client, it leans on a client type the app is not, and the
reason Google deprecated loopback — another app on the device binding the same port and taking
the code — is only partly answered by PKCE. It is a workaround, not a design.

## Consequences

- One more dependency, from a personal repository rather than a vendor's. It is Apache-2.0,
  actively released (3.0.6), and its Android path is built on Google's own SDKs — but it is one
  maintainer, and it is confined to the sign-in surface.
- KMPAuth's Google flow is **Compose-bound**: `getUiProvider()` is a `@Composable`, and the
  credential UI is launched through `rememberLauncherForActivityResult`. The screen therefore
  hosts the flow and reports the result to the ViewModel as an intent — the project's
  Intent-driven screens stay the state owner, and the library's state holder stays inside the
  Composable that owns it.
- `GoogleAuthProvider.create()` is process-wide and **first call wins**. A client id pasted after
  the provider exists cannot replace it, so the app says so and asks for a restart rather than
  pretending the new id took effect.
- The Edge Function is a new deployable: a Supabase project, a secret for the Google client
  secret, and a table or vault for the refresh token. It is also the only place the durable
  credential lives — a compromise for an offline grant, made deliberately.
- The offline path **cannot be verified end to end here**: it needs a Google Cloud project, a
  registered Android client with the release SHA-1, and a Supabase project. What the device check
  can prove is that the flow starts, that the browser receives a well-formed authorization
  request, and that a code that comes back is redeemed and stored.
- The desktop path stays as written and needs no Edge Function, which is why the two platforms
  keep different shapes. A third platform has to pick one of them deliberately.

## Deploying it

The code is not the whole of it. Four things have to exist outside the repository, and
each of them has a failure that looks like a bug in the app:

1. **An Android OAuth client** in the Google Cloud project, with the package name
   `com.singularity.todo` and the SHA-1 of the **release** keystore. Google's
   authorization API resolves the client from the package and the signing certificate
   of the app that calls it; without that registration the request fails with a
   `GoogleApiException` that names neither the console nor the keystore.
2. **A Web OAuth client** in the same project. **Its id is the one the user pastes into
   the app's client id field**, and the same value the Edge Function redeems the code
   with. It is the `serverClientId` of KMPAuth's Credential Manager step
   (`GetGoogleIdOption.serverClientId`) and the `serverClientId` of our
   `AuthorizationRequest.requestOfflineAccess(...)`, which Google documents as the client
   that "will be used to identify your backend server". A *Desktop app* client is not
   enough — the offline flow is bound to a web client, and the desktop path redeems the
   code on the device.

   The **Android** client's id is never typed into the app: the authorization API
   resolves the calling app from the package name and the signing certificate alone, so
   the Android client identifies the app without being a setting.
3. **The Edge Function** `google-oauth-exchange`, deployed, with two secrets:
   `GOOGLE_CLIENT_ID` (the web client id) and `GOOGLE_CLIENT_SECRET` (its secret).
   Without the secret the function cannot redeem anything, and without the function
   the Android sign-in ends at a code nobody can use.
4. **The migration** `supabase/migrations/2026-10-24-google_calendar_grants.sql`,
   applied to the project: the table the refresh token lives in, with its RLS policy.

The calendar scope has to be granted on both clients. A grant on the Android client
alone is not enough for the desktop, and the other way round.

## Links

- `docs/decisions/2026-10-05-google-oauth-hybrid-kmpauth-plus-own-token-exchange.md` — the
  decision this one supersedes.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendarSync/auth/` — the client id
  resolver, the credential store and the token client.
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/calendarSync/auth/` — the offline
  authorization request.
- `supabase/functions/` — the exchange function.
- [Google: OAuth 2.0 for native apps](https://developers.google.com/identity/protocols/oauth2/native-app)
- [Google: loopback migration guide](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)
- [Google: authorize access to Google user data (Android)](https://developer.android.com/identity/authorization)
