# Tasks — a-turn-that-hangs-has-ended

## 1. The turn's clock

- [X] shared/ Give the turn an inactivity bound: no event for 60 seconds ends it as a failure
      that names the silence, and the runner collects the turn's event stream so the bound
      lives with the turn's other rules.
      `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpTurnRunner.kt`
- [X] shared/ Report, once and for the current turn only, that the session is gone when a turn
      ends without a completion and the reader did not stop it.
      `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpTurnRunner.kt`

## 2. The connection state

- [X] shared/ Mark the connection failed (with the reason) when the session is lost, close the
      session, and make the tool access unavailable — so the reader sees the reason and the
      Connect control, and the partial answer stays.
      `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt`

## 3. The tests

- [X] shared/ A turn that produces no event for the idle window ends as failed with a reason
      that names it, and the connection is marked failed. Advance the virtual clock past the
      window; assert the outcome and the connection.
      `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModelTest.kt`
- [X] shared/ A turn whose stream ends without a completion marks the connection failed and
      keeps the partial answer — and a turn the reader stopped does not.
      `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModelTest.kt`
- [X] desktopApp/ After the session is lost, the screen shows the reason and offers the
      Connect control again.
      `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreenTest.kt`

## 4. The gates

- [X] Run the shared tests for the affected area — `--rerun`, or Gradle reports UP-TO-DATE and
      runs nothing. `./gw :shared:jvmTest --tests "*AcpExternalAgentViewModelTest*" --rerun`
- [X] Run the desktop screen test — same `--rerun`, and the class is `@Tag("slow")`.
      `./gw :desktopApp:test -Ptest.tags=slow --tests "*AcpExternalAgentScreenTest*" --rerun`
- [X] Run the linter over the changed files. `python3 scripts/lint-changed.py`
