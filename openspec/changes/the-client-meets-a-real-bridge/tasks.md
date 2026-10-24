# Tasks: The client meets a real bridge

Module prefixes: `shared/`, `mcp-server/`, `desktopApp/`.

## 1. The probe

- [x] shared/ Spawn a real bridge and drive the application's own client against it, and report the verdict, the agent's name and version, the methods the bridge used and its frames. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Take the transcript underneath the protocol's reader, and read it when the bridge has gone quiet — a bridge that announces things after the session it answered must not have its announcements cut off by the snapshot. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpBridgeProbe.kt`
- [x] shared/ Give the probe a Gradle task, beside the tool surface's export. `shared/build.gradle.kts`
- [x] shared/ Give the probe a script whose exit code is the verdict. `scripts/acp-bridge-probe.sh`

## 2. What the bridge sent that the fake did not

- [x] shared/ Send the authentication status notification in the fake, as the bridge sends it right after the handshake. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Send the available-commands notification in the fake, as the bridge sends it right after the session is open. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Send the bridge's five permission modes and its three configuration options in the fake's session answer. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/FakeAcpAgent.kt`
- [x] shared/ Test that a notification the application has no answer for does not stop the conversation, and produces no activity for the reader. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`

## 3. The records

- [x] shared/ Record the decision this change makes. `docs/decisions/2026-10-24-the-client-meets-a-real-bridge.md`
- [x] shared/ Write down the bridge's own frames, so that every claim about them can be checked without a network. `openspec/changes/the-client-meets-a-real-bridge/bridge-transcript.txt`
- [x] shared/ Say in the architecture document how to run the probe, and what it covers. `docs/architecture/acp-client.md`
- [x] shared/ Refresh the decisions digest. `./scripts/refresh-decisions-digest.sh`
- [ ] shared/ Write the verdict for this change's review. `.specify/reviews/the-client-meets-a-real-bridge.md`

## 4. Verification

- [x] Run the tests for the changed module. `./gw :shared:jvmTest --tests "com.singularity.todo.feature.ai.acp.*" -Ptest.population=full`
- [x] Run the linter over the changed module. `./gw :shared:detekt`
- [x] Run the probe against the real bridge. `scripts/acp-bridge-probe.sh`
