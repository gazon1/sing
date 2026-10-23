# Tasks: The data survives being unsure about it

Module prefixes: `shared/`, `mcp-server/`, `desktopApp/`.

## 1. Tests first

- [ ] shared/ Add the fail-safe probe tests: locked, unreadable, corrupted, empty, valid-not-ours, valid-ours — asserting content equality, not file presence. `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/StaleDbCleanerTest.kt`
- [ ] mcp-server/ Add the profile argument tests: valid id, unknown id, both selectors, legacy label for each documented value, neither. `mcp-server/src/test/kotlin/com/singularity/todo/mcp/ProfileArgumentTest.kt`
- [ ] mcp-server/ Add the error response tests: a quote, a newline, a control character; valid message, no path. `mcp-server/src/test/kotlin/com/singularity/todo/mcp/JsonRpcErrorTest.kt`
- [ ] shared/ Add the classification completeness assertions to the existing registry consistency test. `desktopApp/src/jvmTest/kotlin/com/singularity/todo/ToolSurfaceWiringTest.kt`
- [ ] shared/ Add the proposal contract test: confirm writes, decline does not. `shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/ToolWriteContractTest.kt`

## 2. The probe

- [ ] shared/ Rewrite the startup probe so that deletion happens only for a readable, valid database without the bookkeeping marker; every other outcome leaves the file and its companion files untouched and records the diagnostic. `shared/src/jvmMain/kotlin/com/singularity/todo/core/database/contract/StaleDbCleaner.kt`

## 3. The profile selector

- [ ] shared/ Add the fixed-profile repository that resolves a profile id without the stored active-profile state. `shared/src/jvmMain/kotlin/com/singularity/todo/feature/profile/FixedProfileRepository.kt`
- [ ] shared/ Extract the profile bindings into a module and add it to the domain module **list** — never `includes()`, whose child scope is invisible to siblings. `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt`
- [ ] mcp-server/ Accept the profile id, refuse both selectors together, keep the legacy label's meaning and log its deprecation, build error responses with the serialiser, keep internal detail out of the response, and leave no half-started process on a startup failure. `mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt`

## 4. The classification

- [ ] shared/ Add the write behaviour field with no default, plus the exception reason, and classify every registered tool. `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/McpToolCatalog.kt`
- [ ] shared/ Regenerate the exported tool surface in the same change. `config/docs/tool-surface.json`

## 5. Verification

- [ ] Run the probe tests, the argument tests, the error tests and the classification checks. `./gw :shared:jvmTest`
- [ ] Run the linter over the changed modules. `./gw :shared:detekt`
