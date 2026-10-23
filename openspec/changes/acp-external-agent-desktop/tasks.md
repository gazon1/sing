# Tasks: The user brings their own agent

Module prefixes: `shared/`, `desktopApp/`, `scripts/`.

## 1. The probe — before anything else

- [ ] scripts/ Add the reproducible packaging probe: what the packaged classpath carries, whether the tool server's main class starts from it, and where the application jar and the bundled runtime live. `scripts/probe-packaged-tool-server.sh`
- [ ] shared/ Run the probe against a real packaged build and record the result in the decision before the delivery mechanism is chosen. `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## 2. Tests first

- [ ] shared/ Add the event mapping tests, covering every session update the protocol can produce, including the ones deliberately ignored. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpEventAdapterTest.kt`
- [ ] shared/ Add the duplex process tests: a child that writes more than a pipe buffer holds, a child that writes to the error stream, a clean end of stream, a missing binary. `shared/src/jvmTest/kotlin/com/singularity/todo/core/process/SubprocessDuplexTest.kt`
- [ ] shared/ Add the process-tree test with a fake agent that starts a child of its own; after close, nothing from the tree remains. `shared/src/jvmTest/kotlin/com/singularity/todo/core/process/ProcessTreeTest.kt`
- [ ] shared/ Add the client tests against a fake agent process: connect, stream, cancel, a crash mid-turn, a hung process, an agent that is not installed, a rejected protocol version. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`
- [ ] shared/ Add the screen-state tests through the ViewModel harness: permission accepted, declined, timed out, cancelled with a request outstanding, a second turn refused, and the no-access warning surviving the first reply. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModelTest.kt`
- [ ] desktopApp/ Add the screen tests: agent selection, streaming text, the cards, the permission sheet, cancel, and the warning visible before the first message. `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreenTest.kt`

## 3. Process ownership

- [ ] shared/ Add the duplex spawn to the one file allowed to spawn processes; the error stream is drained separately and never folded into the protocol stream, and no read end is closed early. `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/Subprocess.kt`
- [ ] shared/ Add the process-tree owner: descendants deepest first, then the root, with a grace period and an idempotent close. `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/ProcessTree.kt`

## 4. The client

- [ ] shared/ Add the command provider that returns a verified launch command or a diagnosable failure naming the command and where it was expected. `shared/src/jvmMain/kotlin/com/singularity/todo/core/mcp/McpServerCommandProvider.kt`
- [ ] shared/ Add the agent registry as data, with exactly one default entry. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentProfile.kt`
- [ ] shared/ Add the client — the only file that imports the protocol library. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt`
- [ ] shared/ Add the session operations: request permissions and notify, and nothing else. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClientSessionOperations.kt`
- [ ] shared/ Add the event adapter from protocol updates to the application's activity vocabulary. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpEventAdapter.kt`

## 5. The screen

- [ ] shared/ Add the screen state and intents; the session and the process tree belong to it. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt`
- [ ] shared/ Add the screen, reusing the shared activity cards. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreen.kt`
- [ ] shared/ Add the bindings. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/di/AcpDiModule.jvm.kt`
- [ ] desktopApp/ Compose the bindings into the application. `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`
- [ ] shared/ Add the navigation entry, so the screen is a real destination rather than a preview. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt`
- [ ] desktopApp/ Package the tool server into the distribution the way the probe decided. `desktopApp/build.gradle.kts`

## 6. Verification

- [ ] Run the new tests. `./gw :shared:jvmTest`
- [ ] Run the desktop tests. `./gw :desktopApp:test`
- [ ] Run the linter over the changed modules. `./gw :shared:detekt`
- [ ] Run the manual pass against a real agent on a packaged build. `specs/014-acp-client-agent-activity/quickstart.md`
