---
name: singularity-todo-test-flaky-prevention
description: Rules to prevent test flakiness in the Singularity Todo project. Covers shared ProfileAwareCurrentUser, deterministic dates, advanceUntilIdle vs delay, and FakeRepositories wiring. Use when writing new VM tests or debugging flaky tests.
---

# Test Flaky Prevention Rules

This skill documents the hard-won lessons from the test-suite-cleanup session (2026-09-25). Three real bugs were found that caused test failures or misleading assertions. Follow these rules to avoid repeating them.

---

## Rule 1: Share One `ProfileAwareCurrentUser` Across All Fakes

**Symptom:** VM state stays `Loading` indefinitely. `advanceUntilIdle()` has no effect. Tests that assert `Loading` pass for the wrong reason.

**Root cause:** When a `FakeRepository` creates its own internal `FakeProfileAwareCurrentUser`, that user runs its collectors on `Dispatchers.Default` — outside the test's `StandardTestDispatcher`. `advanceUntilIdle()` cannot virtualize real `Dispatchers.Default` work.

**Fix:** Every `Fake*Repository` must receive `ProfileAwareCurrentUser` as a constructor parameter. All call sites pass the test's shared instance.

```kotlin
// ✅ CORRECT — share the same user across all fakes
private val fakeCurrentUser = FakeProfileAwareCurrentUser(
    FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
)
private val fakeReminderRepo = FakeReminderRepository(fakeCurrentUser)  // share!
private val fakeTaskRepo = FakeTaskRepository(fakeCurrentUser)           // share!

// ❌ WRONG — each fake creates its own ProfileAwareCurrentUser on Dispatchers.Default
private val fakeReminderRepo = FakeReminderRepository()  // internal Dispatchers.Default!
```

**Why this matters:** `ProfileAwareCurrentUser.init` launches a `combine` collector that reads `profileRepository.activeProfileId`. If this runs on `Dispatchers.Default`, `advanceUntilIdle()` cannot advance it.

**The wiring checklist (add to every VM test):**
1. Create `fakeCurrentUser` first
2. Pass it to every `Fake*Repository` that accepts it
3. If a repository overload defaults to `FakeProfileAwareCurrentUser()`, **replace it** with the explicit shared instance

**Known repositories that accept `ProfileAwareCurrentUser`:**
- `FakeReminderRepository(currentUser)` — must pass shared user
- `FakeTaskRepository(currentUser)` — must pass shared user
- `FakeProjectsRepository(currentUser)` — must pass shared user
- `FakeNotesRepository(currentUser)` — must pass shared user
- `FakeTagsRepository(currentUser)` — must pass shared user
- `FakeAttachmentRepository(currentUser)` — must pass shared user

---

## Rule 1b: Take the Expected Identity From the Same Flow the Code Uses

**Symptom:** a profile-isolation test passes on `:shared:jvmTest` and fails on
`:shared:testAndroidHostTest` (Robolectric). Or it fails intermittently, with the
seeded row missing from the result.

**Root cause:** `CurrentUser.userId` and `ProfileAwareCurrentUser.scopedUserId` are
`StateFlow`s seeded with a value (`"anonymous"`) and corrected later by collectors on
`Dispatchers.Default` (see Rule 1). Two consumers that read at different instants can
therefore get **different identities** — the race is against the collector, not against
your test. Robolectric schedules that collector later than the JVM does, which is why one
source set passes and the other fails.

A test that recomputes the expected id with its own `combine(currentUser.userId, …)` is
racing in exactly the same way, and can win the seeding read while the code under test
loses it.

**Fix:** derive the expected identity from the production derivation, and prefer the
`live*` flows, which are derived from the session and cannot lag.

```kotlin
// ✅ CORRECT — the same flow the repository scopes its query with
val scoped = currentUser.liveScopedUserId.first()

// ❌ WRONG — a second derivation that races the collectors independently
val scoped = combine(currentUser.userId, profiles.activeProfileId) { u, p -> … }.first()
```

**When you need the identity at all:** to *seed* rows the code will query for. If a test
seeds under one identity and queries under another, every row is invisible and the failure
looks like a filtering bug in production code. Check this before debugging the tool.

Related: ADR `2026-10-04-derived-identity-flows`.

---

## Rule 2: Use Fixed Dates in Domain Logic Tests

**Symptom:** `AssertionFailedError: Expected value to be true` on a date-comparison test. Fails only on certain days of the week or months.

**Root cause:** Using `Clock.System.now()` or `todayInSystemZone()` in a domain test. The test is non-deterministic — it passes on some dates and fails on others.

```kotlin
// ❌ WRONG — uses real system date
private val today: LocalDate get() = todayInSystemZone()

// ❌ WRONG — uses real system time
private val now: Instant get() = Clock.System.now()

// ✅ CORRECT — fixed constant
private val today = LocalDate(2026, Month.SEPTEMBER, 16)
private val now = Instant.fromEpochMilliseconds(0)
```

