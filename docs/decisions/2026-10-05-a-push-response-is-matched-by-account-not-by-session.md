---
date: 2026-10-05
status: accepted
title: A push response is matched by account, not by session
---

# A push response is matched by account, not by session

## Context

REQ-UA-018 requires that "a response that arrives after the account it was requested under is gone SHALL
NOT change local state". The engine's `push()` read `authRepository.currentSession.value` once, issued
`api.batchPush`, and applied whatever came back — deleting outbox rows as delivered and settling shadows
as confirmed — with no second look. A sign-out or an account switch during the request changed nothing
about that. Issue #181 records the concrete harm: for a switch, the departing account's queued work is
marked delivered on the strength of the incoming account's push, and the device and the server then
disagree with nothing left to say so.

The requirement says *account*. The code has a [Session]. Between the two is a question the requirement
does not answer, and it decides whether the fix works at all.

## Idea

1. **Compare the whole [Session].** "Is this the session the request went out under?" — the literal
   reading of "the session that authorised it".
2. **Compare the [UserId].** "Is this the same account?" — the literal reading of REQ-UA-018, and the
   weaker question.
3. **Compare nothing, and re-plan instead.** Treat any session change as a reason to abandon the cycle
   and rebuild it from the current session.

## Decision

**Compare the account ([UserId]), via `Session.accountIdOrNull`. A change of token is not a change of
account and does not discard the response.**

`Session` is a data class holding an access token, a refresh token and an email alongside the user id.
A token refresh replaces all of it — a new [Session] instance, unequal in every field, same account.
Refreshes are routine: they happen on a timer, and on any 401. So option 1 is not a stricter version of
the requirement, it is a different and broken rule.

Its failure mode is specific and it is silent. Every push whose token rotated mid-flight would have its
response discarded: the outbox row is not deleted, the shadow is not settled, the server's new version
is thrown away. The same patches go out again next cycle, and the same thing happens again. The outbox
never drains, for every account, always — and every individual step reports success, because each push
did reach the server and the server did accept it. The user sees a sync indicator that flickers and a
device that never stops trying. Nothing anywhere says "the token expired".

Option 3 was rejected because it makes a correct response do nothing useful. A profile change is not an
account change, and the engine already handles it by capturing the scope with the request
(`PushPlan.active`) precisely so a mid-flight switch cannot put a patch under one profile and its shadow
under another. Rebuilding the cycle on any session change would discard that, and would re-read the
scope — reintroducing the misattribution the capture exists to prevent.

`Session.Loading` maps to *no account*, not to "the same account". "Not known yet" is not "unchanged",
and treating it as unchanged would apply a response on the strength of a session that has not been
established. The engine only pushes for a `Session.SignedIn`, so this only arises if a refresh is in
flight when the response lands; the strict reading is the one that cannot lose a user's work.

## Rationale

The distinction is *who* against *which credential*. REQ-UA-018 is about ownership: one account's work
must not be settled by another's. A refreshed token is the same account asking the same question with a
newer proof of the same identity, and the server rotating that proof is not a reason to distrust the
answer.

The discarded response is reported rather than dropped in silence, through a fourth field on
`[PushSummary]` rather than by counting it as succeeded or failed. Counted as `succeeded` it would mark
rows delivered that are still queued; counted as `failed` it would send whoever is debugging to look at
a server that in fact accepted the work. The old three-field summary could not say "the server took it
and this device ignored it" at all, which is the exact shape of the defect.

## Consequences

- `Session.accountIdOrNull` is the single place that answers "which account, if any", so the question is
  not re-derived — and not answered differently — by each caller.
- A row whose response is discarded is re-sent later. That is safe because patches carry their patch id
  and the server is idempotent on it; it is also the reason discarding is acceptable at all.
- The rule is about accounts, so a *profile* change under one account is untouched by it and remains
  governed by the captured scope. The two are different questions and
  `SyncEnginePushIdentityTest` pins both, because a rule about *who* that was quietly a rule about
  *when* would look identical on the sign-out case and wrong on the refresh case.
- The switch path from ADR `2026-10-05-switching-users-delivers-then-erases-the-departing-user` makes
  most of this unreachable in the app's own flow — a switch cannot start until delivery finishes — and
  that is worth saying plainly: this is a defence in depth, and it is the defence that holds when the
  switch is a token refresh, a second device, or a future caller that is not a switch at all.

## Links

- Issue #181; REQ-UA-018 in `openspec/changes/account-switch-and-clock-drift`.
- ADR `2026-10-05-switching-users-delivers-then-erases-the-departing-user`.
