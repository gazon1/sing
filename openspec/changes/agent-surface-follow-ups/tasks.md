# Tasks: The agent's surface says what it does

Module prefixes: `shared/`, `mcp-server/`, `desktopApp/`.

## 1. The write classification (1.1)

- [x] shared/ Publish the write behaviour and the exception reason in the exported tool surface, and bump the export's own version. `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolSurfaceExport.kt`
- [x] shared/ Regenerate the exported surface. `config/docs/tool-surface.json`
- [x] shared/ Enforce the classification in the catalogue: a direct-write exception carries a non-blank reason, and nothing else carries one. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
- [x] shared/ Reclassify `create_tag`: the tool proposes, so the declaration must say so. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
- [x] shared/ Check the declaration against the tools' behaviour in both directions, and name the exceptions the scan cannot reach. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/tools/WriteToolsOnlyProposeTest.kt`
- [x] shared/ Drive every proposal-gated tool through the round trip: the call proposes, the user's data is unchanged, the confirmation writes, the refusal changes nothing. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/`

## 2. The client's lifecycle (1.3)

- [x] shared/ Guard the client's transitions with a lifecycle lock, and read the fields that are read outside it safely. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpClient.kt`
- [x] shared/ Test the race: a turn in flight and a close during it. `shared/src/jvmTest/kotlin/com/singularity/todo/feature/ai/acp/AcpClientProcessTest.kt`

## 3. The protocol's standard output (1.4)

- [ ] shared/ Make the protocol's output a type that owns the stream, and pass it to everything that writes the protocol. `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ProtocolStdout.kt`
- [ ] shared/ Point the logger at standard error explicitly, so the application's own log does not depend on the global redirect. `shared/src/jvmMain/kotlin/com/singularity/todo/core/log/`
- [ ] shared/ Pin the contract: the protocol channel is the original standard output, and the logger writes to standard error without the redirect in place. `shared/src/jvmTest/kotlin/com/singularity/todo/mcp/ProtocolStdoutTest.kt`

## 4. The module's rules (2.2)

- [ ] mcp-server/ Judge the module by the root detekt configuration and by the project's own rules, like every other module. `mcp-server/build.gradle.kts`
- [ ] mcp-server/ Fix whatever that surfaces, rather than baselining it. `mcp-server/src/test/kotlin/com/singularity/todo/mcp/McpServerEndToEndTest.kt`

## 5. The two god classes (2.3)

- [ ] shared/ Split the external agent's ViewModel into a connection, a turn and a permission broker, and keep the MVI surface in the ViewModel. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentViewModel.kt`
- [ ] shared/ Move the tool server's start-up out of the entry point, so the entry point parses, starts and serves. `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolServerMain.kt`

## 6. The records

- [x] shared/ Record the decisions this change makes. `docs/decisions/`
- [x] shared/ Refresh the decisions digest. `./scripts/refresh-decisions-digest.sh`
- [ ] shared/ Write the verdict for this change's review. `.specify/reviews/agent-surface-follow-ups.md`

## 7. Verification

- [ ] Run the tests for the changed modules. `./gw :shared:jvmTest :desktopApp:test -Ptest.tags=fast,slow`
- [ ] Run the linter over the changed modules. `./gw :shared:detekt :desktopApp:detekt :mcp-server:detekt :androidApp:detekt`
- [ ] Run the structural gates. `PYTHONPATH=scripts/src python3 scripts/check-gate-wiring.py`