**When fixed dates ARE appropriate:**
- Domain logic tests (pure functions, selectors, evaluators)
- Date arithmetic tests (calendar math, RRULE generation)
- Any test that validates business rules, not integration

**When real time IS appropriate:**
- Integration tests where system time is part of the scenario
- End-to-end tests
- Tests of actual scheduling/notification logic

---

## Rule 3: Know When to Use `delay()` vs `advanceUntilIdle()`

**`advanceUntilIdle()`** works when the test dispatcher controls ALL coroutines in the chain:
- Pure test dispatcher chains
- Fakes created with test dispatcher
- No `Dispatchers.Default` in the chain

**`delay(ms)`** is required when:
- `FakeProfileAwareCurrentUser` runs on `Dispatchers.Default` (even with PR-3.1 fix, some code paths still use default dispatcher)
- Real network/HTTP is involved (RecordingHttpClient pattern)
- `java.util.Timer` or `ScheduledExecutorService` is used

**The decision tree:**

```
Does the VM under test use FakeProfileAwareCurrentUser internally?
├─ YES → Can advanceUntilIdle() drive all collectors?
│         ├─ YES (shared dispatcher) → advanceUntilIdle()
│         └─ NO (internal Dispatchers.Default) → delay() + comment explaining why
└─ NO → Can advanceUntilIdle() drain all flows?
          ├─ YES → advanceUntilIdle()
          └─ NO (external dispatcher) → delay()
```

**The 500ms rule:** `NoRealDelayInTestRule` allows delays up to 500ms. This threshold accommodates `stateIn(WhileSubscribed(5000))` — the subscription establishment delay. If you need `delay(1000)`, something is wrong with the test architecture.

---

## Rule 4: Comment the Reason for `delay()` in Tests

```kotlin
@Test
fun `ToggleComplete sets completedAt in repository`() = runTest {
    val vm = createVm(backgroundScope, task.id)
    delay(100) // Allow subscription to establish before acting

    vm.onIntent(TaskDetailIntent.Domain.ToggleComplete)
    delay(50) // scope.launch { mutate(...) } executes immediately

    assertNotNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)
}
```

**Good comment explains WHY** — not just what delay is used for. The 100ms establishment delay is for `stateIn` subscription timing; the 50ms is because the mutation coroutine fires synchronously.

**Bad comment:**
```kotlin
delay(100) // wait
delay(50) // wait more
```

---

## Rule 5: Verify Assertions After `advanceUntilIdle()`

When converting `delay()` to `advanceUntilIdle()`, make sure the assertion happens AFTER the advancement:

```kotlin
// ✅ CORRECT — advance THEN assert
advanceUntilIdle()
assertEquals("Edited title", fakeTaskRepo.tasks.value["t1"]?.title)

// ❌ WRONG — assert BEFORE advance (always passes with stale state)
assertEquals("Edited title", fakeTaskRepo.tasks.value["t1"]?.title)
advanceUntilIdle()
```

---

## Rule 6: Every New Test File Needs This Header

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)  // Required for runTest + TestScope
class FooViewModelTest {

    // ─── Fakes — create shared currentUser first ───────────────────────────────
    private val testUserId = UserId("test-user")
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
    )
    private val fakeTaskRepo = FakeTaskRepository(fakeCurrentUser)
    private val fakeReminderRepo = FakeReminderRepository(fakeCurrentUser)

    // ─── VM factory — always pass testScope(this) ──────────────────────────────
    private fun createVm(scope: CoroutineScope = this) =
        FooViewModel(
            deps = FooDeps(taskRepo = fakeTaskRepo, reminderRepo = fakeReminderRepo),
            scope = testScope(scope),
        )

    // ─── Seed helper ─────────────────────────────────────────────────────────
    private fun seedTask(...) { ... }
}
```

---

## Flaky Test Debugging Checklist

When a VM test is flaky or stuck in `Loading`:

1. **Is `fakeCurrentUser` shared?** → Rule 1
2. **Does the VM's repository chain use `Dispatchers.Default`?** → Pass test dispatcher explicitly
3. **Is there a `combine` that requires wall-clock time?** → Use `delay()` with explanation
4. **Does the assertion check `Loading` after a transition that should complete?** → Verify VM actually transitions with `advanceUntilIdle()`
5. **Are there date-comparisons that depend on today's date?** → Rule 2

---

## See Also

- `singularity-todo-test-helpers` — `testScope`, `assertIs`, `awaitState`
- `singularity-todo-testable-vm` — canonical VM pattern, dispatcher injection
- `singularity-todo-quality-tools` — running tests, detekt
- `docs/decisions/2026-09-25-test-flaky-root-causes.md` — full ADR with 3 bugs documented
- `docs/decisions/2026-09-25-testable-vm-dispatcher-clock.md` — ADR for dispatcher injection
