# llm-usage-recording

## ADDED Requirements

### Requirement: REQ-1 Every LLM text-generation call is recorded, on every platform

Every call made through the application's `TextGenPort` SHALL be recorded with
its token counts, cost and duration, on every platform the application builds
for.

The recording SHALL be applied by binding the decorator at the DI layer rather
than at each call site, so a new call site cannot opt out by omission, and a new
platform cannot ship without it by being forgotten.

A test SHALL resolve `TextGenPort` on each platform source set and assert the
resolved instance is the recording decorator. A source-text assertion is not
sufficient: it passes for a binding that is present but shadowed.

**Rationale:** `AiToolsModule.jvm.kt` bound `TextGenPort` through
`UsageRecordingTextGen` with a `RoomUsageRecorder`; `AiToolsModule.android.kt`
bound it straight to a raw agent. Each platform's build was green, nothing
threw, and `llm_usage` — the table the dogfooding MCP server reads to know what
its own agent calls cost — held JVM rows and no Android rows. The feature looked
alive on the platform used for development and was silently absent on the
platform used by users.

#### Scenario: An Android build makes an LLM call

- **Given** an Android build with `TextGenPort` resolved from the graph
- **When** a text-generation call completes
- **Then** a row is written to `llm_usage` carrying tokens, cost and duration

#### Scenario: A new platform source set is added

- **Given** a platform with no explicit `TextGenPort` binding of its own
- **When** the recording test runs on that source set
- **Then** it fails
- **And** the omission is visible as a missing binding rather than as empty
      tables discovered later

#### Scenario: A call site bypasses the port

- **Given** code that constructs an agent directly instead of resolving
      `TextGenPort`
- **When** the recording invariant is checked
- **Then** the direct construction is reported
- **And** it is not left to a quarterly manual audit
