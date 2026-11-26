# PR review — #3191: переквалификация тринадцати Maestro-потоков

- Repository: gazon1/sing
- Target: PR #3214 — https://github.com/gazon1/sing/pull/3214
- Base → Head: main (`754e445791`) → `gazon1/test-maestro-requalify-the-thirteen-flows-whose` (`f86c4a6b747965ee002bb8c9f5d7cd76a137388e`)
- Reviewed at: 2026-11-26T03:44Z
- Scope: 24 changed files — two product fixes (saved selected tab, undo-offer
  lifetime), eight Maestro flow/helper fixes, the orientation fixture and its
  give-back, the debt registry (`config/docs/maestro-debt.yaml`), the
  qualification evidence (`config/docs/maestro-flow-qualification.yaml`), three
  ADRs and their tests.

## Verdict

APPROVE — three independent reviewer passes (correctness/security, architecture/scope,
tests/acceptance) returned zero findings and an explicit approve on the head that
the pull request carries. Two of the seven rounds produced actionable findings; both
were fixed in the change itself, not explained away, and both fixes are measured by
fresh evidence. Nothing in any pass requires a code change.

The head under review is the rebased branch: `main` moved on (44 commits) while the
branch was open, three files that both sides touched were resolved by hand, and the
seed helper — hashed into the fingerprint of every flow — moved the fingerprints of
all fourteen flows, so all fourteen were measured again on the rebased tree.

## Checks

| Check | Command | Result | Notes |
|---|---|---|---|
| Reviewer A — correctness / security | `chatgpt-use ask --busy wait --model current --timeout 1800` (round 7) | PASSED | APPROVE, no findings; checked the saved-state seam, the undo lifetime, the fixture give-back, the helper tap and the evidence registry |
| Reviewer B — architecture / scope | same backend (round 7) | PASSED | APPROVE, no findings; checked the KMP placement of `Nav3SavedState`, the `allowedRoutes` guard, the ADR scope boundary, the serialization reuse |
| Reviewer C — tests / acceptance | same backend (round 7) | PASSED | APPROVE, no findings; checked the three acceptance criteria of #3191 against the records |
| Qualification gate | `python3 scripts/check-maestro-flow-qualification.py` | PASSED | exit 0 — `116 flow(s) in blocking scopes: 48 baselined, 17 qualified, 51 excepted`; none of the fourteen flows this change owns is excepted |
| Flow measurement | `scripts/maestro-qualify-flows.py --record --attempts 3 --serial emulator-5590 --skip-install` | PASSED | 14 flows × 3 consecutive attempts, one build (`3c637206d564`), one environment (API 36, `sdk_gphone64_x86_64`, 1080x2400, density 420), Maestro 2.10.0 |
| Wait budgets | `python3 scripts/check-ui-wait-budgets.py` | PASSED | exit 0 |
| Cheap Maestro gates | catalogue, hideKeyboard, keyboard-commands, seeded-titles, screen-geometry, selector validation | PASSED | green over 130 flows |
| Architecture gate | `easement check` | PASSED | 0 violations |
| Undo-lifetime guard | `./gw :shared:archTest --tests "com.singularity.todo.arch.UndoOfferDurationTest"` | PASSED | 2 tests green (the undo offer is `Long`, a plain notice stays `Short`) |
| Orientation-fixture tests | `python3 -m unittest discover -s scripts/tests -p "test_maestro_orientation_fixture.py"` | PASSED | 10 tests green (the rotation, and the give-back) |
| PR CI | `gh pr checks 3214` | SKIPPED | skipped on the author's explicit instruction (all CI/pre-commit/pre-push checks to be skipped) |

## Findings

Seven rounds of review, three roles each. Two rounds produced a finding; both were
fixed inside this change and re-measured. Round 7 — the round on the rebased
branch — produced no finding in any role.

