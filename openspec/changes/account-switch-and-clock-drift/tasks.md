# Tasks — account-switch-and-clock-drift

Issues: #179, #181, #182.
ADRs: `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`,
`2026-10-05-switching-users-delivers-then-erases-the-departing-user`,
`2026-10-05-hybrid-clock-is-merged-and-drift-stops-the-write`.
Spec: `user-authentication` REQ-UA-015…018, `offline-sync` REQ-OS-019.

Ordered so each block is verifiable before the next depends on it. Every behaviour task
names its test.

## REQ-UA-015 — an account-less session is local and survives

- [ ] `shared/` Persist the account-less session, with an owner id that is stable for the
      life of the install — the same requirement the device id already has. **Test:** a
      restart with no account signed in finds the same data under the same owner.
- [ ] `shared/` Stop calling the provider for an account-less sign-in, and remove the now
      dead call. **Test:** an account-less sign-in reaches the gateway zero times and
      reports a session.
- [ ] `shared/` Offer the choice after a successful sign-in when there is data, and honour
      keeping: the account-less rows are re-attributed to the signed-in account.
      **Test:** keeping moves the rows to the new owner and leaves none behind.
- [ ] `shared/` Honour discarding through the same owner-scoped delete a user switch uses —
      one implementation, not two. **Test:** discarding removes every row of that owner
      and nothing belonging to anyone else.
- [ ] `shared/` Do not ask when there is nothing to ask about. **Test:** a sign-in from an
      empty account-less session does not raise the choice.
- [ ] `shared/` Treat declining the choice as a completed sign-in, not a failure.
      **Test:** the session is signed in and only the account-less data is gone.

## REQ-UA-016, REQ-UA-017 — signing out and switching are different operations

- [ ] `shared/` Keep sign-out as it is today: credentials cleared, local data retained, no
      network required. **Test:** the existing sign-out-with-the-server-unreachable test
      still passes, and local rows survive it.
- [ ] `shared/` Add the switch as a distinct path, not a variant of sign-out, and make the
      distinction visible in the code rather than in a comment. **Test:** a gate or test
      asserting the two are separate entry points.
- [ ] `shared/` Before erasing, deliver the departing account's queued changes under its
      own session. **Test:** the outgoing push carries the departing account's scope.
- [ ] `shared/` Erase only after the delivery succeeded. **Test:** a delivery that fails
      leaves every row in place and the incoming account not signed in.
- [ ] `shared/` Refuse a switch that cannot deliver, with a message naming the network.
      **Test:** offline with queued work — nothing is erased and nothing is half-applied.
- [ ] `shared/` Wipe by owner, so every profile of the departing account goes and not only
      the active one. **Test:** two profiles, one switch, both gone.
- [ ] `shared/` Report progress during the switch so it cannot be mistaken for a hang.
      **Test:** the state names the delivery step while it runs.

## REQ-UA-018 — queued work belongs to the account that made it

- [ ] `shared/` Re-check the session before applying a push response; if it is not the
      session the request was made under, discard the response and leave the rows queued.
      **Test:** the session is switched while a push is suspended; the rows are still
      queued and the shadows are untouched.
- [ ] `shared/` Settle a response only under the captured scope, never a re-read one.
      **Test:** the profile changes mid-flight and the shadow is settled under the
      original profile.
- [ ] `shared/` Pin the existing no-network sign-out behaviour with a test, so the
      distinction from a switch is regression-protected on both sides.

## REQ-OS-019 — the clock is merged, and a wrong one stops the write

- [ ] `shared/` Merge the received clock on every event, so a received change is always
      ordered after the one that caused it. **Test:** receive an event, then assert the
      next locally written clock is strictly greater than the received one.
- [ ] `shared/` Refuse to write when a server reading disagrees by more than five
      minutes, and do not move the local clock. **Test:** a reading an hour ahead changes
      neither the clock nor the written patch.
- [ ] `shared/` Report the refusal as its own condition, distinguishable from a server
      refusal and from a transport failure. **Test:** the reported code is the drift one,
      and a test asserts it is not classified as retriable — retrying does not move a
      clock.
- [ ] `shared/` Keep local editing working while sync is refused. **Test:** a task is
      created and queued during a drift refusal.
- [ ] `shared/` Absorb a disagreement within the bound rather than refusing it.
      **Test:** a reading three minutes out is merged and the write proceeds.

## Not in this change

- [ ] The server-side change REQ-OS-019's drift half needs: a clock reading on the wire,
      which is a schema change and a protocol version bump. Until it lands, the drift half
      is inert and only the merge is observable. **Test:** none yet — the merge tests above
      do not need it; a drift test does, and is blocked rather than faked.
- [ ] The design of the keep-or-discard screen. Behaviour is specified; the surface is not
      part of this change.
- [ ] Archiving `supabase-auth-and-sync`, which the three superseded requirements have to
      be reconciled into by hand.

## Verification

- [ ] `shared/` `./gw :shared:jvmTest` — the new tests and the existing authentication,
      credential-store, sync-engine and view-model tests.
- [ ] `shared/` `./gw detekt`.
- [ ] `openspec validate --all --strict`.
