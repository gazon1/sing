# Tasks — the-summary-names-only-what-identifies

## 1. The rule

- [X] shared/ Make the summary field-based: quote a string value only when its field is one
      that identifies, and elide every other string whatever its length; numbers and flags
      stay quoted by the value rule. The identifying set is explicit and documented.
      `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/ToolCallSummary.kt`
- [X] shared/ Amend FR-020's measurable clause to say the field decides, and record the
      closed gap in the feature's assumptions.
      `specs/014-acp-client-agent-activity/spec.md`

## 2. The tests

- [X] shared/ A short, single-line free-text value (a one-line note body) is elided, and the
      field is still named.
      `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/ToolCallSummaryTest.kt`
- [X] shared/ A short description is elided while the title beside it is quoted.
      `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/ToolCallSummaryTest.kt`
- [X] shared/ Identifying values are quoted (identifier, state, number, flag), and the
      200-character bound still holds.
      `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/ToolCallSummaryTest.kt`
- [X] shared/ The activity vocabulary's length bound still holds with identifying fields.
      `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/AgentActivityTest.kt`

## 3. The gates

- [X] Run the shared tests for the affected area — `--rerun`, or Gradle reports UP-TO-DATE.
      `./gw :shared:jvmTest --tests "*ToolCallSummary*" --tests "*AgentActivity*" --tests "*ToolCallActivity*" --rerun`
- [X] Run the linter over the changed files. `python3 scripts/lint-changed.py`
