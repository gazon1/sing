# Tasks: The chat's turn is one model call

Module prefixes: `shared/`, `desktopApp/`.

## 1. The port takes the instructions for one call

- [X] shared/ Give `TextGenPort.streamChat` an `instructions` parameter, so that a call can be told what it may draw — and only that call. `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt`
- [X] shared/ Compose the instructions into the system prompt of the call, after the configured one, so the persona stays the persona and the catalogue is an addition. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/KoogAgentService.kt`
- [X] shared/ Pass the instructions through the decorator, so the usage row still describes the call that happened. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/UsageRecordingTextGen.kt`
- [X] shared/ Let the shared fake carry the instructions, so a test can assert that the chat asked for a screen. `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeTextGen.kt`
- [X] shared/ End the turn as failed when no credentials are configured, instead of a completed turn whose text says the key is missing. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/KoogAgentService.kt`

## 2. The screen is a format of the answer

- [X] shared/ Own the framer, the parser, the processor and the controller in a turn of its own, so that a screen belongs to the answer that drew it. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/SurfaceTurn.kt`
- [X] shared/ Say what the model is told when a screen was rejected, in the words the screen's owner uses. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/SurfaceTurn.kt`
- [X] shared/ Keep the rules that move: one framer per attempt, the surface deleted before it is drawn again, prose and messages told apart, an advisory error is not a rejection. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/SurfaceTurn.kt`

## 3. The turn

- [X] shared/ Own the model call, the conversation's context and the correction loop in one place, and emit the turn's activity as one stream. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatTurn.kt`
- [X] shared/ Hand the model's text to the screen as it arrives, so a screen is drawn while the answer is still arriving. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatTurn.kt`

## 4. The chat

- [X] shared/ Ask the turn, fold its events into the state, and record exactly one outcome — on every path, including a cancelled turn. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatViewModel.kt`
- [X] shared/ Keep the job of the turn in flight, so the reader can stop it. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatViewModel.kt`
- [X] shared/ Offer the reader a way to stop a running turn, next to the input. `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ChatInputBar.kt`
- [X] shared/ Bind the turn in the graph, and take the transport bindings out of it. `shared/src/commonMain/kotlin/com/singularity/todo/core/di/AiToolsModule.jvm.kt`
- [X] shared/ Bind the turn in the graph on the phone as well, so the two platforms do not drift. `shared/src/androidMain/kotlin/com/singularity/todo/core/di/AiToolsModule.android.kt`
- [X] shared/ Take the transport bindings out of the screen's own module. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/di/GenuiDiModule.kt`

## 5. What is deleted

- [X] shared/ Delete the transport seam: the interface, the platform implementation and the recorder that gave a screen its own ledger. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/transport/`
- [X] shared/ Delete the session that owned a model call of its own. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/GenuiSession.kt`

## 6. The tests

- [X] shared/ Test that a tool call in the chat becomes a card, and that the turn ends with one outcome — through the wiring the graph builds, not through a branch the graph does not take. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnOutcomeTest.kt`
- [X] shared/ Test that a stopped turn is cancelled, keeps the answer so far, and leaves no spinner. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnOutcomeTest.kt`
- [X] shared/ Test that the screen still renders when the answer draws one, and that a rejected screen is corrected. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnTest.kt`
- [X] shared/ Test that the chat asks for a screen — the catalogue reaches the model as that call's instructions. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/chat/ChatTurnOutcomeTest.kt`
- [X] shared/ The turn's cost is written by the one decorator that wraps the port: the screen's own recorder is gone, so there is no second row to write. `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/transport/`

## 7. The records

- [X] shared/ Record the decision this change makes. `docs/decisions/2026-10-25-the-turn-is-one-model-call.md`
- [X] shared/ Say which requirement the built-in agent's missing plan producer is, and that this change does not close it. `specs/014-acp-client-agent-activity/tasks.md`
