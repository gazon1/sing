---
title: "Core Auth Oauth Is Entirely Unwired"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #442

**Found in:** MR-4. The plan listed two dead symbols in
`core/auth/oauth/OAuth.kt`; the file as a whole is unreachable.

**Symptom:** `OAuthConfig`, `OAuthResult`, `OAuthTokenData` and
`toOAuthTokenData` have zero references outside their own file — no ViewModel,
no repository, no test, no Koin binding. `TokenError` and `RedirectState` were
deleted in MR-4; the rest was left alone.

**Already ruled out:** not reachable through reflection, DI or a route — it is
plain Kotlin with no registration anywhere.

**Try next:** decide whether Supabase OAuth is still planned. If yes, the file
is a reasonable starting skeleton. If no, delete the remaining 90 lines. A
dead-code sweep should not make the product decision either way, which is why
MR-4 stopped at the two symbols it was asked to remove.

---
