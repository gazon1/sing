---
title: "Derived identity flows: never read a cached userId that is corrected asynchronously"
status: accepted
date: 2026-10-04
tags: [auth, profiles, coroutines, architecture, data-flow]
---

# Context

`ReadToolsProfileAwareTest.search_tasks_does_not_match_local_user_when_profile_is_agent`
passed on the JVM and failed on `:shared:testAndroidHostTest` (the
Robolectric/Android source set) — the same test, the same assertion, two
different outcomes. It is a regression guard for a data-leak bug: the MCP read
tools once defaulted a blank userId to the literal `"local-user"`, so personal
rows leaked into agent queries.

The identity the tools query with is built from two layers:

```
CurrentUser.userId                 →  "anonymous" seed, corrected by an async collector
  └─ ProfileAwareCurrentUser.scopedUserId  →  seeded from the above, corrected by a second async collector
       └─ observeForCurrentUser { uid }     →  uid scopes every repository read
```

Both layers are a `MutableStateFlow` seeded with a value and corrected later by
`scope.launch { ... }` on a background dispatcher. That design is deliberate and
documented — the eager seed exists so an imperative `.value` read in a test body
that runs before the first `collect` is not wrong.

The cost of the eager seed is a window. Between construction and the collector
running, the flow reports the seed. A `StateFlow` cannot be lazy *and* have a
value, so the two requirements are in tension — and the collector resolving the
tension makes two consumers reading at different instants able to disagree.

That is what the test hit. It seeded a task under the identity it computed, and
the tool then queried with a different one:

- the test's read landed **before** the collector → identity `ai-agent/anonymous`
- the tool's read landed **after** it → identity `ai-agent/u-1`

Nothing matched, and the agent's own task came back missing. On the JVM the
collector wins the race both times, so the test is green there. On Robolectric it
does not. `observeForCurrentUser` has 106 call sites across the production
repositories, so the same window exists on every user-scoped read the app makes
shortly after a sign-in or a profile switch — it is not confined to tests.

# Idea

The staleness is not inherent to caching; it is inherent to *caching a derived
value that is corrected asynchronously*. The upstream sources are already
synchronous and never stale: `AuthRepository.currentSession` and
`ProfileRepository.activeProfileId`. Deriving from them on collection cannot lag,
because there is nothing to correct.

# Decision

**Keep the cached `StateFlow`s for imperative reads, and add derived cold flows
for reactive reads.** The two accessors then each serve the case they are right
for, and the choice is explicit at the call site:

- `CurrentUser.liveUserId: Flow<UserId>` — `currentSession.map { effectiveUserId(it) }`
- `ProfileAwareCurrentUser.liveScopedUserId: Flow<UserId>` — `combine(liveUserId, activeProfileId)`
- `observeForCurrentUser` switches to `liveScopedUserId.flatMapLatest { … }`

`liveScopedUserId` derives from `liveUserId` rather than from the cached
`userId`, so the whole chain is derived from the session. `combine` over live
flows emits the true pair synchronously on collection.

The collectors in `init` stay: they keep the `StateFlow`s warm for `.value` reads
and for UI that wants a value without collecting.

The test now takes its expected identity from `liveScopedUserId` — the same
production derivation the repository uses — instead of hand-rolling its own
`combine`. That removes a duplicated derivation which was itself the thing
racing, and makes the test assert the real contract rather than an approximation
of it.

# Rationale

Two different questions were being asked of one property. "What is the current
identity?" wants a value now, and an eager seed is the honest answer even if it
may be one dispatch behind. "What identity should this *subscription* be scoped
to?" wants the truth at the moment the subscription starts, and a cached
replica is the wrong data structure for it — the subscription is the thing that
has to be correct, because it is what selects rows.

Note the ordering in `liveScopedUserId`: it must combine `liveUserId` with
`profileId`, never the cached `userId` with `profileId`. Combining the two
cached flows would have left the session leg stale and fixed only the profile
leg — which is exactly the intermediate state this bug passed through.

# Consequences

- Every user-scoped repository read is now immune to the window, because all 106
  `observeForCurrentUser` call sites go through the derived flow.
- The rule is new and easy to break: **do not add a new consumer of
  `userId`/`scopedUserId` that needs freshness.** Use the `live*` variant. The
  KDoc on each says which one it is for, and says why.
- The duplicated `combine` in the test is gone; the test now fails if the
  production derivation changes, which is the point of a guard test.
- A future refactor that "simplifies" `liveScopedUserId` into
  `scopedUserId.flatMapLatest {}` would reintroduce this. The test is the
  regression guard: it fails on whichever source set loses the race first, so
  the fix is not optional to notice — only to diagnose.
- Two names now coexist (`userId` / `liveUserId`, `scopedUserId` /
  `liveScopedUserId`). That is a real cost, paid deliberately: a single property
  cannot serve both questions correctly, and collapsing them would make one of
  the two call sites wrong in a way no type system here would catch.

# Links

- ADR `2026-10-04-test-execution-integrity` — the executed-count gate that surfaced this
- `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/CurrentUser.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileAwareCurrentUser.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/repository/UserScopedFlow.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/tools/ReadToolsProfileAwareTest.kt`

# Addendum (2026-10-04): the rule is now enforced, and it found a live instance

The ADR above documents the defect and the fix but left the rule to prose. A rule
nobody can violate by accident is a comment, so
`CachedIdentityReadArchitectureTest` now fails the build on the pattern.

The rule it encodes is narrower than "do not read `.value`", because 159 reads
across 43 files are correct: a repository write picks one identity at the moment of
the write, and pinning that identity is the intended design. What is forbidden is
reading `.value` **inside a reactive body** — a `combine`/`collect`/`map` lambda —
where the value is sampled once and then reused for every later emission.

Konsist 0.17 has no declaration type for a lambda, so the test is a brace-matching
scanner over the source text. A hand-written scanner is only trustworthy if it is
itself tested, so eleven tests run the same function over synthetic sources: a read
inside `flow { }` is caught, a read in an imperative function is not, a read in a
`launch` body is not, reads in KDoc are not, and `combine(u.scopedUserId) { }` — the
*flow*, not its value — is not.

Two design decisions came out of writing those tests rather than the other way
round:

- **`launch` is not a reactive block.** A `scope.launch { }` body runs once, and the
  first version of the rule included it. That flagged `TaskTimeSlot.start`,
  `.stop` and `.createManual` — three correct one-shot writes. A gate that fires on
  correct code is a gate people learn to bypass.
- **A read in a reactive call's argument list is not a finding.** It is evaluated
  once, when the flow is constructed, which is the same one-shot semantics as an
  imperative write. The cost is a known gap: `combine(u.scopedUserId.value) { }` is
  not flagged, and such a combine is useless rather than wrong, because the sampled
  value can never change.

**The one production finding was real.** `TaskTimeSlot`'s init block read
`currentUser.scopedUserId.value` inside `.collect { entries -> … }` and passed it to
`getOpenEntry` on every emission — the same class of bug as `observeForCurrentUser`,
in a screen slot rather than a repository. It now reads
`currentUser.liveScopedUserId.first()` inside the collector, so the identity is
re-derived per emission. The structurally better fix is to make the identity a flow
input of the `combine` so a profile switch re-emits by itself; that is a
refactor, not a bug fix, and is left for a deliberate pass.

The scanner's remaining limits are stated in the test's KDoc: it tracks braces, so a
reactive call whose lambda opens on a later line is missed, and it only knows the
combinator names it lists. Both err toward silence, which is the right direction —
a false positive costs an allowlist entry, a false negative costs a bug.