The round-7 change under review was the rebase itself. `main` moved on twice while
the branch was open (PR #3209, and later the seed-helper migration #2554/#2556, 44
commits). Three files that both sides touched were resolved by hand: the no-results
flow keeps both sides' lines, the pinned-filter flow keeps the wait this change
added for it, and the qualification registry was resolved semantically — `main`'s
exceptions for these flows are gone (their records replace them, which is what
#3191 asks for) and the stale `attachments/02-delete` exception was dropped because
it no longer matches the merged flow. The seed helper is hashed into the
fingerprint of every flow, so its contract-text change moved all fourteen
fingerprints; all fourteen were therefore measured again, 42 consecutive attempts,
no `test_failure` and no `infra_failure`, on the build `3c637206d564`.

1. **Round 1–2 (roles A, B) — the orientation fixture never gave the device back.**
   It switched automatic rotation off and left it off, so a physical phone kept the
   fixture's setting after the run. Fixed: the device's own `accelerometer_rotation`
   and `user_rotation` are read before the first change and put back when the run is
   over (`scripts/src/emulator/maestro/_fixtures.py`, `restore_orientation`,
   registered as a close callback in `_cli.py` after the screen-off callback so the
   device is put back while its screen is still awake). Each setting is taken on its
   own — a device that answers for one and not for the other still gets the readable
   one back. Ten tests in `scripts/tests/test_maestro_orientation_fixture.py`.
   ADR: `docs/decisions/2026-11-25-a-fixture-gives-back-what-it-took.md`.
2. **Round 3 (role C) — the selected tab did not survive a rotation.** A product
   defect, not a test defect: `Nav3StateFactory.android.kt` held the selected
   top-level route in a plain `remember`, `MainActivity` declares no
   `android:configChanges`, so a rotation recreated the Activity, the shell came
   back on Today and the stack that held the open editor was never composed again.
   The route is saved state now
   (`shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt`,
   `rememberSaveable(stateSaver = navKeySaver(topLevelRoutes))`); a payload this build
   cannot decode degrades to the start route instead of crashing. ADR:
   `docs/decisions/2026-11-25-the-selected-tab-survives-the-configuration-change.md`
   — and it states in Consequences that only the selected tab is saved, not the tab
   history, so the boundary reads as a contract and not as an oversight.
3. **Round 5 (role A) — the undo offer was gone before the tap that accepts it.**
   Also a product defect: `NotificationHost` showed the delete snackbar with
   `SnackbarDuration.Short` (four seconds) while the tap that presses "Undo" needs
   three to nine seconds on a loaded device — the element lookup alone costs about
   2.4 s. The undo offer is now `SnackbarDuration.Long` (ten seconds), which is what
   Material 3 prescribes for a snackbar that carries an action; a plain notice stays
   `Short`. The lifetime is pinned by a guard test
   (`shared/src/jvmTest/kotlin/com/singularity/todo/arch/UndoOfferDurationTest.kt`).
   ADR: `docs/decisions/2026-11-26-the-undo-offer-outlives-the-tap.md`.
4. **Round 5 (role A) — the attachment tap could land on the system navigation bar.**
   Removing `centerElement: true` from the two attachment helpers left the "+ Add
   file" row at the very bottom of the sheet window; on a phone with gesture
   navigation that is under the system bar, so the tap could reach the system
   navigation instead of the row — the flow would pass on a device without a gesture
   bar and fail on one that has it. Accepted and fixed in the helper: the tap now
   addresses a fixed point inside the row (`point: "50%,25%"`), so it does not depend
   on how far the sheet happened to be scrolled, and the row itself is what is
   addressed. The change invalidates the fingerprint of every flow that uses the
   helper, so the five `attachments/*` flows were measured again — three consecutive
   attempts each — on the build that carries the fix.

Round 3 also answered a remark the round-2 answer left open — the attachment
helpers no longer centre their scroll — explicitly, in both roles that raised it
(roles A and C in round 6): **not a finding**. The add-file row is the last thing in
the sheet, and Maestro's centring never settles on it (measured 2026-11-25); the
plain scroll stops as soon as the row is visible, which is what the helper promises.

## Implicit decisions and trade-offs

The reviewers were asked, in each role, what the implementation decided that the
requirements did not state. The decisions they found and accepted:

1. **Save only the selected top-level route, not the tab history** — the alternative
   was saving the whole history. One key reuses the existing saved-state mechanism
   and satisfies the rotation contract; the Back history is not restored, and the ADR
   says so. Role B calls this a legitimate, documented scope boundary rather than an
   accidental regression, and agrees with the trade-off.
2. **Reject a restored route that is outside `allowedRoutes`** — the alternative was
   to trust the serialized payload. A stale route must not point the shell at a
   nonexistent stack; the fallback is the start route.
3. **Treat undecodable saved state as recoverable** — the alternative was to fail
   fast on deserialization. The trade-off is that the user may return to Today.
4. **Give the undo offer ten seconds** — the alternative was to keep four seconds
   and optimize the automation. Material 3's action-bearing snackbar duration is
   appropriate; users get more time to reverse a deletion. This is a visible product
   behaviour change, not merely a test adjustment.
5. **Restore only the readable device settings** — the alternative was to treat both
   orientation settings as one atomic pair, or to abandon restoration if either is
   unreadable. The restoration is best-effort rather than atomic.
6. **Keep portrait throughout the run and give the device back at the end** — the
   alternative was to restore after each flow. Every flow gets a consistent screen
   geometry; the phone's owner gets their settings back when the run ends.
7. **Keep the two causes that did not reproduce as accepted debt** — the alternative
   was to remove them after three successful attempts. Keeping them means a
   recurrence reads as the same cause and not as a new one.
8. **Tap the upper quarter of the attachment row** — the alternative was to restore
   the centred scroll. The tap is relative to the element, not the screen, and avoids
   a screen coordinate near the gesture-navigation area.
9. **Anchor the qualification to the tested APK, not to the commit that contains the
   records** — the alternative was to record the final pull-request commit. The
   evidence file itself changes the commit that contains it, so naming that commit
   would create a circular dependency. The build is kept reachable through the tag
   `qualification-3191-rebase-build`.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| The six new causes each have a cause of their own in `config/docs/maestro-debt.yaml`, with the issue that owns it | MET | six entries with `issue: 3191`; a seventh was added by the requalification itself (the undo offer, `owner: product`, `status: fixed`) |
| The thirteen flows pass three consecutive attempts on a documented environment, and each exception is replaced by a qualification record | MET | 14 records (the thirteen plus `attachments/02-delete`, whose fingerprint moved with the same helpers), each with three passing attempts on one build and one environment — 42 consecutive passing attempts, no `test_failure` and no `infra_failure` |
| `python3 scripts/check-maestro-flow-qualification.py` exits 0 with no exception left for these flows | MET | exit 0 — `116 flow(s) in blocking scopes: 48 baselined, 17 qualified, 51 excepted`; the 51 remaining exceptions belong to `main` and to other issues |

## Not verified

- PR CI — skipped on purpose, per the explicit instruction to skip all
  CI/pre-commit/pre-push checks. The reviewers could not independently confirm the
  local verification run; the PR body quotes it.
- `scripts/ci/check-maestro-results.py` — it measures the report set of a full CI run
  against the committed floor, and this branch has no CI run, so `build/maestro-results/`
  holds only a stub. It fails the same way on the untouched tree: the failure is the
  missing run, not this change.
- `sgate adr_status_policy` — reports 16 overdue `open` ADRs, all written in October
  and none touched by this change. Both ADRs of this change are clean.

## Reviewers

| Role | Provider | Verdict | Notes |
|---|---|---|---|
| Correctness / security | chatgpt (Pro) | APPROVE — no findings | round 7; checked the saved-state seam, the undo lifetime, the fixture give-back, the helper tap and the evidence registry |
| Architecture / scope | chatgpt (Pro) | APPROVE — no findings | round 7; checked the KMP placement, the `allowedRoutes` guard, the ADR scope boundary, the serialization reuse |
| Tests / acceptance criteria | chatgpt (Pro) | APPROVE — no findings | round 7; checked the three acceptance criteria against the records, and that the flow changes did not weaken their assertions |

All three roles ran with the same coordinates and project context, in separate
conversations, one after another (the ChatGPT window on this machine is single), and
none saw another's pass.

Raw answers: [role A](3191-review/role-a-chatgpt.txt),
[role B](3191-review/role-b-chatgpt.txt),
[role C](3191-review/role-c-chatgpt.txt).

## Remaining risks

- The undo offer now stays up for ten seconds instead of four. A user who deletes a
  task and does nothing sees the "Undo" affordance twice as long. Material 3 asks for
  that lifetime for a snackbar that carries an action, and a notice without an action
  is untouched — but it is a visible change in the shell, not a change a test can see.
- The saved route is new state in the shell: a restore that names a route the app has
  no stack for is refused, and a payload this build cannot decode falls back to the
  start route. The screen can therefore come back on Today after a process death —
  the old behaviour, not a worse one.
- The tab *history* is not saved, only the tab. Before this change the rotation reset
  the tab itself, so the history was lost anyway — this is the documented contract
  now, not a new gap.
- The orientation fixture runs `adb shell settings put` before every flow: one extra
  adb round trip per flow. A setting it cannot read is reported once and cannot be
  given back — the readable one still is.
- Two of the seven causes did not reproduce on the current build. They stay registered
  as debt rather than being written off, so a recurrence is this cause and not a new
  one.
- The qualification records name the build that ran
  (`3c637206d564596975c00fd67be1c71a66a5ec87`), not the commit that contains them — a
  record cannot name its own commit, because naming it would change the tree it
  certifies. The build is kept reachable as the tag `qualification-3191-rebase-build`.
- `main` moved on twice while this branch was open (PR #3209, and later the
  seed-helper migration #2554/#2556), so the branch was rebased onto it and the
  evidence was measured again on the rebased tree. The seed helper is a transitive
  dependency of every flow's fingerprint, so its contract-text change moved the
  fingerprints of all fourteen flows and the requalification had to be repeated.
  Three files that both sides touched were resolved by hand — the no-results flow
  (both sides' lines kept), the pinned-filter flow (the wait this change added is
  kept) and the qualification registry (resolved semantically: `main`'s exceptions
  for these flows are gone, the stale `attachments/02-delete` exception was
  dropped). The build of the first round is still reachable as the tag
  `qualification-3191-build`.

## Follow-up improvements

No follow-up improvements recommended. The reviewers judged the reuse and approach
assessment complete and found nothing that warrants a separate issue.
