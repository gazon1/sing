# Tasks — usage-recording-platform-parity

- [ ] Add the `UsageRecordingTextGen` binding to `AiToolsModule.android.kt`,
      mirroring `AiToolsModule.jvm.kt:105-118` — the decorator and its recorder
      are platform-neutral, so the diff should be the binding and nothing else.
- [ ] Write the DI test **before** the binding, and watch it fail on Android.
      A test that passes on both source sets from the start has not proved the
      parity exists.
- [ ] The test resolves `TextGenPort` and asserts the resolved instance is a
      `UsageRecordingTextGen`. Assert on the resolved type rather than on the
      binding's source text — a source-text assertion passes for a binding that
      is present but shadowed.
- [ ] Record where the check lives, and why. The ADR's `core` → `feature/ai`
      import direction is the repository's only feature-referenced-from-`core/di`
      case; a test in `core/di` inherits the inversion, so either place it where
      that is acceptable or state the reason.
- [ ] Verify against real data, not just the wiring: make one LLM call from an
      Android build and confirm a row lands in `llm_usage`. The binding test
      proves the decorator is in the graph; only the call proves the recorder is
      reachable from the agent.
- [ ] Close the backlog entry in place, recording that both platforms now route
      through the decorator, and reference this change from issue #106.
- [ ] Grep for any other `TextGenPort` binding that bypasses the decorator. The
      parity test covers the source sets it knows about; a third binding in a
      platform added later is the case it cannot see.
