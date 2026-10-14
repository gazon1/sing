---
title: "Core Auth Oauth Is Entirely Unwired"
date: 2000-01-01
status: closed
tags: ["deferred"]
---

**Status: CLOSED — the code was deleted in commit 51217e52.**

**Tracked as:** #442 (closed)

**Found in:** MR-4. The plan listed two dead symbols in
`core/auth/oauth/OAuth.kt`; the file as a whole was unreachable.

**Resolution (2026-10-14):** The OAuth decision was made — Supabase OAuth is not
planned for the current iteration. All OAuth-related code (`OAuth.kt`, `PKCE.kt`,
`OAuthTokenRefresh.kt`, `IdToken.kt`) was deleted in commit 51217e52 along with
the dead symbols. This ADR is closed as the code no longer exists.

**Why the ADR was still open:** the code deletion happened but this ADR was not
updated to reflect it.

---
