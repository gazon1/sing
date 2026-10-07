# a-profile-that-was-never-activated-was-reported-as-activated

Backlog entry: `profile-bootstrapper-reports-a-failed-switch`. Found by the
dropped-`Result` audit of 2026-10-07; no issue number yet.

## What

`ProfileBootstrapper.run` called `repository.switchTo(profile.id)`, discarded the
`Result`, returned `activated = profile.id` and logged "activated profile".

The fix is `.getOrThrow()`.

## Why the audit under-rated this

The audit filed it as one more dropped `Result` on a settings write — user-visible
state, low severity. Reading the caller changes that.

`mcp/Main.kt:164`:

```kotlin
result.activated?.let { activated ->
    val agentId = activated.value
    retromigrateRowsToAgentScope(
        profileId = profileCliArg ?: "ai-agent",
        localUserId = localUserId,
        newUserId = "$agentId/$localUserId",
    )
}
```

The returned id is an **input to a destructive migration**, not a status line. Every
row owned by the unscoped local user id gets moved into a scope keyed on the activated
profile. A switch that failed but still returned an id moves the rows into a namespace
the server is not running under — and the rows are no longer reachable under the old
one either, because the migration is a move.

So the failure mode is: local write succeeds, `switchTo` fails, the agent keeps running
under the previous profile, and the user's data has been relocated to match a profile
that was never selected.

**Throwing, not returning null.** Two reasons, and the second is the one that decides:

1. The only production caller already wraps the whole bootstrap in a try/catch that
   logs and continues against the default profile (`mcp/Main.kt:175-179`). The throw
   lands in a handler that exists, states why, and — crucially — does not reach the
   migration.
2. `null` is also what a successful `activateName = null` run returns. Returning `null`
   would leave the caller unable to tell "nothing asked for a switch" from "the switch
   failed" — and the second is a failure worth surfacing while the first is routine.
   A name that was not found after seeding is a third, legitimate outcome that keeps
   returning `null`, because there was never a switch to fail.

## How

One `.getOrThrow()`, plus a `@throws` line on `run` so the new contract is on the
signature rather than in a comment.

The KDoc already claimed the caller could read the id "without a separate `.first()`
call — avoiding a potential race". The whole point of that value is that the caller
trusts it, so returning one for a switch that failed defeats what the doc promises.

**Invariant:** the fake grows a `switchToFailure` hook, and there is a control for each
of the three outcomes that share the `if` — switched, not-found, not-requested. A fix
that made every non-null `activateName` throw would pass a single control and break two.