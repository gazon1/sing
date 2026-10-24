# Tasks: The client meets a real bridge

Module prefixes: `shared/`, `mcp-server/`, `desktopApp/`.

## 1. The probe

- [x] shared/ Spawn a real bridge and drive the application's own client against it, and report the verdict, the agent's name and version, the methods the bridge used and its frames. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Take the transcript underneath the protocol's reader, and read it over a window after the connect — a bridge that announces things after the session it answered must not have its announcements cut off by the snapshot. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Read the transcript over a fixed window and not over a quiet period, so that a bridge which announces on a timer cannot have its announcement cut off — every time, not sometimes. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Copy the bridge's bytes and decode them once, so that a chunk boundary inside a multi-byte character cannot put a replacement character into a transcript that is called verbatim. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Give the probe a Gradle task, beside the tool surface's export. `shared/build.gradle.kts`
- [x] shared/ Keep the probe's verdict out of the build's exit code, so that a probe which did not connect is a report and not a failed build. `shared/build.gradle.kts`
- [x] shared/ Give the probe a script whose exit code is the verdict, and which tells the three answers apart: the build failed, the bridge did not answer, or the handshake completed. `scripts/acp-bridge-probe.sh`

## 2. What the bridge sent that the fake did not

- [x] shared/ Send the authentication status notification in the fake, as the bridge sends it during the handshake and before the session answer. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Send the available-commands notification in the fake during the turn, where a session update could become activity the reader sees — a notification sent before the turn is handed to this application's own notification handler, which does nothing, and never reaches the client's adapter. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Send the bridge's five permission modes and its three configuration options in the fake's session answer. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Test that a notification the application has no answer for does not stop the conversation, and produces no activity for the reader — counted, not merely absent from the texts. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`
- [x] shared/ Write down what the fake sent, so that the test can show the notification really was sent while the turn was running. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`

## 3. The records

- [x] shared/ Record the decision this change makes. `docs/decisions/2026-10-24-the-client-meets-a-real-bridge.md`
- [x] shared/ Write down the bridge's own frames, so that every claim about them can be checked without a network — with the machine's own model list and command catalogue redacted, and the shape of every frame kept. `openspec/changes/the-client-meets-a-real-bridge/bridge-transcript.txt`
- [x] shared/ Say in the architecture document how to run the probe, and what it covers. `docs/architecture/acp-client.md`
- [x] shared/ Refresh the decisions digest. `./scripts/refresh-decisions-digest.sh`
- [x] shared/ Write the verdict for this change's review. `.specify/reviews/the-client-meets-a-real-bridge.md`

## 4. Verification

- [x] Run the tests for the changed module. `./gw :shared:jvmTest --tests "com.singularity.todo.feature.ai.acp.*" -Ptest.population=full`
- [x] Run the linter over the changed module. `./gw :shared:detekt`
- [x] Run the probe against the real bridge. `scripts/acp-bridge-probe.sh`
