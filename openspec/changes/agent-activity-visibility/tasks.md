# Tasks: The agent stops working in the dark

Module prefixes: `shared/`.

## 1. The shared vocabulary

- [ ] shared/ Declare the activity vocabulary: text additions, tool-call updates with a status, plan updates, and successful completion. `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/AgentEvent.kt`
- [ ] shared/ Change the streaming port to return activity rather than text, leaving the plain-text form available for callers that only need the answer. `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt`
- [ ] shared/ Update the text-generation fake so the module compiles and the fake can emit any event sequence, including one that ends without a completion event. `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeTextGen.kt`
- [ ] shared/ Add the shared card presentation: tool call with status, expandable bounded summary, plan. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/ui/AgentActivityCard.kt`
- [ ] shared/ Add the vocabulary tests: deltas accumulate, a known tool id updates in place, a plan replaces the previous snapshot, summaries are bounded. `shared/src/commonTest/kotlin/com/singularity/todo/core/llm/AgentEventTest.kt`

## 2. Tests first

- [ ] shared/ Add the loop tests: a running entry before the call, completed or failed after it, a completion at the end. `shared/src/commonTest/kotlin/com/singularity/todo/core/ai/ToolCallActivityTest.kt`
- [ ] shared/ Add the turn-outcome tests through the ViewModel harness: success, a stream that ends without completion, cancellation, and a late event after the outcome. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnOutcomeTest.kt`

## 3. The built-in agent

- [ ] shared/ Rewrite the loop to emit activity, and rewrite the KDoc section that says tool calls are not the reader's business. `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/ToolCallLoop.kt`
- [ ] shared/ Map the model's frames onto the activity vocabulary. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/KoogAgentService.kt`
- [ ] shared/ Pass activity through while preserving the usage accounting. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/UsageRecordingTextGen.kt`

## 4. The chat

- [ ] shared/ Hold the turn's activity and outcome in the chat state, and resolve the turn on every path so cancellation cannot leave the loading state set. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatViewModel.kt`
- [ ] shared/ Render the cards. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatScreen.kt`
- [ ] shared/ Add the automation tags for the cards and regenerate the tag catalog. `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`, `Maestro/TAGS.md`

## 5. Verification

- [ ] Run the new tests and the chat tests. `./gw :shared:jvmTest`
- [ ] Run the linter over the shared module. `./gw :shared:detekt`
