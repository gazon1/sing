# Tasks — the-refusal-is-reachable-from-the-screen

## 1. The screen lets the rule say no

- [X] shared/ Keep the external-agent input enabled while a turn runs, so a second message
      reaches the ViewModel and is refused there with a message instead of being stopped by a
      disabled field. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreen.kt`
- [X] shared/ Assert the refusal's message, not only that the second prompt was not sent — the
      "say why" half of REQ-EXTAG-007 was unasserted. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModelTest.kt`
- [X] desktopApp/ Drive a running turn on the real screen and assert the input is enabled and
      the Stop control is present. `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreenTest.kt`

## 2. The gates

- [X] Run the shared tests for the affected area. `./gw :shared:jvmTest --tests "*AcpExternalAgentViewModelTest*"`
- [X] Run the desktop screen test. `./gw :desktopApp:jvmTest --tests "*AcpExternalAgentScreenTest*"`
- [X] Run the linter over the changed files. `python3 scripts/lint-changed.py`
